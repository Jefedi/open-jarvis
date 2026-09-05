package com.openjarvis.murena

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

object SpeechRegistry {
    private val inputs = ConcurrentHashMap<String, () -> SpeechToTextProvider>()
    private val outputs = ConcurrentHashMap<String, () -> SpeechOutputProvider>()
    init {
        registerInput("voxtral-stt") { ApiTranscription() }
        registerInput("whisper") { ApiTranscription() }
        registerOutput("voxtral-tts") { ApiSpeech() }
        listOf("openai-tts", "kokoro").forEach { registerOutput(it) { ApiSpeech() } }
    }
    fun registerInput(kind: String, factory: () -> SpeechToTextProvider) { inputs[kind] = factory }
    fun registerOutput(kind: String, factory: () -> SpeechOutputProvider) { outputs[kind] = factory }
    fun input(profile: ConnectionProfile) = inputs[profile.kind]?.invoke() ?: error("Profil de transcription incompatible.")
    fun output(profile: ConnectionProfile) = outputs[profile.kind]?.invoke() ?: error("Profil de voix incompatible.")
}

class ApiTranscription : SpeechToTextProvider {
    override suspend fun transcribe(profile: ConnectionProfile, wav: ByteArray, language: String): String = transcribeProgressive(profile, wav, language) {}
    suspend fun transcribeProgressive(profile: ConnectionProfile, wav: ByteArray, language: String, onText: (String) -> Unit): String {
        require(wav.size in 45..8_000_000) { "Enregistrement vide ou trop long." }
        require(profile.model.isNotBlank()) { "Choisissez un modèle de transcription." }
        val streaming = profile.kind == "voxtral-stt" && profile.streaming
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", profile.model)
            .addFormDataPart("file", "commande.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .apply {
                if (language.isNotBlank() && language != "auto") addFormDataPart("language", language.substringBefore('-'))
                if (streaming) addFormDataPart("stream", "true")
            }.build()
        return SafeHttp.execute(profile, SafeHttp.request(profile, "audio/transcriptions", "POST", body)) { response ->
            if (streaming && response.header("Content-Type").orEmpty().contains("text/event-stream")) {
                val result = StringBuilder(); var done = false
                SafeHttp.readSse(response) { event, data ->
                    val j = JSONObject(data)
                    when (j.optString("type", event)) {
                        "transcription.text.delta" -> { val t = j.optString("text", j.optString("delta")); result.append(t); onText(t) }
                        "transcription.done" -> {
                            j.nullableString("text")?.let { if (result.isEmpty()) { result.append(it); onText(it) } }
                            done = true
                        }
                        "error", "transcription.error" -> error("La transcription a été refusée.")
                    }
                }
                check(done) { "Transcription interrompue." }
                result.toString().trim().also { require(it.isNotEmpty()) { "Aucune parole reconnue." } }
            } else {
                JSONObject(SafeHttp.limitedBytes(response, 1_000_000).toString(Charsets.UTF_8)).getString("text").trim()
                    .also { require(it.isNotEmpty()) { "Aucune parole reconnue." }; onText(it) }
            }
        }
    }
}

