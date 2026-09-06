package com.vaan.contactomega

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

class SpiritBoxEngine(private val store: SessionStore, private val onState: (String) -> Unit) {
    enum class Mode { WHITE_NOISE, STATICOM, PHONEME_SWEEP, ECHO_BED }
    private val active = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile var sourceActive = false
        private set
    var seed: Long = 0L
        private set

    fun start(mode: Mode, dwellMs: Int, reverse: Boolean, gain: Float, cleanGapMs: Int = defaultGap(mode)) {
        stop()
        active.set(true); sourceActive = true
        seed = System.nanoTime()
        val gap = cleanGapMs.coerceIn(300, 10_000)
        store.event("SPIRIT_SOURCE_START", mapOf("mode" to mode.name, "dwellMs" to dwellMs, "reverse" to reverse, "seed" to seed, "cleanGapMs" to gap))
        thread = Thread { run(mode, dwellMs.coerceIn(30, 350), reverse, gain.coerceIn(0.05f, 1f), gap) }.also { it.start() }
    }

    fun stop() {
        if (!active.get() && !sourceActive) return
        active.set(false); sourceActive = false
        try { thread?.join(500) } catch (_: Throwable) {}
        thread = null
        store.event("SPIRIT_SOURCE_STOP")
    }

    private fun run(mode: Mode, dwell: Int, reverse: Boolean, gain: Float, cleanGapMs: Int) {
        val sr = 48000
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(min * 4).build()
        val rnd = Random(seed)
        var phase = 0.0
        var step = if (reverse) 31 else 0
        var lastSegment = 0L
        var segmentsSinceGap = 0
        val burstSegments = when (mode) {
            Mode.STATICOM, Mode.WHITE_NOISE -> 12
            else -> 18
        }
        try {
            track.play(); onState("${mode.name.replace('_',' ')} running · seed $seed · clean gaps armed")
            while (active.get()) {
                sourceActive = true
                val n = (sr * dwell / 1000).coerceAtLeast(256)
                val b = ShortArray(n)
                when (mode) {
                    Mode.WHITE_NOISE -> for (i in b.indices) b[i] = ((rnd.nextInt(65536)-32768) * gain * 0.42f).toInt().toShort()
                    Mode.STATICOM -> {
                        var prev = 0.0
                        for (i in b.indices) {
                            val white = (rnd.nextDouble()*2-1)
                            prev = 0.82*prev + 0.18*white
                            val carrier = sin(phase); phase += 2*PI*(900.0 + (step%8)*97)/sr
                            b[i] = ((0.58*white + 0.25*prev + 0.17*carrier) * 15000 * gain).toInt().coerceIn(-32767,32767).toShort()
                        }
                    }
                    Mode.PHONEME_SWEEP, Mode.ECHO_BED -> {
                        val vowel = step % 8
                        val f1 = intArrayOf(270,390,530,660,730,570,440,300)[vowel]
                        val f2 = intArrayOf(2290,1990,1840,1720,1090,840,1020,870)[vowel]
                        val f3 = intArrayOf(3010,2550,2480,2410,2440,2410,2240,2240)[vowel]
                        val chirp = 70 + (step*37 % 180)
                        for (i in b.indices) {
                            val t = i.toDouble()/sr
                            val env = sin(PI * i / b.size).coerceAtLeast(0.0)
                            val hiss = (rnd.nextDouble()*2-1) * if (step%3==0) 0.30 else 0.10
                            val x = 0.46*sin(2*PI*f1*t) + 0.30*sin(2*PI*f2*t) + 0.17*sin(2*PI*f3*t) + 0.12*sin(2*PI*chirp*t) + hiss
                            b[i] = (x * env * 10500 * gain).toInt().coerceIn(-32767,32767).toShort()
                        }
                        if (reverse && step % 2 == 0) b.reverse()
                    }
                }
                track.write(b, 0, b.size, AudioTrack.WRITE_BLOCKING)
                if (System.currentTimeMillis() - lastSegment > 1000) {
                    lastSegment = System.currentTimeMillis()
                    store.event("SPIRIT_SOURCE_SEGMENT", mapOf("step" to step, "mode" to mode.name, "seed" to seed))
                }
                step = if (reverse) (step - 1 + 32) % 32 else (step + 1) % 32
                segmentsSinceGap++

                if (segmentsSinceGap >= burstSegments && active.get()) {
                    segmentsSinceGap = 0
                    sourceActive = false
                    store.event("SPIRIT_CLEAN_GAP_START", mapOf("durationMs" to cleanGapMs, "mode" to mode.name))
                    onState("CLEAN REPLY WINDOW · ${cleanGapMs} ms")
                    val silence = ShortArray((sr * cleanGapMs / 1000).coerceAtLeast(1))
                    track.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                    store.event("SPIRIT_CLEAN_GAP_END", mapOf("mode" to mode.name))
                    sourceActive = true
                    onState("${mode.name.replace('_',' ')} running · clean gap complete")
                }
            }
        } catch (t: Throwable) { onState("Spirit source stopped: ${t.message ?: "audio error"}") }
        finally { sourceActive = false; try { track.stop() } catch (_: Throwable) {}; track.release() }
    }

    companion object {
        private fun defaultGap(mode: Mode): Int = when (mode) {
            Mode.STATICOM, Mode.WHITE_NOISE -> 3000
            Mode.PHONEME_SWEEP, Mode.ECHO_BED -> 900
        }
    }
}
