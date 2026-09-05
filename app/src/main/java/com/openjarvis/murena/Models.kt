package com.openjarvis.murena

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** No credentials may appear in logs or in the default representation of a profile. */
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Nouveau profil",
    val kind: String = "mistral",
    val url: String = "https://api.mistral.ai/v1",
    val model: String = "mistral-small-latest",
    val secret: String = "",
    val headers: Map<String, String> = emptyMap(),
    val timeoutSeconds: Int = 90,
    val contextTokens: Int = 32000,
    val outputTokens: Int = 2048,
    val temperature: Double = 0.2,
    val streaming: Boolean = true,
    val tools: Boolean = true,
    val allowLocalHttp: Boolean = false,
    val voice: String = "",
    val allowlist: List<String> = emptyList()
) {
    override fun toString(): String = "ConnectionProfile(id=$id, name=$name, kind=$kind, credentials=[redacted])"
    fun json(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("kind", kind); put("url", url); put("model", model)
        put("secret", secret); put("headers", JSONObject(headers)); put("timeout", timeoutSeconds)
        put("context", contextTokens); put("output", outputTokens); put("temperature", temperature)
        put("streaming", streaming); put("tools", tools); put("localHttp", allowLocalHttp)
        put("voice", voice); put("allowlist", JSONArray(allowlist))
    }
    fun validate() {
        require(name.isNotBlank() && name.length <= 100) { "Le nom du profil est obligatoire (100 caractères maximum)." }
        require(timeoutSeconds in 5..300) { "Le délai doit être compris entre 5 et 300 secondes." }
        require(contextTokens in 1024..2_000_000 && outputTokens in 1..131072) { "Limites de contexte ou de réponse invalides." }
        require(temperature in 0.0..2.0 && temperature.isFinite()) { "Température invalide." }
        require(secret.length < 10000 && headers.size <= 20) { "Identifiants ou en-têtes trop volumineux." }
        require(allowlist.size <= 500) { "La liste d'autorisations est trop longue." }
        SafeHttp.validateUrl(url, allowLocalHttp)
        headers.forEach { (name, value) ->
            require(name.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Nom d'en-tête invalide." }
            require(name.lowercase() !in setOf("host", "content-length", "connection", "cookie", "set-cookie")) { "Cet en-tête n'est pas autorisé." }
            require(!value.contains('\r') && !value.contains('\n') && value.length < 10000) { "Valeur d'en-tête invalide." }
        }
    }
    companion object {
        fun fromJson(j: JSONObject): ConnectionProfile = ConnectionProfile(
            id = j.getString("id"), name = j.getString("name"), kind = j.getString("kind"),
            url = j.getString("url"), model = j.optString("model"), secret = j.optString("secret"),
            headers = j.optJSONObject("headers")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } } ?: emptyMap(),
            timeoutSeconds = j.optInt("timeout", 90), contextTokens = j.optInt("context", 32000),
            outputTokens = j.optInt("output", 2048), temperature = j.optDouble("temperature", 0.2),
            streaming = j.optBoolean("streaming", true), tools = j.optBoolean("tools", true),
            allowLocalHttp = j.optBoolean("localHttp"), voice = j.optString("voice"),
            allowlist = j.optJSONArray("allowlist")?.strings() ?: emptyList()
        )
    }
}

data class ProviderInfo(val id: String, val name: String, val url: String, val model: String, val capability: String,
                        val streaming: Boolean = false, val nativeTools: Boolean = false)

