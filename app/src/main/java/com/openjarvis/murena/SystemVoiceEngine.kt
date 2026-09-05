package com.openjarvis.murena

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Only speaks text supplied through Android's TTS API after the owner opts in.
 * It cannot read screens, capture a microphone, launch another app, or control a device.
 * Android serializes synthesis requests on a dedicated synthesis thread.
 */
class JarvisSystemTtsService : TextToSpeechService() {
    private val active = AtomicReference<Job?>(null)
    private fun selectedProfile(): ConnectionProfile? = try {
        val store = ProfileStore.get(this)
        if (!store.flag("system_tts")) null else store.chosen(Roles.TTS)?.takeIf {
            Providers.info(it.kind).capability == "tts"
        }
    } catch (_: Exception) { null }

    override fun onGetFeaturesForLanguage(lang: String, country: String, variant: String): MutableSet<String> =
        mutableSetOf(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
    override fun onGetLanguage(): Array<String> = arrayOf("fra", "FRA", "")
    override fun onIsLanguageAvailable(lang: String, country: String, variant: String): Int {
        if (selectedProfile() == null) return TextToSpeech.LANG_MISSING_DATA
        return if (lang.lowercase() in setOf("fr", "fra", "fre", "en", "eng")) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
    }
    override fun onLoadLanguage(lang: String, country: String, variant: String): Int = onIsLanguageAvailable(lang, country, variant)
    override fun onStop() { active.getAndSet(null)?.cancel() }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val profile = selectedProfile()
        if (profile == null) { callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET); return }
        val text = request.charSequenceText.toString()
        if (text.isBlank() || text.length > 24000) { callback.error(TextToSpeech.ERROR_INVALID_REQUEST); return }
        val job = Job()
        active.getAndSet(job)?.cancel()
        try {
            runBlocking(job) {
                var format: Pair<Int, Int>? = null
                for (segment in AudioController.splitSpeech(text)) {
                    coroutineContext.ensureActive()
                    check(selectedProfile() == profile) { "Voice permission changed" }
                    val audio = SpeechRegistry.output(profile).synthesize(profile.copy(streaming = false), segment,
                        (request.speechRate / 100f).coerceIn(0.5f, 2f))
                    coroutineContext.ensureActive()
                    check(selectedProfile() == profile) { "Voice permission revoked" }
                    if (format == null) {
                        check(callback.start(audio.sampleRate, AudioFormat.ENCODING_PCM_16BIT, audio.channels) == TextToSpeech.SUCCESS)
                        format = audio.sampleRate to audio.channels
                    } else check(format == (audio.sampleRate to audio.channels))
                    try {
                        var offset = 0
                        val maximum = callback.maxBufferSize.coerceAtLeast(1)
                        while (offset < audio.samples.size) {
                            coroutineContext.ensureActive()
                            val count = minOf(maximum, audio.samples.size - offset)
                            check(callback.audioAvailable(audio.samples, offset, count) == TextToSpeech.SUCCESS)
                            offset += count
                        }
                    } finally { audio.samples.fill(0) }
                }
                callback.done()
            }
        } catch (_: CancellationException) {
            // Stop is handled by the framework; do not emit additional audio or retry.
        } catch (_: Exception) { callback.error(TextToSpeech.ERROR_SYNTHESIS) }
        finally { active.compareAndSet(job, null); job.cancel() }
    }
    override fun onDestroy() { onStop(); super.onDestroy() }
}
