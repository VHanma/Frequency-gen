package com.vaan.voiceforgex

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

enum class SynthesisMode(val steps: Int, val candidates: Int) { FAST(3,1), BALANCED(6,2), OMEGA(10,5) }
object CloneEngine {
 private val mutex=Mutex(); @Volatile private var tts:OfflineTts?=null; @Volatile private var modelPath:String?=null
 fun invalidate(){synchronized(this){tts=null;modelPath=null}}
 private fun build(c:Context):OfflineTts{val d=ModelManager.dir(c);check(ModelManager.isReady(c)){"Voice model is not downloaded yet"};val p=OfflineTtsPocketModelConfig(lmFlow=File(d,"lm_flow.int8.onnx").absolutePath,lmMain=File(d,"lm_main.int8.onnx").absolutePath,encoder=File(d,"encoder.onnx").absolutePath,decoder=File(d,"decoder.int8.onnx").absolutePath,textConditioner=File(d,"text_conditioner.onnx").absolutePath,vocabJson=File(d,"vocab.json").absolutePath,tokenScoresJson=File(d,"token_scores.json").absolutePath,voiceEmbeddingCacheCapacity=384);return OfflineTts(config=OfflineTtsConfig(model=OfflineTtsModelConfig(pocket=p,numThreads=maxOf(2,Runtime.getRuntime().availableProcessors().coerceAtMost(6)),debug=false,provider="cpu"))).also{modelPath=d.absolutePath}}
 private fun instance(c:Context):OfflineTts{val d=ModelManager.dir(c).absolutePath;tts?.let{if(modelPath==d)return it};synchronized(this){return tts?:build(c).also{tts=it}}}
 suspend fun generate(c:Context,p:CloneProfile,text:String,mode:SynthesisMode=SynthesisMode.BALANCED):GeneratedAudio=mutex.withLock{
  val refs=VoiceGenome.references(p,if(mode==SynthesisMode.OMEGA)4 else if(mode==SynthesisMode.BALANCED)2 else 1).map{WavUtils.readPcm16Mono(it)};val engine=instance(c);val rendered=mutableListOf<GeneratedAudio>()
  for((ci,chunk) in chunkText(text).withIndex()){var best:GeneratedAudio?=null;var bestScore=Double.NEGATIVE_INFINITY;repeat(mode.candidates){k->val r=refs[k%refs.size];val x=engine.generateWithConfig(text=chunk,config=GenerationConfig(referenceAudio=r.samples,referenceSampleRate=r.sampleRate,numSteps=mode.steps,extra=mapOf("max_reference_audio_len" to "15","seed" to (42+k*7919+ci*104729).toString())));val score=refs.map{acousticFit(it.samples,x.samples)}.sortedDescending().take(2).average();if(score>bestScore){bestScore=score;best=x}};rendered+=best?:error("Voice generation returned no audio")};stitch(rendered,refs.first().sampleRate)
 }
 suspend fun generate(c:Context,p:CloneProfile,text:String,steps:Int)=generate(c,p,text,if(steps<=3)SynthesisMode.FAST else if(steps>=8)SynthesisMode.OMEGA else SynthesisMode.BALANCED)
 suspend fun play(c:Context,p:CloneProfile,text:String,mode:SynthesisMode=SynthesisMode.BALANCED)=playAudio(generate(c,p,text,mode));suspend fun play(c:Context,p:CloneProfile,text:String,steps:Int)=playAudio(generate(c,p,text,steps))
 private fun chunkText(t:String):List<String>{val clean=t.trim().replace(Regex("\\s+")," ");if(clean.length<=220)return listOf(clean);val out=mutableListOf<String>();var cur="";for(s in clean.split(Regex("(?<=[.!?])\\s+"))){if(cur.isNotEmpty()&&cur.length+s.length+1>220){out+=cur;cur=s}else cur=if(cur.isEmpty())s else "$cur $s"};if(cur.isNotBlank())out+=cur;return out.flatMap{if(it.length<=300)listOf(it) else it.chunked(260)}}
 private fun stitch(parts:List<GeneratedAudio>,fallback:Int):GeneratedAudio{if(parts.size==1)return parts[0];val rate=parts.firstOrNull()?.sampleRate?:fallback;val gap=FloatArray((rate*.045).toInt());val all=FloatArray(parts.sumOf{it.samples.size}+gap.size*(parts.size-1));var p=0;parts.forEachIndexed{i,a->a.samples.copyInto(all,p);p+=a.samples.size;if(i!=parts.lastIndex){gap.copyInto(all,p);p+=gap.size}};return GeneratedAudio(samples=all,sampleRate=rate)}
 private fun playAudio(a:GeneratedAudio){val min=AudioTrack.getMinBufferSize(a.sampleRate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);val tr=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(AudioFormat.Builder().setSampleRate(a.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(maxOf(min,32768)).setTransferMode(AudioTrack.MODE_STREAM).build();try{tr.play();val pcm=WavUtils.floatToPcm16(a.samples);var o=0;while(o<pcm.size){val n=tr.write(pcm,o,pcm.size-o);if(n<=0)break;o+=n}}finally{runCatching{tr.stop()};tr.release()}}
 private fun acousticFit(r:FloatArray,c:FloatArray):Double{fun f(s:FloatArray):DoubleArray{if(s.isEmpty())return DoubleArray(8);var sq=0.0;var z=0;var peak=0.0;var aa=0.0;var diff=0.0;var d2=0.0;var prev=s[0].toDouble();var pd=0.0;val step=(s.size/16000).coerceAtLeast(1);var n=0;var i=0;while(i<s.size){val v=s[i].toDouble();val d=v-prev;sq+=v*v;aa+=abs(v);peak=maxOf(peak,abs(v));diff+=abs(d);d2+=abs(d-pd);if((v>=0)!=(prev>=0))z++;pd=d;prev=v;n++;i+=step};val nn=n.coerceAtLeast(1);val rms=sqrt(sq/nn);val mean=aa/nn;return doubleArrayOf(rms,mean,z.toDouble()/nn,peak,diff/nn,d2/nn,(if(rms>.0001)peak/rms else 0.0).coerceAtMost(10.0)/10,(peak-mean).coerceAtLeast(0.0))};val a=f(r);val b=f(c);val w=doubleArrayOf(3.0,2.0,1.4,.7,1.6,1.2,.8,.8);var d=0.0;for(i in a.indices)d+=abs(a[i]-b[i])*w[i];return-d}
}
