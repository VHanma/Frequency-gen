package com.vaan.contactomega

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

class BeaconEngine(
    private val activity: Activity,
    private val store: SessionStore,
    private val onScreenFlash: (Boolean) -> Unit,
    private val onState: (String) -> Unit
) {
    private val active=AtomicBoolean(false)
    private val cm=activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val flashId:String? = try { cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)==true } } catch(_:Throwable){null}

    fun transmit(text:String, protocol:String, light:Boolean, screen:Boolean, audio:Boolean, onDone:()->Unit){
        if(!active.compareAndSet(false,true))return
        val adaptive = store.adaptiveChallenge()
        val effectiveText = if(protocol == "BINARY") text else "$text\nR3 ADAPTIVE CHALLENGE: $adaptive"
        val bits=packet(effectiveText,protocol)
        store.event("BEACON_TX_START",mapOf(
            "protocol" to protocol,
            "message" to text,
            "adaptiveChallenge" to adaptive,
            "effectivePayload" to effectiveText,
            "symbols" to bits.size,
            "r3FingerprintBeforeTx" to store.fingerprintSummary()
        ))
        store.event("R3_ADAPTIVE_CHALLENGE", mapOf("challenge" to adaptive))
        Thread {
            val sr=48000
            val track=if(audio) try { AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(sr/2).build().also{it.play()} }catch(_:Throwable){null}else null
            try{
                bits.forEachIndexed { idx,bit ->
                    if(!active.get())return@Thread
                    val on=if(bit==1)160L else 65L
                    if(light)torch(true); if(screen)onScreenFlash(true)
                    if(track!=null){
                        val n=(sr*on/1000).toInt(); val b=ShortArray(n); val hz=if(bit==1)1633.0 else 941.0
                        for(i in b.indices){
                            val edge = minOf(1.0, i/100.0, (b.size-i).coerceAtLeast(0)/100.0)
                            b[i]=(sin(2*PI*hz*i/sr)*8000*edge).toInt().toShort()
                        }
                        track.write(b,0,b.size,AudioTrack.WRITE_BLOCKING)
                    }
                    Thread.sleep(on)
                    if(light)torch(false); if(screen)onScreenFlash(false)
                    Thread.sleep(70)
                    if(idx%24==0)onState("R3 beacon ${idx+1}/${bits.size}")
                }
            } finally {
                torch(false); onScreenFlash(false)
                try{track?.stop()}catch(_:Throwable){}; track?.release()
                active.set(false)
                store.event("BEACON_TX_END", mapOf("adaptiveChallenge" to adaptive))
                activity.runOnUiThread{onDone()}
            }
        }.start()
    }

    fun stop(){active.set(false);torch(false);onScreenFlash(false)}

    private fun torch(on:Boolean){
        try{if(activity.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)flashId?.let{cm.setTorchMode(it,on)}}catch(_:Throwable){}
    }

    private fun packet(text:String,protocol:String):List<Int>{
        val out=mutableListOf<Int>()
        fun pattern(s:String){s.forEach{if(it=='0'||it=='1')out+=if(it=='1')1 else 0}}
        fun byte(v:Int){for(i in 7 downTo 0)out+=(v shr i) and 1}
        pattern("1110010110010111101001001")
        when(protocol){
            "PRIME"->listOf(2,3,5,7,11,13,17,19,23).forEach{byte(it);pattern("00")}
            "FIBONACCI"->listOf(1,1,2,3,5,8,13,21,34,55).forEach{byte(it);pattern("01")}
            "MATH+PHYSICS"->{
                listOf(2,3,5,7,11,13).forEach{byte(it)}
                pattern("000111000111")
                listOf(1,2,4,8,16,32,64,128).forEach{byte(it)}
            }
        }
        val data=text.toByteArray(StandardCharsets.UTF_8)
        byte(data.size.coerceAtMost(255))
        data.take(255).forEach{byte(it.toInt() and 0xff)}
        val crc=CRC32().apply{update(data.take(255).toByteArray())}.value
        for(i in 31 downTo 0)out+=((crc shr i) and 1L).toInt()
        pattern("1111000011110000")
        return out
    }
}
