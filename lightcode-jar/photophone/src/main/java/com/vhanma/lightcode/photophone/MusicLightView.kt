package com.vhanma.lightcode.photophone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.PowerManager
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

internal data class EfficiencySnapshot(
    val refreshRateHz: Double,
    val configuredRows: Int,
    val activeRows: Int,
    val effectiveRowRateHz: Double,
    val thermalHeadroom: Float,
    val renderMicros: Long,
    val droppedFrames: Long,
    val frameNumber: Long
)

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
    private val onEfficiency: (EfficiencySnapshot) -> Unit = {},
    private val onFinished: () -> Unit
) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isFilterBitmap = false
        isDither = false
    }
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val sourceRect = Rect()
    private val destinationRect = RectF()
    private var running = false
    private var startNanos = 0L
    private var lastFrameNanos = 0L
    private var lastTapMillis = 0L
    private var rows = 384
    private var activeRows = rows
    private var currentRows = FloatArray(rows) { 0.52f }
    private var nextRows = FloatArray(rows) { 0.52f }
    private var waveform = FloatArray(rows)
    private var pixels = IntArray(rows)
    private var rasterBitmap: Bitmap? = null
    private var frameCounter = 0L
    private var droppedFrames = 0L
    private var thermalHeadroom = Float.NaN
    private var lastThermalCheckNanos = 0L
    private var lastRenderMicros = 0L

    init {
        holder.addCallback(this)
        keepScreenOn = true
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        requestFastestRefresh(holder.surface)
        startNanos = System.nanoTime()
        lastFrameNanos = startNanos
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        rows = if (requestedRows > 0) requestedRows.coerceIn(64, 768)
        else min(640, maxOf(192, height / 4))
        activeRows = rows
        currentRows = FloatArray(rows) { 0.52f }
        nextRows = FloatArray(rows) { 0.52f }
        waveform = FloatArray(rows)
        pixels = IntArray(rows)
        rasterBitmap?.recycle()
        rasterBitmap = Bitmap.createBitmap(1, rows, Bitmap.Config.ARGB_8888)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stop()
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        rasterBitmap?.recycle()
        rasterBitmap = null
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                holder.surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || !holder.surface.isValid) return

        val refreshRate = display?.refreshRate?.toDouble()?.coerceAtLeast(30.0) ?: 60.0
        val expectedFrameNanos = (1_000_000_000.0 / refreshRate).toLong()
        if (lastFrameNanos > 0L && frameTimeNanos - lastFrameNanos > expectedFrameNanos * 3L / 2L) {
            droppedFrames += ((frameTimeNanos - lastFrameNanos) / expectedFrameNanos - 1L).coerceAtLeast(1L)
        }
        lastFrameNanos = frameTimeNanos
        updateThermalBudget(frameTimeNanos)

        val elapsed = (frameTimeNanos - startNanos).coerceAtLeast(0L) / 1_000_000_000.0
        if (!program.loop && elapsed >= program.durationSeconds) {
            stop()
            onFinished()
            return
        }

        val renderStarted = System.nanoTime()
        val canvas = runCatching {
            if (Build.VERSION.SDK_INT >= 26) holder.lockHardwareCanvas() else holder.lockCanvas()
        }.getOrElse { runCatching { holder.lockCanvas() }.getOrNull() }
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
        lastRenderMicros = (System.nanoTime() - renderStarted) / 1_000L

        frameCounter++
        if (frameCounter % 12L == 0L) onProgress(elapsed)
        if (frameCounter % 15L == 0L) {
            onEfficiency(
                EfficiencySnapshot(
                    refreshRateHz = refreshRate,
                    configuredRows = rows,
                    activeRows = activeRows,
                    effectiveRowRateHz = refreshRate * activeRows.toDouble(),
                    thermalHeadroom = thermalHeadroom,
                    renderMicros = lastRenderMicros,
                    droppedFrames = droppedFrames,
                    frameNumber = frameCounter
                )
            )
        }
        if (running) Choreographer.getInstance().postFrameCallback(this)
    }

    private fun updateThermalBudget(frameTimeNanos: Long) {
        if (frameTimeNanos - lastThermalCheckNanos < 1_000_000_000L) return
        lastThermalCheckNanos = frameTimeNanos
        thermalHeadroom = if (Build.VERSION.SDK_INT >= 30) {
            runCatching { powerManager.getThermalHeadroom(0) }.getOrDefault(Float.NaN)
        } else Float.NaN

        activeRows = when {
            thermalHeadroom.isNaN() -> rows
            thermalHeadroom >= 0.92f -> maxOf(128, rows / 2)
            thermalHeadroom >= 0.78f -> maxOf(192, rows * 3 / 4)
            else -> rows
        }.coerceAtMost(rows)
    }

    private fun drawWholeFrame(canvas: Canvas, elapsed: Double, refreshRate: Double) {
        val frameDuration = 1.0 / refreshRate
        var average = 0.0
        val taps = 12
        repeat(taps) { tap ->
            average += SignalCore.sampleAt(
                program,
                elapsed + frameDuration * tap.toDouble() / taps.toDouble()
            )
        }
        val value = (average / taps.toDouble()).toFloat()
        val brightness = (0.50f + 0.49f * value * modulationGain).coerceIn(0.01f, 1f)
        canvas.drawColor(Color.BLACK)
        paint.color = opticalColor(brightness)
        drawSolidGeometry(canvas)
    }

    private fun drawScanlinePcm(canvas: Canvas, elapsed: Double, refreshRate: Double) {
        val usedRows = activeRows.coerceAtLeast(1)
        val effectiveRowRate = refreshRate * usedRows.toDouble()
        var previous = SignalCore.sampleAt(program, elapsed - 1.0 / effectiveRowRate)
        var largestStep = 1e-6f

        for (row in 0 until usedRows) {
            val sample = SignalCore.sampleAt(program, elapsed + row.toDouble() / effectiveRowRate)
            waveform[row] = sample
            largestStep = maxOf(largestStep, abs(sample - previous))
            previous = sample
        }

        val requestedGain = 0.47f * modulationGain.coerceIn(0.02f, 1.8f)
        val safeGain = 0.90f / (usedRows.toFloat() * largestStep)
        val frameGain = min(requestedGain, safeGain)

        previous = SignalCore.sampleAt(program, elapsed - 1.0 / effectiveRowRate)
        for (logicalRow in 0 until usedRows) {
            val delta = usedRows.toFloat() * frameGain * (waveform[logicalRow] - previous)
            nextRows[logicalRow] = (currentRows[logicalRow] + delta).coerceIn(0.01f, 0.995f)
            previous = waveform[logicalRow]
            val displayRow = if (reverseRows) usedRows - 1 - logicalRow else logicalRow
            pixels[displayRow] = opticalColor(nextRows[logicalRow])
        }

        val bitmap = rasterBitmap ?: return
        bitmap.setPixels(pixels, 0, 1, 0, 0, 1, usedRows)
        sourceRect.set(0, 0, 1, usedRows)
        canvas.drawColor(Color.BLACK)
        drawBitmapGeometry(canvas, bitmap)

        val swap = currentRows
        currentRows = nextRows
        nextRows = swap
    }

    private fun drawBitmapGeometry(canvas: Canvas, bitmap: Bitmap) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        when (geometry) {
            BeamGeometry.FULL_APERTURE -> {
                destinationRect.set(0f, 0f, width, height)
                canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
            }
            BeamGeometry.HOLLOW_BEAM -> {
                destinationRect.set(0f, 0f, width * 0.34f, height)
                canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
                destinationRect.set(width * 0.66f, 0f, width, height)
                canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
            }
            BeamGeometry.CENTRAL_SHAFT -> {
                destinationRect.set(width * 0.24f, 0f, width * 0.76f, height)
                canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
            }
            BeamGeometry.TWIN_BEAM -> {
                destinationRect.set(width * 0.08f, 0f, width * 0.38f, height)
                canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
                destinationRect.set(width * 0.62f, 0f, width * 0.92f, height)
                canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
            }
        }
    }

    private fun drawSolidGeometry(canvas: Canvas) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        when (geometry) {
            BeamGeometry.FULL_APERTURE -> canvas.drawRect(0f, 0f, width, height, paint)
            BeamGeometry.HOLLOW_BEAM -> {
                canvas.drawRect(0f, 0f, width * 0.34f, height, paint)
                canvas.drawRect(width * 0.66f, 0f, width, height, paint)
            }
            BeamGeometry.CENTRAL_SHAFT -> canvas.drawRect(width * 0.24f, 0f, width * 0.76f, height, paint)
            BeamGeometry.TWIN_BEAM -> {
                canvas.drawRect(width * 0.08f, 0f, width * 0.38f, height, paint)
                canvas.drawRect(width * 0.62f, 0f, width * 0.92f, height, paint)
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
