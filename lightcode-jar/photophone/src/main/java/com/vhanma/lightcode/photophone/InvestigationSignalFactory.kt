package com.vhanma.lightcode.photophone

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

internal enum class EvidenceLayer {
    DECLASSIFIED,
    ESTABLISHED_ENGINEERING,
    FRONTIER,
    REPORTED_FRINGE
}

internal data class InvestigationProtocol(
    val name: String,
    val evidenceLayer: EvidenceLayer,
    val description: String,
    val program: OpticalProgram?,
    val eventSchedule: TrialSchedule? = null,
    val instrumentOnly: Boolean = false,
    val maximumUsefulOutputHz: Int = 12_000
)

internal data class TrialEvent(
    val index: Int,
    val condition: String,
    val cueSeconds: Double,
    val stimulusSeconds: Double,
    val restSeconds: Double
)

internal data class TrialSchedule(
    val name: String,
    val seed: Long,
    val events: List<TrialEvent>
) {
    fun toCsv(): String = buildString {
        append("protocol,seed,trial,condition,cue_seconds,stimulus_seconds,rest_seconds\n")
        events.forEach { event ->
            append(name).append(',')
                .append(seed).append(',')
                .append(event.index).append(',')
                .append(event.condition).append(',')
                .append(event.cueSeconds).append(',')
                .append(event.stimulusSeconds).append(',')
                .append(event.restSeconds).append('\n')
        }
    }
}

internal object InvestigationSignalFactory {
    private const val SAMPLE_RATE = 24_000
    private const val MAX_PACKET_BYTES = 262_144
    private val barker13 = intArrayOf(1, 1, 1, 1, 1, -1, -1, 1, 1, -1, 1, -1, 1)

    fun protocolNames(): List<String> = listOf(
        "PRBS-127 correlation beacon",
        "Barker-13 acquisition bursts",
        "Gold-code dual-LFSR beacon",
        "Logarithmic chirp spread",
        "Manchester text packet",
        "Manchester exact-file packet",
        "16-position PPM text",
        "Multi-carrier phase lattice",
        "Kirlian-inspired pulsed carrier",
        "SRI remote-strobe trial schedule"
    )

    fun create(
        index: Int,
        text: String,
        fileBytes: ByteArray?,
        fileName: String?,
        loop: Boolean,
        seed: Long
    ): InvestigationProtocol = when (index) {
        0 -> prbs127(loop)
        1 -> barkerBeacon(loop)
        2 -> goldCode(loop)
        3 -> chirpSpread(loop)
        4 -> manchesterPacket(
            text.toByteArray(Charsets.UTF_8),
            "message.txt",
            loop,
            "Manchester text packet"
        )
        5 -> manchesterPacket(
            fileBytes ?: error("Choose a file first."),
            fileName ?: "payload.bin",
            loop,
            "Manchester exact-file packet"
        )
        6 -> ppmText(text, loop)
        7 -> phaseLattice(loop)
        8 -> kirlianInspired(loop)
        else -> remoteStrobeSchedule(seed)
    }

    private fun prbs127(loop: Boolean): InvestigationProtocol {
        val chipRate = 600
        val samplesPerChip = SAMPLE_RATE / chipRate
        val sequence = mSequence7()
        val preamble = barker13.toList() + barker13.toList()
        val chips = preamble + sequence.map { if (it == 1) 1 else -1 }
        val output = FloatArray(chips.size * samplesPerChip)
        var phase = 0.0
        val carrier = 2_400.0
        var cursor = 0
        chips.forEach { chip ->
            repeat(samplesPerChip) {
                phase += 2.0 * PI * carrier / SAMPLE_RATE.toDouble()
                output[cursor++] = (0.94 * chip * sin(phase)).toFloat()
            }
        }
        return InvestigationProtocol(
            name = "PRBS-127 correlation beacon",
            evidenceLayer = EvidenceLayer.ESTABLISHED_ENGINEERING,
            description = "A maximal-length pseudorandom sequence with a Barker acquisition preamble. Designed for correlation detection through noisy optical or photoacoustic channels.",
            program = OpticalProgram(output, SAMPLE_RATE, "PRBS-127 correlation beacon", loop),
            maximumUsefulOutputHz = 3_000
        )
    }