class ApiSpeech : SpeechOutputProvider {
    override suspend fun synthesize(profile: ConnectionProfile, text: String, speed: Float): PcmAudio {
        val payload = payload(profile, text, false, "wav", speed)
        return SafeHttp.execute(profile, SafeHttp.request(profile, "audio/speech", "POST", payload.toString().toRequestBody(SafeHttp.jsonType))) { response ->
            val bytes = SafeHttp.limitedBytes(response, 40_000_000)
            val audio = if (profile.kind == "voxtral-tts") {
                val j = JSONObject(bytes.toString(Charsets.UTF_8))
                Base64.getDecoder().decode(j.getString("audio_data"))
            } else bytes
            WaveCodec.decode(audio)
        }
    }
    override suspend fun stream(profile: ConnectionProfile, text: String, speed: Float, onChunk: (PcmAudio) -> Unit) {
        if (profile.kind != "voxtral-tts" || !profile.streaming) {
            onChunk(synthesize(profile, text, speed)); return
        }
        val payload = payload(profile, text, true, "pcm", speed)
        SafeHttp.execute(profile, SafeHttp.request(profile, "audio/speech", "POST", payload.toString().toRequestBody(SafeHttp.jsonType))) { response ->
            var done = false
            SafeHttp.readSse(response, maxChars = 64_000_000) { event, data ->
                val j = JSONObject(data)
                when (j.optString("type", event)) {
                    "speech.audio.delta" -> {
                        val bytes = Base64.getDecoder().decode(j.getString("audio_data"))
                        onChunk(PcmAudio(WaveCodec.float32ToPcm16(bytes), 24000, 1))
                    }
                    "speech.audio.done" -> done = true
                    "error", "speech.audio.error" -> error("La génération vocale a été interrompue.")
                }
            }
            check(done) { "Flux audio incomplet." }
        }
    }
    private fun payload(profile: ConnectionProfile, text: String, stream: Boolean, format: String, speed: Float): JSONObject {
        require(text.isNotBlank() && text.length <= 4000) { "Le segment vocal doit contenir entre 1 et 4 000 caractères." }
        require(profile.model.isNotBlank() && profile.voice.isNotBlank()) { "Choisissez le modèle et une voix dans le profil TTS." }
        return JSONObject().put("model", profile.model).put("input", text).put("response_format", format).apply {
            if (profile.kind == "voxtral-tts") { put("voice_id", profile.voice); put("stream", stream) }
            else { put("voice", profile.voice); put("speed", speed.coerceIn(0.5f, 2f).toDouble()) }
        }
    }
    suspend fun voices(profile: ConnectionProfile): List<Pair<String, String>> {
        require(profile.kind == "voxtral-tts") { "La découverte des voix est disponible pour Voxtral ; les autres profils acceptent un identifiant manuel." }
        return SafeHttp.json(profile, "audio/voices?type=preset&limit=100").optJSONArray("items")?.objects()?.map {
            it.getString("id") to it.optString("name", it.getString("id"))
        } ?: emptyList()
    }
}

object WaveCodec {
    fun encode(pcm16: ByteArray, sampleRate: Int = 16000, channels: Int = 1): ByteArray {
        require(channels in 1..2 && sampleRate in 8000..96000 && pcm16.size % (2 * channels) == 0)
        return ByteBuffer.allocate(44 + pcm16.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm16.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(channels.toShort()); putInt(sampleRate)
            putInt(sampleRate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(pcm16.size); put(pcm16)
        }.array()
    }
    fun decode(wav: ByteArray): PcmAudio {
        require(wav.size >= 44 && wav.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            wav.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE") { "Le serveur n'a pas renvoyé un fichier WAV valide." }
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var position = 12; var format = 0; var bits = 0; var channels = 0; var rate = 0; var data: ByteArray? = null
        while (position + 8 <= wav.size) {
            val tag = wav.copyOfRange(position, position + 4).toString(Charsets.US_ASCII)
            val length = buffer.getInt(position + 4).toLong() and 0xffffffffL
            val start = position + 8
            require(length <= wav.size - start) { "Bloc WAV tronqué." }
            if (tag == "fmt ") {
                require(length >= 16)
                format = buffer.getShort(start).toInt() and 0xffff
                channels = buffer.getShort(start + 2).toInt() and 0xffff
                rate = buffer.getInt(start + 4); bits = buffer.getShort(start + 14).toInt() and 0xffff
                if (format == 0xfffe && length >= 40) format = buffer.getShort(start + 24).toInt() and 0xffff
            } else if (tag == "data") data = wav.copyOfRange(start, start + length.toInt())
            position = start + length.toInt() + (length.toInt() and 1)
        }
        require(channels in 1..2 && rate in 8000..96000) { "Format audio non pris en charge." }
        val bytes = data ?: error("Données audio absentes.")
        val samples = when {
            format == 1 && bits == 16 -> bytes
            format == 3 && bits == 32 -> float32ToPcm16(bytes)
            else -> error("Le serveur doit fournir du PCM 16 bits ou float32 dans WAV.")
        }
        require(samples.isNotEmpty() && samples.size % (channels * 2) == 0)
        return PcmAudio(samples, rate, channels)
    }
    fun float32ToPcm16(bytes: ByteArray): ByteArray {
        require(bytes.size % 4 == 0) { "Échantillon float32 tronqué." }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(bytes.size / 2).order(ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= 4) {
            val value = input.float
            output.putShort(((if (value.isFinite()) value else 0f).coerceIn(-1f, 1f) * 32767).toInt().toShort())
        }
        return output.array()
    }
}
