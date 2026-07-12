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
import kotlin.math.abs
import kotlin.math.min

internal enum class ScreenPhotophoneMode {
    SCANLINE_PCM,
    WHOLE_FRAME_FALLBACK
}

internal enum class LightColorMode {
    WHITE,
    RED,
    GREEN,
    BLUE,
    AMBER,
    CYAN,
    MAGENTA
}

internal enum class BeamGeometry {
    FULL_APERTURE,
    HOLLOW_BEAM,
    CENTRAL_SHAFT,
    TWIN_BEAM
}

internal class MusicLightView(
    context: Context,
    private val program: OpticalProgram,
    private val mode: ScreenPhotophoneMode,
    private val modulationGain: Float,
    private val reverseRows: Boolean,
    private val colorMode: LightColorMode,
    private val requestedRows: Int,
    private val geometry: BeamGeometry,
    private val onProgress: (Double) -> Unit,
    private val onFinished: () -> Unit
) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var running = false
    private var startNanos = 0L
    private var lastTapMillis = 0L
    private var rows = 384
    private var currentRows = FloatArray(rows) { 0.52f }
    private var nextRows = FloatArray(rows) { 0.52f }
    private var waveform = FloatArray(rows)
    private var frameCounter = 0

    init {
        holder.addCallback(this)
        keepScreenOn = true
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        requestFastestRefresh(holder.surface)
        startNanos = System.nanoTime()
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        rows = if (requestedRows > 0) requestedRows.coerceIn(64, 768)
        else min(640, maxOf(192, height / 4))
        currentRows = FloatArray(rows) { 0.52f }
        nextRows = FloatArray(rows) { 0.52f }
        waveform = FloatArray(rows)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stop()
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                holder.surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || !holder.surface.isValid) return

        val elapsed = (frameTimeNanos - startNanos).coerceAtLeast(0L) / 1_000_000_000.0
        if (!program.loop && elapsed >= program.durationSeconds) {
            stop()
            onFinished()
            return
        }

        val refreshRate = display?.refreshRate?.toDouble()?.coerceAtLeast(30.0) ?: 60.0
        val canvas = runCatching { holder.lockCanvas() }.getOrNull()
        if (canvas != null) {
            try {
                when (mode) {
                    ScreenPhotophoneMode.SCANLINE_PCM -> drawScanlinePcm(canvas, elapsed, refreshRate)
                    ScreenPhotophoneMode.WHOLE_FRAME_FALLBACK -> drawWholeFrame(canvas, elapsed, refreshRate)
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        frameCounter++
        if (frameCounter % 12 == 0) onProgress(elapsed)
        if (running) Choreographer.getInstance().postFrameCallback(this)
    }

    private fun drawWholeFrame(canvas: Canvas, elapsed: Double, refreshRate: Double) {
        val frameDuration = 1.0 / refreshRate
        var average = 0.0
        val taps = 16
        repeat(taps) { tap ->
            val time = elapsed + frameDuration * tap.toDouble() / taps.toDouble()
            average += SignalCore.sampleAt(program, time)
        }
        val value = (average / taps.toDouble()).toFloat()
        val brightness = (0.50f + 0.49f * value * modulationGain).coerceIn(0.01f, 1f)
        canvas.drawColor(Color.BLACK)
        drawGeometry(canvas, brightness, 0f, canvas.height.toFloat())
    }

    private fun drawScanlinePcm(canvas: Canvas, elapsed: Double, refreshRate: Double) {
        val effectiveRowRate = refreshRate * rows.toDouble()
        var previous = SignalCore.sampleAt(program, elapsed - 1.0 / effectiveRowRate)
        var largestStep = 1e-6f

        for (row in 0 until rows) {
            val sampleTime = elapsed + row.toDouble() / effectiveRowRate
            val sample = SignalCore.sampleAt(program, sampleTime)
            waveform[row] = sample
            largestStep = maxOf(largestStep, abs(sample - previous))
            previous = sample
        }

        val requestedGain = 0.47f * modulationGain.coerceIn(0.02f, 1.8f)
        val safeGain = 0.90f / (rows.toFloat() * largestStep)
        val frameGain = min(requestedGain, safeGain)

        previous = SignalCore.sampleAt(program, elapsed - 1.0 / effectiveRowRate)
        for (row in 0 until rows) {
            val delta = rows.toFloat() * frameGain * (waveform[row] - previous)
            nextRows[row] = (currentRows[row] + delta).coerceIn(0.01f, 0.995f)
            previous = waveform[row]
        }

        canvas.drawColor(Color.BLACK)
        val bandHeight = canvas.height.toFloat() / rows.toFloat()
        for (logicalRow in 0 until rows) {
            val displayRow = if (reverseRows) rows - 1 - logicalRow else logicalRow
            val top = displayRow.toFloat() * bandHeight
            drawGeometry(canvas, nextRows[logicalRow], top, top + bandHeight + 1f)
        }

        val swap = currentRows
        currentRows = nextRows
        nextRows = swap
    }

    private fun drawGeometry(canvas: Canvas, brightness: Float, top: Float, bottom: Float) {
        paint.color = opticalColor(brightness)
        val width = canvas.width.toFloat()
        when (geometry) {
            BeamGeometry.FULL_APERTURE -> {
                canvas.drawRect(0f, top, width, bottom, paint)
            }
            BeamGeometry.HOLLOW_BEAM -> {
                canvas.drawRect(0f, top, width * 0.34f, bottom, paint)
                canvas.drawRect(width * 0.66f, top, width, bottom, paint)
            }
            BeamGeometry.CENTRAL_SHAFT -> {
                canvas.drawRect(width * 0.24f, top, width * 0.76f, bottom, paint)
            }
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