    private fun barkerBeacon(loop: Boolean): InvestigationProtocol {
        val chipRate = 100
        val samplesPerChip = SAMPLE_RATE / chipRate
        val repetitions = 20
        val output = FloatArray(barker13.size * repetitions * samplesPerChip)
        var cursor = 0
        var phase = 0.0
        repeat(repetitions) {
            for (chip in barker13) {
                repeat(samplesPerChip) {
                    phase += 2.0 * PI * 1_200.0 / SAMPLE_RATE.toDouble()
                    output[cursor++] = (0.94 * chip * sin(phase)).toFloat()
                }
            }
        }
        return InvestigationProtocol(
            name = "Barker-13 acquisition bursts",
            evidenceLayer = EvidenceLayer.ESTABLISHED_ENGINEERING,
            description = "Repeated Barker-13 codes create a sharp correlation peak for timing recovery and weak-signal detection.",
            program = OpticalProgram(output, SAMPLE_RATE, "Barker-13 bursts", loop),
            maximumUsefulOutputHz = 1_600
        )
    }

    private fun goldCode(loop: Boolean): InvestigationProtocol {
        val codeA = lfsrSequence(intArrayOf(7, 3), 127, 0x7F)
        val codeB = lfsrSequence(intArrayOf(7, 6, 5, 2), 127, 0x5D)
        val gold = IntArray(127) { index -> codeA[index] xor codeB[(index + 19) % 127] }
        val chipRate = 800
        val samplesPerChip = SAMPLE_RATE / chipRate
        val output = FloatArray((barker13.size + gold.size) * samplesPerChip)
        var cursor = 0
        var phase = 0.0
        val chips = barker13.toList() + gold.map { if (it == 1) 1 else -1 }
        chips.forEach { chip ->
            repeat(samplesPerChip) {
                phase += 2.0 * PI * 3_200.0 / SAMPLE_RATE.toDouble()
                output[cursor++] = (0.90 * chip * sin(phase)).toFloat()
            }
        }
        return InvestigationProtocol(
            name = "Gold-code dual-LFSR beacon",
            evidenceLayer = EvidenceLayer.ESTABLISHED_ENGINEERING,
            description = "Two deterministic LFSR sequences combine into a Gold-like code for channel identification and multiple-transmitter separation.",
            program = OpticalProgram(output, SAMPLE_RATE, "Gold-code beacon", loop),
            maximumUsefulOutputHz = 4_000
        )
    }

    private fun chirpSpread(loop: Boolean): InvestigationProtocol {
        val startHz = 180.0
        val endHz = 8_000.0
        val chirpSeconds = 4.0
        val gapSeconds = 0.5
        val repetitions = 3
        val chirpSamples = (chirpSeconds * SAMPLE_RATE).toInt()
        val gapSamples = (gapSeconds * SAMPLE_RATE).toInt()
        val output = FloatArray(repetitions * (chirpSamples + gapSamples))
        var cursor = 0
        repeat(repetitions) {
            var phase = 0.0
            for (sampleIndex in 0 until chirpSamples) {
                val fraction = sampleIndex.toDouble() / chirpSamples.toDouble()
                val frequency = startHz * exp(ln(endHz / startHz) * fraction)
                phase += 2.0 * PI * frequency / SAMPLE_RATE.toDouble()
                val fade = when {
                    fraction < 0.02 -> fraction / 0.02
                    fraction > 0.98 -> (1.0 - fraction) / 0.02
                    else -> 1.0
                }
                output[cursor++] = (0.94 * fade * sin(phase)).toFloat()
            }
            repeat(gapSamples) { output[cursor++] = 0f }
        }
        return InvestigationProtocol(
            name = "Logarithmic chirp spread",
            evidenceLayer = EvidenceLayer.ESTABLISHED_ENGINEERING,
            description = "Repeated broadband chirps expose the transfer function of the light source, absorber, jar cavity and microphone.",
            program = OpticalProgram(output, SAMPLE_RATE, "Logarithmic chirp spread", loop),
            maximumUsefulOutputHz = 8_000
        )
    }

