package com.vhanma.lightcode.photophone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.floor
import kotlin.math.min

internal class StreamingPayloadLightView(
    context: Context,
    private val encoder: UniversalWaveEncoder,
    private val wholeFrame: Boolean,
    private val modulationGain: Float,
    private val reverseRows: Boolean,
    private val colorMode: LightColorMode,
    private val requestedRows: Int,
    private val geometry: BeamGeometry,
    private val binaryRows: Boolean,
    private val onProgress: (Long, Long) -> Unit,
    private val onFinished: () -> Unit
) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var running = false
    private var rows = 384
    private var frameCounter = 0L
    private var sampleCarry = 0.0
    private var frameSamples = FloatArray(512)
    private var lastTapMillis = 0L

    init {
        holder.addCallback(this)
        keepScreenOn = true
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        requestFastestRefresh(holder.surface)
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        rows = if (requestedRows > 0) requestedRows.coerceIn(96, 768)
        else min(640, maxOf(256, height / 4))
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stop()
    }

    fun stop() {
        if (!running) return
        running = false
        encoder.close()
        Choreographer.getInstance().removeFrameCallback(this)
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { holder.surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || !holder.surface.isValid) return
        val refresh = display?.refreshRate?.toDouble()?.coerceAtLeast(30.0) ?: 60.0
        sampleCarry += encoder.sampleRate.toDouble() / refresh
        val requested = floor(sampleCarry).toInt().coerceAtLeast(1)
        sampleCarry -= requested.toDouble()
        ensureSampleCapacity(requested)

        var count = 0
        while (count < requested) {
            val sample = encoder.nextSample()
            if (sample == null) break
            frameSamples[count++] = sample
        }
        if (count == 0) {
            stop()
            onFinished()
            return
        }

        val canvas = runCatching { holder.lockCanvas() }.getOrNull()
        if (canvas != null) {
            try {
                if (wholeFrame) drawWholeFrame(canvas, count)
                else drawScanlineFrame(canvas, count)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        frameCounter++
        if (frameCounter % 12L == 0L) {
            onProgress(encoder.payloadBytesRead, encoder.passCount)
        }
        if (running) Choreographer.getInstance().postFrameCallback(this)
    }

    private fun drawWholeFrame(canvas: Canvas, count: Int) {
        var sum = 0.0
        for (index in 0 until count) sum += frameSamples[index]
        val sample = (sum / count.toDouble()).toFloat()
        val brightness = (0.5f + 0.49f * sample * modulationGain).coerceIn(0.01f, 1f)
        canvas.drawColor(Color.BLACK)
        paint.color = opticalColor(brightness)
        drawGeometry(canvas, 0f, canvas.height.toFloat())
    }

    private fun drawScanlineFrame(canvas: Canvas, count: Int) {
        canvas.drawColor(Color.BLACK)
        val bandHeight = canvas.height.toFloat() / rows.toFloat()
        for (logicalRow in 0 until rows) {
            val sampleIndex = ((logicalRow.toLong() * count.toLong()) / rows.toLong())
                .toInt()
                .coerceIn(0, count - 1)
            val raw = frameSamples[sampleIndex]
            val brightness = if (binaryRows) {
                if (raw >= 0f) 1f else 0f
            } else {
                (0.5f + 0.49f * raw * modulationGain).coerceIn(0.01f, 1f)
            }
            val displayRow = if (reverseRows) rows - 1 - logicalRow else logicalRow
            val top = displayRow.toFloat() * bandHeight
            paint.color = opticalColor(brightness)
            drawGeometry(canvas, top, top + bandHeight + 1f)
        }
    }

    private fun drawGeometry(canvas: Canvas, top: Float, bottom: Float) {
        val width = canvas.width.toFloat()
        when (geometry) {
            BeamGeometry.FULL_APERTURE -> canvas.drawRect(0f, top, width, bottom, paint)
            BeamGeometry.HOLLOW_BEAM -> {
                canvas.drawRect(0f, top, width * 0.34f, bottom, paint)
                canvas.drawRect(width * 0.66f, top, width, bottom, paint)
            }
            BeamGeometry.CENTRAL_SHAFT -> canvas.drawRect(width * 0.24f, top, width * 0.76f, bottom, paint)
            BeamGeometry.TWIN_BEAM -> {
                canvas.drawRect(width * 0.08f, top, width * 0.38f, bottom, paint)
                canvas.drawRect(width * 0.62f, top, width * 0.92f, bottom, paint)
            }
        }
    }

    private fun opticalColor(brightness: Float): Int {
        val value = brightness.coerceIn(0f, 1f)
        fun channel(multiplier: Float): Int =
            (255f * value * multiplier).toInt().coerceIn(0, 255)
        return when (colorMode) {
            LightColorMode.WHITE -> Color.rgb(channel(1f), channel(1f), channel(1f))
            LightColorMode.RED -> Color.rgb(channel(1f), 0, 0)
            LightColorMode.GREEN -> Color.rgb(0, channel(1f), 0)
            LightColorMode.BLUE -> Color.rgb(0, 0, channel(1f))
            LightColorMode.AMBER -> Color.rgb(channel(1f), channel(0.55f), 0)
            LightColorMode.CYAN -> Color.rgb(0, channel(1f), channel(1f))
            LightColorMode.MAGENTA -> Color.rgb(channel(1f), 0, channel(1f))
        }
    }

    private fun ensureSampleCapacity(required: Int) {
        if (required <= frameSamples.size) return
        frameSamples = FloatArray(required * 2)
    }

    private fun requestFastestRefresh(surface: Surface) {
        val fastest = display?.supportedModes?.maxByOrNull { it.refreshRate }?.refreshRate
            ?: display?.refreshRate
            ?: 60f
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(
                    fastest,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
            } else if (Build.VERSION.SDK_INT >= 30) {
                surface.setFrameRate(fastest, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastTapMillis < 350L) {
                stop()
                onFinished()
            }
            lastTapMillis = now
        }
        return true
    }
}
