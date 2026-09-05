package com.openjarvis.murena

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt

class AudioController(private val context: Context, private val store: ProfileStore) {
    data class Status(val stage: String = "idle", val detail: String = "", val transcript: String = "")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus
    private var inputJob: Job? = null
    private var outputJob: Job? = null
    @Volatile private var recording = false
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var playbackCancelled = false
    private var recognizer: SpeechRecognizer? = null
    private var androidTts: TextToSpeech? = null
    private var audioFocus: AudioFocusRequest? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    var foreground: Boolean = false
        set(value) { field = value; if (!value) cancelMicrophone() }

    fun startMicrophone(onResult: (String) -> Unit) {
        if (inputJob?.isActive == true) { finishMicrophone(); return }
        val previousSpeech = outputJob
        stopSpeech()
        if (!foreground) { mutableStatus.value = Status("error", "Ouvrez Jarvis pour utiliser le microphone."); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableStatus.value = Status("error", "L'autorisation du microphone est nécessaire."); return
        }
        val selected = store.selected(Roles.STT)
        val previousInput = inputJob
        inputJob = scope.launch {
            previousInput?.join()
            previousSpeech?.join()
            try {
                val transcript = if (selected == Roles.ANDROID) recognizeAndroid() else {
                    val profile = store.profile(selected) ?: error("Choisissez un profil de transcription ou le moteur Android.")
                    mutableStatus.value = Status("listening", "Microphone actif — ${profile.name}. Appuyez de nouveau pour terminer.")
                    val wav = recordWav()
                    try {
                        mutableStatus.value = Status("transcribing", "Transcription — ${profile.name}")
                        val provider = SpeechRegistry.input(profile)
                        if (provider is ApiTranscription) provider.transcribeProgressive(profile, wav, store.language()) { text ->
                            val previous = mutableStatus.value
                            mutableStatus.value = previous.copy(transcript = previous.transcript + text)
                        } else provider.transcribe(profile, wav, store.language())
                    } finally { wav.fill(0) }
                }
                mutableStatus.value = Status("idle", "Transcription terminée.", transcript)
                onResult(transcript)
            } catch (_: CancellationException) {
                mutableStatus.value = Status()
            } catch (e: Exception) {
                mutableStatus.value = Status("error", e.message ?: "La reconnaissance vocale a échoué.")
            } finally { recording = false; recognizer?.destroy(); recognizer = null }
        }
    }
    fun finishMicrophone() {
        recording = false
        recognizer?.stopListening()
    }
    fun cancelMicrophone() {
        recording = false
        inputJob?.cancel(); inputJob = null
        try { audioRecord?.stop() } catch (_: Exception) { }
        if (Looper.myLooper() == Looper.getMainLooper()) recognizer?.cancel()
        else Handler(Looper.getMainLooper()).post { recognizer?.cancel() }
    }
    private suspend fun recordWav(): ByteArray = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Ce téléphone ne fournit pas le format d'enregistrement requis." }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error("L'autorisation du microphone a été retirée.")
        }
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 8192))
        audioRecord = recorder
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release(); audioRecord = null
            error("Le microphone n'a pas pu être initialisé.")
        }
        val output = ByteArrayOutputStream(); val buffer = ByteArray(3200)
        var heardSpeech = false; var silentSamples = 0
        val vad = store.flag("voice_end_detection", true)
        try {
            recording = true; recorder.startRecording()
            while (recording && currentCoroutineContext().isActive && output.size() < 16000 * 2 * 60) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count < 0) { if (!recording) break; error("Le microphone a été interrompu.") }
                if (count == 0) continue
                output.write(buffer, 0, count)
                var squares = 0.0
                for (i in 0 until count - 1 step 2) {
                    val sample = ((buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
                    squares += sample.toDouble() * sample
                }
                val rms = sqrt(squares / (count / 2).coerceAtLeast(1))
                if (rms > 450) { heardSpeech = true; silentSamples = 0 } else silentSamples += count / 2
                if (vad && heardSpeech && silentSamples >= 16000 * 2) break
                if (!heardSpeech && output.size() >= 16000 * 2 * 15) error("Aucune parole détectée. Vous pouvez saisir la commande au clavier.")
            }
            currentCoroutineContext().ensureActive()
            require(output.size() >= 3200) { "Enregistrement trop court." }
            val pcm = output.toByteArray()
            try { WaveCodec.encode(pcm) } finally { pcm.fill(0) }
        } finally {
            recording = false; buffer.fill(0); output.reset()
            try { recorder.stop() } catch (_: Exception) { }
            recorder.release(); audioRecord = null
        }
    }
    private suspend fun recognizeAndroid(): String {
        check(SpeechRecognizer.isRecognitionAvailable(context)) { "Aucun moteur de reconnaissance Android installé. Choisissez Voxtral ou un serveur Whisper." }
        val result = CompletableDeferred<String>()
        val engine = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = engine
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { mutableStatus.value = Status("listening", "Microphone actif — moteur Android installé.") }
            override fun onBeginningOfSpeech() { }
            override fun onRmsChanged(rmsdB: Float) { }
            override fun onBufferReceived(buffer: ByteArray?) { }
            override fun onEndOfSpeech() { mutableStatus.value = mutableStatus.value.copy(stage = "transcribing", detail = "Reconnaissance Android…") }
            override fun onError(error: Int) { result.completeExceptionally(IllegalStateException("Reconnaissance Android indisponible (code $error). Utilisez le clavier ou un autre moteur.")) }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) result.completeExceptionally(IllegalStateException("Aucune parole reconnue.")) else result.complete(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                mutableStatus.value = mutableStatus.value.copy(transcript = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onEvent(eventType: Int, params: Bundle?) { }
        })
        mutableStatus.value = Status("listening", "Microphone actif — moteur Android.")
        engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, store.language())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
        return try { withTimeout(70000) { result.await() } } finally { engine.cancel(); engine.destroy(); recognizer = null }
    }

    fun speak(text: String) {
        val previousOutput = outputJob
        stopSpeech()
        if (store.selected(Roles.TTS) == Roles.SILENT || text.isBlank()) return
        outputJob = scope.launch {
            previousOutput?.join()
            playbackCancelled = false
            try {
                obtainAudioFocus()
                val selected = store.selected(Roles.TTS)
                if (selected == Roles.ANDROID) speakAndroid(text)
                else {
                    val profile = store.profile(selected) ?: error("Choisissez un profil de voix.")
                    mutableStatus.value = Status("speaking", "Voix — ${profile.name}")
                    for (segment in splitSpeech(text)) {
                        ensureActive()
                        withContext(Dispatchers.IO) {
                            SpeechRegistry.output(profile).stream(profile, segment, store.speechSpeed()) { audio ->
                                check(!playbackCancelled) { "Lecture arrêtée." }
                                try { writeAudio(audio, if (profile.kind == "voxtral-tts") store.speechSpeed() else 1f) }
                                finally { audio.samples.fill(0) }
                            }
                        }
                    }
                    // Drain the audio already accepted by AudioTrack before releasing it.
                    withContext(Dispatchers.IO) {
                        val track = audioTrack
                        if (track != null) {
                            val start = System.nanoTime()
                            while (!playbackCancelled && track.playbackHeadPosition.toLong() < writtenFrames && System.nanoTime() - start < 120_000_000_000L) {
                                currentCoroutineContext().ensureActive(); delay(25)
                            }
                        }
                    }
                }
                mutableStatus.value = Status("idle", "Lecture terminée.")
            } catch (_: CancellationException) { mutableStatus.value = Status() }
            catch (e: Exception) { if (!playbackCancelled) mutableStatus.value = Status("error", e.message ?: "Synthèse vocale indisponible.") }
            finally { releasePlayback() }
        }
    }
    @Volatile private var writtenFrames = 0L
    private var trackRate = 0
    private var trackChannels = 0
    @Synchronized private fun createTrack(audio: PcmAudio, speed: Float): AudioTrack {
        if (audioTrack != null) {
            require(trackRate == audio.sampleRate && trackChannels == audio.channels) { "Le format audio a changé pendant la lecture." }
            return audioTrack!!
        }
        check(!playbackCancelled)
        val mask = if (audio.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(audio.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(audio.sampleRate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, 8192)).setTransferMode(AudioTrack.MODE_STREAM).build()
        check(track.state == AudioTrack.STATE_INITIALIZED)
        track.setVolume(store.speechVolume())
        track.playbackParams = PlaybackParams().allowDefaults().setSpeed(speed).setPitch(1f)
        trackRate = audio.sampleRate; trackChannels = audio.channels; writtenFrames = 0
        audioTrack = track; track.play(); return track
    }
    private fun writeAudio(audio: PcmAudio, speed: Float) {
        val track = createTrack(audio, speed)
        var offset = 0
        while (offset < audio.samples.size) {
            if (playbackCancelled) throw CancellationException("Lecture arrêtée.")
            val count = track.write(audio.samples, offset, minOf(8192, audio.samples.size - offset), AudioTrack.WRITE_BLOCKING)
            check(count > 0) { "La sortie audio a été interrompue." }
            offset += count; writtenFrames += count / (audio.channels * 2)
        }
    }
    private suspend fun speakAndroid(text: String) {
        val engines = installedTtsEngines(context)
        val requested = store.text("android_tts_engine")
        val default = Settings.Secure.getString(context.contentResolver, "tts_default_synth")
        val enginePackage = engines.firstOrNull { it.first == requested }?.first
            ?: engines.firstOrNull { it.first == default }?.first ?: engines.firstOrNull()?.first
            ?: error("Aucun moteur TTS Android installé. Installez un moteur local ou choisissez Voxtral.")
        val ready = CompletableDeferred<Int>()
        val engine = TextToSpeech(context, { ready.complete(it) }, enginePackage)
        androidTts = engine
        check(withTimeout(15000) { ready.await() } == TextToSpeech.SUCCESS) { "Le moteur TTS Android n'a pas démarré." }
        check(engine.setLanguage(Locale.forLanguageTag(store.language())) >= 0) { "Cette langue n'est pas installée dans le moteur TTS." }
        engine.setSpeechRate(store.speechSpeed())
        mutableStatus.value = Status("speaking", "Voix Android — ${engines.first { it.first == enginePackage }.second}")
        for (segment in splitSpeech(text)) {
            val complete = CompletableDeferred<Unit>(); val id = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { }
                override fun onDone(utteranceId: String?) { if (utteranceId == id) complete.complete(Unit) }
                @Deprecated("Android callback") override fun onError(utteranceId: String?) { if (utteranceId == id) complete.completeExceptionally(IllegalStateException("Échec de la voix Android.")) }
            })
            val parameters = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, store.speechVolume()) }
            check(engine.speak(segment, TextToSpeech.QUEUE_FLUSH, parameters, id) == TextToSpeech.SUCCESS)
            withTimeout(120000) { complete.await() }
        }
    }
    private fun obtainAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> if (change <= AudioManager.AUDIOFOCUS_LOSS) scope.launch { stopSpeech() } }.build()
        check(audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "La sortie audio est occupée." }
        audioFocus = request
    }
    fun stopSpeech() {
        playbackCancelled = true
        outputJob?.cancel(); outputJob = null
        releasePlayback()
    }
    @Synchronized private fun releasePlayback() {
        val track = audioTrack; audioTrack = null
        try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) { }
        androidTts?.stop(); androidTts?.shutdown(); androidTts = null
        audioFocus?.let { audioManager.abandonAudioFocusRequest(it) }; audioFocus = null
    }
    fun stopAll() { cancelMicrophone(); stopSpeech() }
    companion object {
        fun splitSpeech(text: String): List<String> {
            val result = mutableListOf<String>(); var remainder = text.take(24000)
            while (remainder.isNotEmpty()) {
                if (remainder.length <= 1200) { result.add(remainder); break }
                val prefix = remainder.take(1200)
                val boundary = maxOf(prefix.lastIndexOf('.'), prefix.lastIndexOf('!'), prefix.lastIndexOf('?'), prefix.lastIndexOf('\n'))
                    .takeIf { it >= 200 }?.plus(1) ?: prefix.lastIndexOf(' ').takeIf { it >= 200 } ?: 1200
                result.add(remainder.take(boundary)); remainder = remainder.drop(boundary).trimStart()
            }
            return result
        }
        fun installedTtsEngines(context: Context): List<Pair<String, String>> = context.packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .filterNot { it.serviceInfo.packageName == context.packageName }
            .map { it.serviceInfo.packageName to it.loadLabel(context.packageManager).toString() }.distinctBy { it.first }
    }
}