    private fun manchesterPacket(
        payload: ByteArray,
        fileName: String,
        loop: Boolean,
        protocolName: String
    ): InvestigationProtocol {
        require(payload.size <= MAX_PACKET_BYTES) { "The in-memory lab packet limit is 256 KB." }
        val frame = framedPayload(payload, fileName, "LCI1")
        val baud = 300
        val halfBitSamples = SAMPLE_RATE / (baud * 2)
        val carrierHz = 2_400.0
        val bitCount = frame.size * 8
        val output = FloatArray(bitCount * halfBitSamples * 2)
        var phase = 0.0
        var cursor = 0
        for (byteValue in frame) {
            val value = byteValue.toInt() and 0xFF
            for (bitIndex in 7 downTo 0) {
                val bit = (value ushr bitIndex) and 1
                val halves = if (bit == 1) intArrayOf(1, -1) else intArrayOf(-1, 1)
                for (half in halves) {
                    repeat(halfBitSamples) {
                        phase += 2.0 * PI * carrierHz / SAMPLE_RATE.toDouble()
                        output[cursor++] = (0.92 * half * sin(phase)).toFloat()
                    }
                }
            }
        }
        return InvestigationProtocol(
            name = protocolName,
            evidenceLayer = EvidenceLayer.ESTABLISHED_ENGINEERING,
            description = "Self-clocking Manchester packet with preamble, filename, byte count and CRC32. This is exact data represented as light-controlled acoustic waveform, not Morse.",
            program = OpticalProgram(output, SAMPLE_RATE, "$protocolName · $fileName", loop),
            maximumUsefulOutputHz = 3_000
        )
    }

    private fun ppmText(text: String, loop: Boolean): InvestigationProtocol {
        val payload = framedPayload(text.toByteArray(Charsets.UTF_8), "message.txt", "LCI2")
        val slots = 16
        val slotSamples = 24
        val symbolSamples = slots * slotSamples
        val output = FloatArray(payload.size * 2 * symbolSamples + SAMPLE_RATE)
        repeat(5) { burst ->
            val start = burst * SAMPLE_RATE / 6
            val length = SAMPLE_RATE / 30
            for (index in start until (start + length).coerceAtMost(output.size)) output[index] = 0.96f
        }
        var base = SAMPLE_RATE
        for (byteValue in payload) {
            val value = byteValue.toInt() and 0xFF
            val symbols = intArrayOf(value ushr 4, value and 0x0F)
            symbols.forEach { symbol ->
                val pulseStart = base + symbol * slotSamples
                val pulseLength = (slotSamples / 3).coerceAtLeast(2)
                repeat(pulseLength) { offset ->
                    val index = pulseStart + offset
                    if (index in output.indices) output[index] = 0.98f
                }
                base += symbolSamples
            }
        }
        return InvestigationProtocol(
            name = "16-position PPM text",
            evidenceLayer = EvidenceLayer.ESTABLISHED_ENGINEERING,
            description = "Information is carried by pulse position inside equal-duration windows, echoing ancient synchronized timing systems while using modern packet framing.",
            program = OpticalProgram(output, SAMPLE_RATE, "16-position PPM text", loop),
            maximumUsefulOutputHz = 1_500
        )
    }

    private fun phaseLattice(loop: Boolean): InvestigationProtocol {
        val frequencies = doubleArrayOf(610.0, 987.0, 1_597.0, 2_584.0, 4_181.0)
        val seconds = 12.0
        val output = FloatArray((seconds * SAMPLE_RATE).toInt())
        val phases = DoubleArray(frequencies.size)
        for (index in output.indices) {
            var value = 0.0
            frequencies.forEachIndexed { lane, frequency ->
                phases[lane] += 2.0 * PI * frequency / SAMPLE_RATE.toDouble()
                val phaseOffset = lane * PI / 5.0
                value += sin(phases[lane] + phaseOffset) / frequencies.size.toDouble()
            }
            output[index] = (value * 0.94).toFloat()
        }
        return InvestigationProtocol(
            name = "Multi-carrier phase lattice",
            evidenceLayer = EvidenceLayer.FRONTIER,
            description = "Five non-harmonic carriers with fixed phase offsets form a repeatable spectral fingerprint for channel and resonance experiments.",
            program = OpticalProgram(output, SAMPLE_RATE, "Multi-carrier phase lattice", loop),
            maximumUsefulOutputHz = 4_500
        )
    }

