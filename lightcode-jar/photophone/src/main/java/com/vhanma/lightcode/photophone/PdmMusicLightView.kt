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
import kotlin.math.min

/**
 * Binary spatial pulse-density modulation.
 *
 * Each physical display row is either fully on or fully off. The next row state is chosen so
 * the aggregate illuminated area follows the desired PCM waveform as the panel scans. This
 * avoids slow grayscale transitions and vendor-specific grayscale PWM as much as a phone screen
 * permits. It remains experimental because display scanout behavior is device-specific.
 */
internal class PdmMusicLightView(
    context: Context,
    private val program: OpticalProgram,
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
    private var frameCounter = 0
    private var rows = 384
    private var currentBits = BooleanArray(rows)
    private var nextBits = BooleanArray(rows)
    private var totalOn = 0
    private var sigmaError = 0.0

    init {
        holder.addCallback(this)
        keepScreenOn = true
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        requestFastestRefresh(holder.surface)
        running = true
        startNanos = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        rows = if (requestedRows > 0) requestedRows.coerceIn(96, 768)
        else min(640, maxOf(256, height / 4))
        currentBits = BooleanArray(rows) { index -> index % 2 == 0 }
        nextBits = currentBits.copyOf()
        totalOn = currentBits.count { it }
        sigmaError = 0.0
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

        val refresh = display?.refreshRate?.toDouble()?.coerceAtLeast(30.0) ?: 60.0
        buildNextFrame(elapsed, refresh)
        val canvas = runCatching { holder.lockCanvas() }.getOrNull()
        if (canvas != null) {
            try {
                drawFrame(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        val swap = currentBits
        currentBits = nextBits
        nextBits = swap
        frameCounter++
        if (frameCounter % 12 == 0) onProgress(elapsed)
        if (running) Choreographer.getInstance().postFrameCallback(this)
    }

    private fun buildNextFrame(elapsed: Double, refresh: Double) {
        val rowRate = refresh * rows.toDouble()
        var simulatedOn = totalOn
        val gain = modulationGain.coerceIn(0.05f, 2.0f)

        for (scanIndex in 0 until rows) {
            val logicalRow = if (reverseRows) rows - 1 - scanIndex else scanIndex
            if (currentBits[logicalRow]) simulatedOn--

            val sampleTime = elapsed + scanIndex.toDouble() / rowRate
            val sample = SignalCore.sampleAt(program, sampleTime)
            val targetFraction = (0.50 + 0.485 * sample.toDouble() * gain.toDouble())
                .coerceIn(0.015, 0.985)
            val targetOn = targetFraction * rows.toDouble()

            // First-order error diffusion in the spatial/temporal scan domain.
            val decisionValue = targetOn - simulatedOn.toDouble() + sigmaError
            val next = decisionValue >= 0.5
            if (next) simulatedOn++
            sigmaError = (decisionValue - if (next) 1.0 else 0.0).coerceIn(-2.0, 2.0)
            nextBits[logicalRow] = next
        }
        totalOn = simulatedOn.coerceIn(0, rows)
    }

    private fun drawFrame(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val bandHeight = canvas.height.toFloat() / rows.toFloat()
        paint.color = opticalColor()
        for (row in 0 until rows) {
            if (!nextBits[row]) continue
            val top = row.toFloat() * bandHeight
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

    private fun opticalColor(): Int = when (colorMode) {
        LightColorMode.WHITE -> Color.WHITE
        LightColorMode.RED -> Color.rgb(255, 0, 0)
        LightColorMode.GREEN -> Color.rgb(0, 255, 0)
        LightColorMode.BLUE -> Color.rgb(0, 0, 255)
        LightColorMode.AMBER -> Color.rgb(255, 140, 0)
        LightColorMode.CYAN -> Color.rgb(0, 255, 255)
        LightColorMode.MAGENTA -> Color.rgb(255, 0, 255)
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
