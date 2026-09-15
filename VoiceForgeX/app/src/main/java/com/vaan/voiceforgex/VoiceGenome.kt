package com.vaan.voiceforgex

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

data class VoiceQuality(val score:Int,val seconds:Float,val rms:Float,val silencePercent:Int,val clippingPercent:Int,val dynamicRange:Float){
    fun summary():String="Q$score • ${"%.1f".format(seconds)}s • silence $silencePercent% • clip $clippingPercent%"
}
data class GenomeSample(val id:String,val path:String,val source:String,val quality:VoiceQuality,val createdAt:Long)

object VoiceGenome {
    private lateinit var app:Context
    private const val PREF="voiceforge_genomes"
    fun init(context:Context){app=context.applicationContext}
    private fun prefs()=app.getSharedPreferences(PREF,Context.MODE_PRIVATE)
    private fun key(id:String)="g:$id"

    fun quality(file:File):VoiceQuality{
        val w=WavUtils.readPcm16Mono(file); val s=w.samples; require(s.isNotEmpty()){"Empty reference"}
        var sq=0.0; var silence=0; var clip=0; var peak=0f; var low=1f
        for(v in s){val a=abs(v);sq+=(v*v);if(a<.008f)silence++;if(a>.985f)clip++;if(a>peak)peak=a;if(a>.01f&&a<low)low=a}
        val rms=sqrt(sq/s.size).toFloat(); val sp=silence*100/s.size; val cp=clip*100/s.size; val sec=s.size.toFloat()/w.sampleRate
        val dyn=if(low<1f)peak/low.coerceAtLeast(.001f) else peak
        var score=100; if(sec<3)score-=35 else if(sec<6)score-=15; if(sec>18)score-=5; score-=(sp*.35f).toInt();score-=(cp*3).coerceAtMost(35);if(rms<.015)score-=25 else if(rms<.035)score-=10;if(peak<.08)score-=12
        return VoiceQuality(score.coerceIn(0,100),sec,rms,sp.coerceIn(0,100),cp.coerceIn(0,100),dyn)
    }

    fun compatibility(profile:CloneProfile,candidate:File):Int{
        val refs=references(profile,3); if(refs.isEmpty())return 100
        val b=fingerprint(candidate); val scores=refs.map{ similarity(fingerprint(it),b) }
        return scores.sortedDescending().take(2).average().toInt().coerceIn(0,100)
    }
    private fun similarity(a:DoubleArray,b:DoubleArray):Double{var d=0.0;for(i in a.indices)d+=abs(a[i]-b[i]);return (100-d*115).coerceIn(0.0,100.0)}
    private fun fingerprint(file:File):DoubleArray{
        val s=WavUtils.readPcm16Mono(file).samples;if(s.isEmpty())return DoubleArray(5);val step=(s.size/16000).coerceAtLeast(1)
        var sq=0.0;var aa=0.0;var z=0;var peak=0.0;var diff=0.0;var n=0;var prev=s[0].toDouble();var i=0
        while(i<s.size){val v=s[i].toDouble();val av=abs(v);sq+=v*v;aa+=av;peak=maxOf(peak,av);diff+=abs(v-prev);if((v>=0)!=(prev>=0))z++;prev=v;n++;i+=step}
        val rms=sqrt(sq/n.coerceAtLeast(1));return doubleArrayOf(rms.coerceAtMost(.5)*2,(aa/n.coerceAtLeast(1)).coerceAtMost(.4)*2.5,(z.toDouble()/n.coerceAtLeast(1)).coerceAtMost(.5)*2,(diff/n.coerceAtLeast(1)).coerceAtMost(.5)*2,(if(rms>.0001)(peak/rms).coerceAtMost(8.0)/8 else 0.0))
    }

    fun ensureLegacy(p:CloneProfile){if(samples(p.id).isEmpty()){val f=File(p.wavPath);if(f.exists())addSample(p.id,f,"original",false)}}
    fun addSample(id:String,source:File,label:String,copy:Boolean=true):GenomeSample{
        val dst=if(copy){val d=File(app.filesDir,"genomes/$id").apply{mkdirs()};File(d,"${System.currentTimeMillis()}_${UUID.randomUUID()}.wav").also{source.copyTo(it,true)}}else source
        val q=quality(dst);val item=GenomeSample(UUID.randomUUID().toString(),dst.absolutePath,label,q,System.currentTimeMillis());save(id,(samples(id)+item).sortedByDescending{it.quality.score});return item
    }
    fun samples(id:String):List<GenomeSample>{
        val a=runCatching{JSONArray(prefs().getString(key(id),"[]"))}.getOrElse{JSONArray()};val out=mutableListOf<GenomeSample>()
        for(i in 0 until a.length()){val o=a.optJSONObject(i)?:continue;val p=o.optString("path");if(!File(p).exists())continue;val q=VoiceQuality(o.optInt("score"),o.optDouble("seconds").toFloat(),o.optDouble("rms").toFloat(),o.optInt("silence"),o.optInt("clip"),o.optDouble("dynamic").toFloat());out+=GenomeSample(o.optString("id"),p,o.optString("source"),q,o.optLong("createdAt"))}
        return out.sortedByDescending{it.quality.score}
    }
    fun references(p:CloneProfile,max:Int=4):List<File>{
        ensureLegacy(p);val all=samples(p.id).filter{it.quality.score>=45}.sortedByDescending{it.quality.score};if(all.isEmpty())return listOf(File(p.wavPath))
        val picked=mutableListOf<GenomeSample>(); for(s in all){if(picked.isEmpty()||picked.all{similarity(fingerprint(File(it.path)),fingerprint(File(s.path)))<96})picked+=s;if(picked.size>=max)break};return (if(picked.isEmpty())all.take(max) else picked).map{File(it.path)}
    }
    fun bestReference(p:CloneProfile)=references(p,1).first()
    fun summary(p:CloneProfile):String{ensureLegacy(p);val s=samples(p.id);val b=s.firstOrNull()?.quality;return if(b==null)"1 reference" else "${s.size} samples • ${references(p,4).size} Ω refs • ${b.summary()}"}
    fun removeSample(profileId:String,sampleId:String):Boolean{val old=samples(profileId);val x=old.firstOrNull{it.id==sampleId}?:return false;if(old.size<=1)return false;val f=File(x.path);if(f.path.contains("/genomes/"))runCatching{f.delete()};save(profileId,old.filterNot{it.id==sampleId});return true}
    fun delete(id:String){samples(id).forEach{val f=File(it.path);if(f.path.contains("/genomes/"))runCatching{f.delete()}};File(app.filesDir,"genomes/$id").deleteRecursively();prefs().edit().remove(key(id)).apply()}
    private fun save(id:String,list:List<GenomeSample>){val a=JSONArray();list.forEach{s->a.put(JSONObject().apply{put("id",s.id);put("path",s.path);put("source",s.source);put("createdAt",s.createdAt);put("score",s.quality.score);put("seconds",s.quality.seconds);put("rms",s.quality.rms);put("silence",s.quality.silencePercent);put("clip",s.quality.clippingPercent);put("dynamic",s.quality.dynamicRange)})};prefs().edit().putString(key(id),a.toString()).apply()}
}