    private fun kirlianInspired(loop: Boolean): InvestigationProtocol {
        val carrierHz = 9_000.0
        val burstRate = 37.0
        val seconds = 12.0
        val output = FloatArray((seconds * SAMPLE_RATE).toInt())
        var carrierPhase = 0.0
        var burstPhase = 0.0
        for (index in output.indices) {
            carrierPhase += 2.0 * PI * carrierHz / SAMPLE_RATE.toDouble()
            burstPhase += 2.0 * PI * burstRate / SAMPLE_RATE.toDouble()
            val gate = if (sin(burstPhase) > 0.55) 1.0 else 0.0
            output[index] = (0.92 * gate * sin(carrierPhase)).toFloat()
        }
        return InvestigationProtocol(
            name = "Kirlian-inspired pulsed carrier",
            evidenceLayer = EvidenceLayer.REPORTED_FRINGE,
            description = "A light-domain timing analogue of the pulsed high-frequency fields described in declassified Kirlian-device reports. It does not reproduce a high-voltage corona field.",
            program = OpticalProgram(output, SAMPLE_RATE, "Kirlian-inspired pulsed carrier", loop),
            instrumentOnly = true,
            maximumUsefulOutputHz = 10_000
        )
    }

    private fun remoteStrobeSchedule(seed: Long): InvestigationProtocol {
        val conditions = MutableList(12) { "0 Hz / null" } +
            MutableList(12) { "6 Hz" } +
            MutableList(12) { "16 Hz" }
        val random = Random(seed)
        conditions.shuffle(random)
        val schedule = TrialSchedule(
            name = "SRI_remote_strobe_replication",
            seed = seed,
            events = conditions.mapIndexed { index, condition ->
                TrialEvent(
                    index = index + 1,
                    condition = condition,
                    cueSeconds = 1.0,
                    stimulusSeconds = 10.0,
                    restSeconds = 2.0
                )
            }
        )
        return InvestigationProtocol(
            name = "SRI remote-strobe trial schedule",
            evidenceLayer = EvidenceLayer.DECLASSIFIED,
            description = "Recreates the randomized trial structure reported in a CIA/SRI research document: twelve null, twelve 6 Hz and twelve 16 Hz ten-second trials. Forge exports the schedule only; it deliberately does not flash a human-facing display.",
            program = null,
            eventSchedule = schedule,
            instrumentOnly = true,
            maximumUsefulOutputHz = 0
        )
    }

    private fun framedPayload(payload: ByteArray, fileName: String, magic: String): ByteArray =
        ByteArrayOutputStream().apply {
            repeat(64) { write(0x55) }
            write(magic.toByteArray(Charsets.US_ASCII))
            val name = fileName.toByteArray(Charsets.UTF_8).take(120).toByteArray()
            write(name.size)
            write(name)
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array())
            write(payload)
            val crc = CRC32().apply { update(payload) }.value.toInt()
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc).array())
        }.toByteArray()

    private fun mSequence7(): IntArray = lfsrSequence(intArrayOf(7, 6), 127, 0x7F)

    private fun lfsrSequence(taps: IntArray, length: Int, initialState: Int): IntArray {
        var state = initialState and 0x7F
        if (state == 0) state = 1
        return IntArray(length) {
            val output = state and 1
            var feedback = 0
            taps.forEach { tap -> feedback = feedback xor ((state ushr (tap - 1)) and 1) }
            state = (state ushr 1) or (feedback shl 6)
            output
        }
    }
}
