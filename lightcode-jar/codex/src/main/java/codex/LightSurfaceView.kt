package com.vhanma.lightcode

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
import kotlin.math.floor
import kotlin.math.min

enum class ScreenOutputMode {
    RASTER_DAC,
    FULL_FRAME
}

enum class OpticalColorMode {
    WHITE,
    RED,
    GREEN,
    BLUE,
    AMBER,
    CYAN,
    MAGENTA
}

internal class LightSurfaceView(
    context: Context,
    private val program: OpticalProgram,
    private val outputMode: ScreenOutputMode,
    private val gain: Float,
    private val reverseRows: Boolean,
    private val colorMode: OpticalColorMode,
    private val requestedRows: Int,
    private val onFinished: () -> Unit
) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var running = false
    private var startNanos = 0L
    private var lastTapMillis = 0L
    private var rows = 256
    private var currentRows = FloatArray(rows) { 0.55f }
    private var nextRows = FloatArray(rows) { 0.55f }
    private var waveform = FloatArray(rows)

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        requestFastestRefresh(holder.surface)
        running = true
        startNanos = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        rows = if (requestedRows > 0) requestedRows.coerceIn(64, 768)
        else min(512, maxOf(128, height / 5))
        currentRows = FloatArray(rows) { 0.55f }
        nextRows = FloatArray(rows) { 0.55f }
        waveform = FloatArray(rows)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stop()
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { holder.surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || !holder.surface.isValid) return

        val refresh = display?.refreshRate?.toDouble()?.coerceAtLeast(30.0) ?: 60.0
        val elapsed = (frameTimeNanos - startNanos).coerceAtLeast(0L) / 1_000_000_000.0
        val duration = program.durationSeconds

        if (!program.loop && elapsed >= duration) {
            stop()
            onFinished()
            return
        }

        val canvas = runCatching { holder.lockCanvas() }.getOrNull()
        if (canvas != null) {
            try {
                when (outputMode) {
                    ScreenOutputMode.RASTER_DAC -> drawRaster(canvas, elapsed, refresh)
                    ScreenOutputMode.FULL_FRAME -> drawFullFrame(canvas, elapsed, refresh)
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
        if (running) Choreographer.getInstance().postFrameCallback(this)
    }

    private fun drawFullFrame(canvas: Canvas, elapsed: Double, refresh: Double) {
        val frameDuration = 1.0 / refresh
        val sampleCount = 12
        var sum = 0.0
        repeat(sampleCount) { i ->
            val t = elapsed + frameDuration * i.toDouble() / sampleCount.toDouble()
            sum += sampleAt(t)
        }
        val value = (sum / sampleCount.toDouble()).toFloat()
        val brightness = (0.5f + 0.49f * value * gain).coerceIn(0f, 1f)
        canvas.drawColor(opticalColor(brightness))
    }

    private fun drawRaster(canvas: Canvas, elapsed: Double, refresh: Double) {
        val effectiveRate = refresh * rows.toDouble()
        var previous = sampleAt(elapsed - 1.0 / effectiveRate)
        var maxDifference = 1e-5f

        for (i in 0 until rows) {
            val sample = sampleAt(elapsed + i.toDouble() / effectiveRate)
            waveform[i] = sample
            maxDifference = maxOf(maxDifference, abs(sample - previous))
            previous = sample
        }

        val desiredGain = 0.46f * gain.coerceIn(0.02f, 1.5f)
        val representableGain = 0.86f / (rows.toFloat() * maxDifference)
        val frameGain = min(desiredGain, representableGain)

        previous = sampleAt(elapsed - 1.0 / effectiveRate)
        for (i in 0 until rows) {
            val delta = rows.toFloat() * frameGain * (waveform[i] - previous)
            nextRows[i] = (currentRows[i] + delta).coerceIn(0.02f, 0.99f)
            previous = waveform[i]
        }

        canvas.drawColor(Color.BLACK)
        val bandHeight = canvas.height.toFloat() / rows.toFloat()
        for (logicalRow in 0 until rows) {
            val drawRow = if (reverseRows) rows - 1 - logicalRow else logicalRow
            val top = drawRow.toFloat() * bandHeight
            paint.color = opticalColor(nextRows[logicalRow])
            canvas.drawRect(0f, top, canvas.width.toFloat(), top + bandHeight + 1f, paint)
        }

        val swap = currentRows
        currentRows = nextRows
        nextRows = swap
    }

    private fun sampleAt(timeSeconds: Double): Float {
        if (program.samples.isEmpty() || program.sampleRate <= 0) return 0f
        val duration = program.durationSeconds
        var t = timeSeconds
        if (program.loop && duration > 0.0) {
            t %= duration
            if (t < 0.0) t += duration
        }
        if (t <= 0.0) return program.samples.first()
        if (t >= duration) return 0f

        val position = t * program.sampleRate.toDouble()
        val index = floor(position).toInt().coerceIn(0, program.samples.lastIndex)
        val next = (index + 1).coerceAtMost(program.samples.lastIndex)
        val fraction = (position - index.toDouble()).toFloat()
        return program.samples[index] * (1f - fraction) + program.samples[next] * fraction
    }

    private fun opticalColor(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        fun channel(scale: Float): Int = (255f * v * scale).toInt().coerceIn(0, 255)
        return when (colorMode) {
            OpticalColorMode.WHITE -> Color.rgb(channel(1f), channel(1f), channel(1f))
            OpticalColorMode.RED -> Color.rgb(channel(1f), 0, 0)
            OpticalColorMode.GREEN -> Color.rgb(0, channel(1f), 0)
            OpticalColorMode.BLUE -> Color.rgb(0, 0, channel(1f))
            OpticalColorMode.AMBER -> Color.rgb(channel(1f), channel(0.55f), 0)
            OpticalColorMode.CYAN -> Color.rgb(0, channel(1f), channel(1f))
            OpticalColorMode.MAGENTA -> Color.rgb(channel(1f), 0, channel(1f))
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
