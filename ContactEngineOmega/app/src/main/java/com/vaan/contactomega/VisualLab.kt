package com.vaan.contactomega

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

class VisualLab(
    private val context: Context,
    private val store: SessionStore,
    private val onMetrics: (Double, Double) -> Unit,
    private val onEvent: (String) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var prevGrid: DoubleArray? = null
    private var baselineN = 0
    private var mean = 0.0
    private var m2 = 0.0
    private var lastSnap = 0L
    private var started = false

    fun start(previewView: PreviewView, owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get(); provider = p; p.unbindAll()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                val recorder = Recorder.Builder().build(); videoCapture = VideoCapture.withOutput(recorder)
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val plane = image.planes[0]; val buf = plane.buffer; val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
                        val w = image.width; val h = image.height
                        val gx = 16; val gy = 12; val grid = DoubleArray(gx*gy)
                        var sum=0.0; var idx=0
                        for (yy in 0 until gy) {
                            val y=(yy*h/gy).coerceIn(0,h-1)
                            for (xx in 0 until gx) {
                                val x=(xx*w/gx).coerceIn(0,w-1)
                                val pos=y*rowStride+x*pixelStride
                                val v=(buf.get(pos).toInt() and 0xff).toDouble(); grid[idx++]=v; sum+=v
                            }
                        }
                        val luma=sum/grid.size
                        val prev=prevGrid
                        var diff=0.0
                        if(prev!=null) for(i in grid.indices) diff += abs(grid[i]-prev[i])
                        diff/=grid.size; prevGrid=grid
                        if(baselineN<50){ baselineN++; val d=diff-mean; mean+=d/baselineN; m2+=d*(diff-mean) }
                        val sd=if(baselineN>5) sqrt((m2/(baselineN-1)).coerceAtLeast(0.25)) else 1.0
                        val z=if(baselineN>5) abs(diff-mean)/sd else 0.0
                        onMetrics(luma,diff)
                        if(z>=5.0 && System.currentTimeMillis()-lastSnap>2500){
                            lastSnap=System.currentTimeMillis(); val msg="VISUAL frame-change ${"%.1f".format(z)}σ · luma ${"%.1f".format(luma)} · diff ${"%.1f".format(diff)}"
                            store.event("VISUAL_ANOMALY", mapOf("z" to z,"luma" to luma,"diff" to diff)); onEvent(msg); captureStill("visual-anomaly-${store.elapsedMs()}.jpg")
                        }
                    } catch (_: Throwable) {} finally { image.close() }
                }
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, imageCapture, videoCapture)
                started = true
                store.event("VISUAL_LAB_START")
            } catch (t: Throwable) { onEvent("Camera lab error: ${t.message}") }
        }, ContextCompat.getMainExecutor(context))
    }

    fun startRecording() {
        if(recording!=null)return
        val vc=videoCapture ?: return
        val file=File(store.ensure("VISUAL_ITC"), "visual-${store.elapsedMs()}.mp4")
        try {
            recording=vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build()).start(ContextCompat.getMainExecutor(context)){ ev ->
                when(ev){
                    is VideoRecordEvent.Start -> { store.event("VIDEO_START", mapOf("file" to file.name)); onEvent("Visual recording started") }
                    is VideoRecordEvent.Finalize -> { store.event("VIDEO_STOP", mapOf("file" to file.name,"error" to ev.error)); onEvent("Visual recording saved · ${file.name}"); recording=null }
                }
            }
        } catch(t:Throwable){ onEvent("Video error: ${t.message}") }
    }
    fun stopRecording(){ try{recording?.stop()}catch(_:Throwable){} }
    fun captureStill(name:String="snapshot-${store.elapsedMs()}.jpg"){
        val ic=imageCapture ?: return; val f=File(store.ensure("VISUAL_ITC"),name)
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(),executor,object:ImageCapture.OnImageSavedCallback{
            override fun onImageSaved(o:ImageCapture.OutputFileResults){ store.event("SNAPSHOT",mapOf("file" to f.name)) }
            override fun onError(e:ImageCaptureException){ store.event("SNAPSHOT_ERROR",mapOf("error" to e.message)) }
        })
    }
    fun stop(){ if(!started && recording==null)return; stopRecording(); provider?.unbindAll(); provider=null; started=false; store.event("VISUAL_LAB_STOP") }
}
