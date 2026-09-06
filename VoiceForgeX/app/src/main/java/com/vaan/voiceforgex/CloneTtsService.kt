package com.vaan.voiceforgex

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import kotlinx.coroutines.runBlocking
import java.util.Locale

class CloneTtsService : TextToSpeechService() {
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        if (lang.equals("eng", true) || lang.equals("en", true)) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = onIsLanguageAvailable(lang, country, variant)
    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")

    override fun onGetVoices(): MutableList<Voice> = CloneRepository.all().map {
        Voice("vfx:${it.id}", Locale.US, Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, setOf("offline", "cloned"))
    }.toMutableList()

    override fun onIsValidVoiceName(voiceName: String?): Int = if (CloneRepository.byVoiceName(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)
    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String = CloneRepository.selected()?.let { "vfx:${it.id}" } ?: ""

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val p = CloneRepository.byVoiceName(request.voiceName)
        if (p == null || !ModelManager.isReady(this)) { callback.error(); return }
        runCatching {
            val a = runBlocking { CloneEngine.generate(this@CloneTtsService, p, request.charSequenceText.toString(), 5) }
            callback.start(a.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            val pcm = WavUtils.floatToPcm16(a.samples)
            var off = 0
            while (off < pcm.size) {
                val n = minOf(callback.maxBufferSize, pcm.size - off)
                if (callback.audioAvailable(pcm, off, n) != TextToSpeech.SUCCESS) break
                off += n
            }
            callback.done()
        }.onFailure { callback.error() }
    }

    override fun onStop() {}
}