object Providers {
    val catalog = listOf(
        ProviderInfo("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1", "", "llm", true, true),
        ProviderInfo("mistral", "Mistral AI", "https://api.mistral.ai/v1", "mistral-small-latest", "llm", true, true),
        ProviderInfo("openai", "OpenAI Chat Completions", "https://api.openai.com/v1", "", "llm", true, true),
        ProviderInfo("responses", "OpenAI Responses", "https://api.openai.com/v1", "", "llm", true, true),
        ProviderInfo("ollama", "Ollama", "http://192.168.1.2:11434", "", "llm", true, true),
        ProviderInfo("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "", "llm", true, true),
        ProviderInfo("groq", "Groq", "https://api.groq.com/openai/v1", "", "llm", true, true),
        ProviderInfo("gemini", "Google Gemini (API)", "https://generativelanguage.googleapis.com/v1beta", "", "llm", true, true),
        ProviderInfo("lmstudio", "LM Studio", "http://192.168.1.2:1234/v1", "", "llm", true, true),
        ProviderInfo("llamacpp", "llama.cpp server", "http://192.168.1.2:8080/v1", "", "llm", true, true),
        ProviderInfo("compatible", "API compatible OpenAI", "https://exemple.invalid/v1", "", "llm", true, true),
        ProviderInfo("voxtral-stt", "Voxtral — transcription", "https://api.mistral.ai/v1", "voxtral-mini-latest", "stt"),
        ProviderInfo("whisper", "Whisper / transcription compatible", "https://api.openai.com/v1", "whisper-1", "stt"),
        ProviderInfo("voxtral-tts", "Voxtral — synthèse vocale", "https://api.mistral.ai/v1", "voxtral-mini-tts-2603", "tts"),
        ProviderInfo("openai-tts", "OpenAI / TTS compatible", "https://api.openai.com/v1", "tts-1", "tts"),
        ProviderInfo("kokoro", "Kokoro / Piper compatible OpenAI", "http://192.168.1.2:8880/v1", "kokoro", "tts"),
        ProviderInfo("mcp", "Serveur MCP (Streamable HTTP)", "https://exemple.invalid/mcp", "", "mcp"),
        ProviderInfo("homeassistant", "Home Assistant (REST)", "https://exemple.invalid", "", "homeassistant"),
        ProviderInfo("embeddings", "Embeddings compatibles OpenAI", "https://api.openai.com/v1", "", "embeddings"),
        ProviderInfo("ollama-embeddings", "Embeddings Ollama", "http://192.168.1.2:11434", "", "embeddings"),
        ProviderInfo("vision", "Vision compatible OpenAI", "https://api.mistral.ai/v1", "", "vision")
    )
    fun info(kind: String): ProviderInfo = catalog.firstOrNull { it.id == kind }
        ?: error("Fournisseur inconnu : $kind")
    fun example(kind: String): ConnectionProfile = info(kind).let {
        ConnectionProfile(name = it.name, kind = it.id, url = it.url, model = it.model,
            streaming = it.streaming, tools = it.nativeTools, allowLocalHttp = false,
            voice = if (kind == "openai-tts") "alloy" else if (kind == "kokoro") "ff_siwis" else "")
    }
}

object Roles {
    const val BRAIN = "brain"
    const val FAST = "fast"
    const val POWERFUL = "powerful"
    const val PRIVATE = "private"
    const val STT = "stt"
    const val TTS = "tts"
    const val VISION = "vision"
    const val EMBEDDINGS = "embeddings"
    const val ANDROID = "android"
    const val SILENT = "silent"
    val labels = linkedMapOf(BRAIN to "Cerveau principal", FAST to "Modèle rapide", POWERFUL to "Modèle puissant", PRIVATE to "Modèle privé / local", STT to "Reconnaissance vocale", TTS to "Voix de l'assistant", VISION to "Vision", EMBEDDINGS to "Mémoire vectorielle (facultative)")
}

data class ToolCall(val id: String, val name: String, val arguments: JSONObject)
data class ToolDefinition(val name: String, val description: String, val parameters: JSONObject)
data class ConversationMessage(val role: String, val text: String = "", val calls: List<ToolCall> = emptyList(), val toolId: String = "", val toolName: String = "")
data class Completion(val text: String, val calls: List<ToolCall> = emptyList())

interface LlmBackend {
    suspend fun complete(profile: ConnectionProfile, system: String, messages: List<ConversationMessage>, tools: List<ToolDefinition>, onText: (String) -> Unit = {}): Completion
    suspend fun models(profile: ConnectionProfile): List<String>
}
interface SpeechToTextProvider { suspend fun transcribe(profile: ConnectionProfile, wav: ByteArray, language: String): String }
interface SpeechOutputProvider { suspend fun synthesize(profile: ConnectionProfile, text: String, speed: Float): PcmAudio }
interface EmbeddingProvider { suspend fun embed(profile: ConnectionProfile, texts: List<String>): List<FloatArray> }
interface VisionProvider { suspend fun describe(profile: ConnectionProfile, jpeg: ByteArray, question: String): String }

data class PcmAudio(val samples: ByteArray, val sampleRate: Int, val channels: Int, val bits: Int = 16)

fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.nullableString(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
fun schema(vararg properties: Pair<String, String>, required: List<String> = properties.map { it.first }): JSONObject = JSONObject().apply {
    put("type", "object")
    put("properties", JSONObject().apply { properties.forEach { (name, type) -> put(name, JSONObject().put("type", type)) } })
    put("required", JSONArray(required)); put("additionalProperties", false)
}
