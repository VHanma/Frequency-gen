package com.vaan.contactomega

import android.app.Activity
import android.content.Context
import android.hardware.*
import org.json.JSONArray
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

class SensorLab(
    private val activity: Activity,
    private val store: SessionStore,
    private val onUpdate: (Snapshot, String?) -> Unit
) : SensorEventListener {
    data class Snapshot(
        val mag: Double = 0.0, val accel: Double = 0.0, val gyro: Double = 0.0,
        val light: Double = 0.0, val pressure: Double = 0.0, val proximity: Double = 0.0,
        val humidity: Double = 0.0, val temperature: Double = 0.0,
        val yesScore: Double? = null, val noScore: Double? = null, val word: String? = null
    ) { fun vector() = doubleArrayOf(mag, accel, gyro, light, pressure, proximity, humidity, temperature) }

    private class Stats {
        var n=0; var mean=0.0; var m2=0.0
        fun add(x:Double){n++; val d=x-mean; mean+=d/n; m2+=d*(x-mean)}
        fun z(x:Double)=if(n>5) abs(x-mean)/sqrt((m2/(n-1)).coerceAtLeast(1e-9)) else 0.0
    }

    private val sm = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val prefs = activity.getSharedPreferences("omega_field_signatures", Context.MODE_PRIVATE)
    private val values = mutableMapOf<Int, Double>()
    private val stats = mutableMapOf<Int, Stats>()
    private var active=false
    private var baselineUntil=0L
    private var freezeBaselineUntil=0L
    private var yes: DoubleArray? = loadSignature("YES")
    private var no: DoubleArray? = loadSignature("NO")
    private var captureLabel: String? = null
    private val capture = mutableListOf<DoubleArray>()
    private var lastEmit=0L
    private var lastWord=0L
    private var lastAnomaly=0L
    private val words = ("yes no hello here there listen speak light dark near far now later friend name who what where when why peace help stop go come see hear feel energy field signal voice image sky ground water fire air time one two three four five open close left right above below inside outside again repeat clear quiet strong weak fast slow warm cold bright blue red green white black human other contact answer question true false same different together alone message pattern change stable move wait ready north south east west home star moon sun earth space plasma mind dream memory sound radio pulse number circle line point door bridge body life love fear know think watch find give take good bad safe unknown return begin end").split(" ")

    fun start() {
        if(active) return
        active=true
        baselineUntil=System.currentTimeMillis()+6000
        freezeBaselineUntil=0L
        stats.clear()
        listOf(
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_LIGHT, Sensor.TYPE_PRESSURE, Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_RELATIVE_HUMIDITY, Sensor.TYPE_AMBIENT_TEMPERATURE
        ).forEach { t -> sm.getDefaultSensor(t)?.let { sm.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME) } }
        store.event("FIELD_LAB_START", mapOf("baselineMs" to 6000, "quarantineMs" to 3500, "yesPersisted" to (yes!=null), "noPersisted" to (no!=null)))
    }

    fun stop(){ if(!active)return; active=false; sm.unregisterListener(this); store.event("FIELD_LAB_STOP") }
    fun isRunning() = active

    fun beginCalibration(label:String){
        captureLabel=label.uppercase(); capture.clear()
        store.event("CALIBRATION_START", mapOf("label" to captureLabel, "channels" to 8))
    }

    fun finishCalibration(){
        val label=captureLabel ?: return; captureLabel=null
        if(capture.isNotEmpty()) {
            val m=DoubleArray(8)
            capture.forEach { v -> for(i in m.indices)m[i]+=v.getOrElse(i){0.0} }
            for(i in m.indices)m[i]/=capture.size
            if(label=="YES") yes=m else if(label=="NO") no=m
            saveSignature(label,m)
            store.event("CALIBRATION_SAVED", mapOf("label" to label,"samples" to capture.size,"vector" to m.joinToString(","),"persistent" to true))
        }
        capture.clear()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if(!active)return
        val v=if(e.values.size>=3) sqrt((e.values[0]*e.values[0]+e.values[1]*e.values[1]+e.values[2]*e.values[2]).toDouble()) else e.values[0].toDouble()
        values[e.sensor.type]=v
        val st=stats.getOrPut(e.sensor.type){Stats()}
        val now=System.currentTimeMillis()
        val baseline=now<baselineUntil
        if(baseline) st.add(v)
        if(now-lastEmit<140)return
        lastEmit=now

        val snap0=Snapshot(
            mag=values[Sensor.TYPE_MAGNETIC_FIELD]?:0.0,
            accel=values[Sensor.TYPE_ACCELEROMETER]?:0.0,
            gyro=values[Sensor.TYPE_GYROSCOPE]?:0.0,
            light=values[Sensor.TYPE_LIGHT]?:0.0,
            pressure=values[Sensor.TYPE_PRESSURE]?:0.0,
            proximity=values[Sensor.TYPE_PROXIMITY]?:0.0,
            humidity=values[Sensor.TYPE_RELATIVE_HUMIDITY]?:0.0,
            temperature=values[Sensor.TYPE_AMBIENT_TEMPERATURE]?:0.0
        )
        captureLabel?.let { capture += snap0.vector() }
        val y=yes?.let{distance(snap0.vector(),it)}
        val n=no?.let{distance(snap0.vector(),it)}
        var word:String?=null
        var event:String?=null

        if(!baseline) {
            var peak=0.0; var peakType=0
            values.forEach { (t,x)-> val z=stats[t]?.z(x)?:0.0; if(z>peak){peak=z;peakType=t} }
            if(peak>=3.5 && now-lastAnomaly>1100){
                lastAnomaly=now
                freezeBaselineUntil=maxOf(freezeBaselineUntil,now+3500L)
                store.event("FIELD_ANOMALY",mapOf("peakSigma" to peak,"sensorType" to peakType,"quarantineUntilEpochMs" to freezeBaselineUntil,"vector" to snap0.vector().joinToString(",")))
            }
            if(peak>=3.5 && now-lastWord>1300){
                lastWord=now; word=wordFor(snap0.vector()); event="FIELD ${"%.1f".format(peak)}σ → $word"
                store.event("ENV_WORD", mapOf("word" to word,"peakSigma" to peak,"sensorType" to peakType,"vector" to snap0.vector().joinToString(",")))
            }
            if(now>freezeBaselineUntil && peak<1.5) {
                values.forEach { (t,x) -> stats[t]?.add(x) }
            }
        }

        val snap=snap0.copy(yesScore=y,noScore=n,word=word)
        store.sensorCsv(
            "tMs,mag,accel,gyro,light,pressure,proximity,humidity,temperature",
            "${store.elapsedMs()},${snap.mag},${snap.accel},${snap.gyro},${snap.light},${snap.pressure},${snap.proximity},${snap.humidity},${snap.temperature}"
        )
        onUpdate(snap,event)
    }

    private fun distance(a:DoubleArray,b:DoubleArray):Double {
        val n=minOf(a.size,b.size)
        if(n==0)return Double.POSITIVE_INFINITY
        var s=0.0
        for(i in 0 until n){ val scale=maxOf(abs(b[i]),1.0); val d=(a[i]-b[i])/scale; s+=d*d }
        return sqrt(s/n)
    }

    private fun wordFor(v:DoubleArray):String {
        val q=v.joinToString("|"){ "%.2f".format(it) }
        val h=MessageDigest.getInstance("SHA-256").digest(q.toByteArray())
        var x=0; for(i in 0..3)x=(x shl 8) or (h[i].toInt() and 0xff)
        return words[(x.toLong() and 0x7fffffffL).rem(words.size).toInt()]
    }

    private fun saveSignature(label:String,v:DoubleArray){
        val a=JSONArray();v.forEach{a.put(it)}
        prefs.edit().putString(label,a.toString()).apply()
    }

    private fun loadSignature(label:String):DoubleArray?{
        val raw=prefs.getString(label,null)?:return null
        return runCatching{val a=JSONArray(raw);DoubleArray(a.length()){i->a.optDouble(i)}}.getOrNull()
    }
}
