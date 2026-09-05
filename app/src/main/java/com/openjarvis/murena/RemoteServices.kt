package com.openjarvis.murena

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Only supports tools; never advertises sampling, roots, arbitrary remote code or elicitation. */
class McpConnection(private val profile: ConnectionProfile) {
    private val lock = Mutex()
    @Volatile private var session: String? = null
    @Volatile private var version = "2025-11-25"
    private var initialized = false
    private val supportedVersions = setOf("2025-11-25", "2025-06-18", "2025-03-26")
    private class ResponseComplete : RuntimeException()

    suspend fun initialize() = lock.withLock {
        if (initialized) return@withLock
        val result = rpc("initialize", JSONObject().put("protocolVersion", version)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "open-jarvis-murena").put("version", "0.2.0")), includeProtocol = false)
        val negotiated = result.getString("protocolVersion")
        require(negotiated in supportedVersions) { "Version MCP non prise en charge par ce client." }
        version = negotiated
        notification("notifications/initialized")
        initialized = true
    }
    suspend fun listTools(): List<ToolDefinition> {
        initialize()
        val tools = mutableListOf<ToolDefinition>()
        var cursor: String? = null
        repeat(10) {
            val page = rpc("tools/list", JSONObject().apply { cursor?.let { put("cursor", it) } })
            page.optJSONArray("tools")?.objects()?.forEach { tool ->
                val name = tool.getString("name")
                require(name.length <= 128)
                tools.add(ToolDefinition(name, tool.optString("description").take(2000), tool.optJSONObject("inputSchema") ?: schema()))
            }
            require(tools.size <= 500) { "Le serveur expose trop d'outils." }
            cursor = page.nullableString("nextCursor")
            if (cursor == null) return tools.distinctBy { it.name }
        }
        error("La pagination des outils MCP dépasse la limite de sécurité.")
    }
    suspend fun callTool(name: String, arguments: JSONObject): JSONObject {
        require(name in profile.allowlist) { "Cet outil MCP n'a pas été autorisé dans le profil." }
        initialize()
        // No automatic retry: a lost response does not prove that a remote mutation did not happen.
        return rpc("tools/call", JSONObject().put("name", name).put("arguments", arguments))
    }
    suspend fun close() {
        if (session != null) {
            try { SafeHttp.execute(profile, SafeHttp.request(profile, method = "DELETE", extraHeaders = headers())) { Unit } }
            catch (_: Exception) { }
        }
        initialized = false; session = null
    }
    private fun headers(includeProtocol: Boolean = true): Map<String, String> = buildMap {
        put("Accept", "application/json, text/event-stream")
        if (includeProtocol) put("MCP-Protocol-Version", version)
        session?.let { put("Mcp-Session-Id", it) }
    }
    private suspend fun notification(method: String) {
        val message = JSONObject().put("jsonrpc", "2.0").put("method", method)
        SafeHttp.execute(profile, SafeHttp.request(profile, method = "POST", body = message.toString().toRequestBody(SafeHttp.jsonType), extraHeaders = headers())) { Unit }
    }
    private suspend fun rpc(method: String, params: JSONObject, includeProtocol: Boolean = true): JSONObject = coroutineScope {
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        SafeHttp.execute(profile, SafeHttp.request(profile, method = "POST", body = body.toString().toRequestBody(SafeHttp.jsonType), extraHeaders = headers(includeProtocol))) { response ->
            response.header("Mcp-Session-Id")?.let {
                require(it.length in 1..2048 && it.all { c -> c.code in 0x21..0x7e }) { "Session MCP invalide." }
                session = it
            }
            var result: JSONObject? = null
            fun receive(j: JSONObject) {
                require(j.optString("jsonrpc") == "2.0") { "Réponse JSON-RPC invalide." }
                if (j.has("method")) {
                    if (j.has("id")) {
                        val error = JSONObject().put("jsonrpc", "2.0").put("id", j.get("id"))
                            .put("error", JSONObject().put("code", -32601).put("message", "Client capability not available"))
                        launch(Dispatchers.IO) {
                            try { SafeHttp.execute(profile, SafeHttp.request(profile, method = "POST", body = error.toString().toRequestBody(SafeHttp.jsonType), extraHeaders = headers())) { Unit } }
                            catch (_: Exception) { }
                        }
                    }
                    return
                }
                if (j.opt("id")?.toString() != id) return
                if (j.has("error")) error("Le serveur MCP a refusé cette opération (code ${j.optJSONObject("error")?.optInt("code")}).")
                result = j.optJSONObject("result") ?: error("Résultat MCP invalide.")
            }
            if (response.header("Content-Type").orEmpty().contains("text/event-stream")) {
                try { SafeHttp.readSse(response) { _, data -> receive(JSONObject(data)); if (result != null) throw ResponseComplete() } }
                catch (_: ResponseComplete) { }
            } else receive(JSONObject(SafeHttp.limitedBytes(response, 2_000_000).toString(Charsets.UTF_8)))
            result ?: error("Le serveur MCP n'a pas répondu à la requête attendue.")
        }
    }
}

class HomeAssistantConnection(private val profile: ConnectionProfile) {
    data class Entity(val id: String, val name: String, val state: String, val attributes: JSONObject) {
        fun json(): JSONObject = JSONObject().put("entity_id", id).put("name", name).put("state", state).put("attributes", attributes)
    }
    fun allowed(entityId: String): Boolean {
        if (!entityId.matches(Regex("[a-z0-9_]+\\.[a-z0-9_]+"))) return false
        return entityId in profile.allowlist || "${entityId.substringBefore('.')}.*" in profile.allowlist
    }
    suspend fun test(): String = SafeHttp.json(profile, "api/").optString("message", "API Home Assistant accessible.")
    suspend fun discover(): List<Entity> = SafeHttp.execute(profile, SafeHttp.request(profile, "api/states")) {
        JSONArray(SafeHttp.limitedBytes(it, 4_000_000).toString(Charsets.UTF_8)).objects().map(::entity)
    }
    suspend fun selectedEntities(): List<Entity> = discover().filter { allowed(it.id) }
    suspend fun read(entityId: String): Entity {
        require(allowed(entityId)) { "Cette entité Home Assistant n'a pas été autorisée." }
        return entity(SafeHttp.json(profile, "api/states/$entityId"))
    }
    suspend fun call(entityId: String, service: String, data: JSONObject): JSONObject {
        require(allowed(entityId)) { "Cette entité Home Assistant n'a pas été autorisée." }
        require(service.matches(Regex("[a-z0-9_]+\\.[a-z0-9_]+"))) { "Service Home Assistant invalide." }
        val domain = service.substringBefore('.'); val action = service.substringAfter('.')
        require(domain == entityId.substringBefore('.') || domain == "homeassistant") { "Le service ne correspond pas au domaine de l'entité." }
        require(data.toString().length <= 20000)
        val forbidden = setOf("entity_id", "area_id", "device_id", "floor_id", "target")
        require(data.keys().asSequence().none { it in forbidden }) { "La cible doit être l'entité explicitement autorisée, pas une zone ou un autre appareil." }
        val payload = JSONObject(data.toString()).put("entity_id", entityId)
        SafeHttp.execute(profile, SafeHttp.request(profile, "api/services/$domain/$action", "POST", payload.toString().toRequestBody(SafeHttp.jsonType))) {
            SafeHttp.limitedBytes(it, 2_000_000); Unit
        }
        delay(350)
        val state = try { read(entityId).json() } catch (_: Exception) { JSONObject().put("verification", "État non disponible ; ne pas conclure à la réussite physique.") }
        return JSONObject().put("request_accepted", true).put("observed", state)
    }
    private fun entity(j: JSONObject): Entity {
        val attributes = j.optJSONObject("attributes") ?: JSONObject()
        val selected = JSONObject()
        listOf("unit_of_measurement", "device_class", "temperature", "current_temperature", "brightness", "current_position", "hvac_action", "volume_level").forEach {
            if (attributes.has(it)) selected.put(it, attributes.get(it))
        }
        val id = j.getString("entity_id")
        return Entity(id, attributes.optString("friendly_name", id), j.optString("state"), selected)
    }
}

object VectorApi : EmbeddingProvider {
    override suspend fun embed(profile: ConnectionProfile, texts: List<String>): List<FloatArray> {
        require(texts.isNotEmpty() && texts.size <= 32 && texts.sumOf { it.length } <= 50000)
        require(profile.model.isNotBlank()) { "Choisissez un modèle d'embeddings." }
        val payload = JSONObject().put("model", profile.model).put("input", JSONArray(texts))
        val result = SafeHttp.json(profile, if (profile.kind == "ollama-embeddings") "api/embed" else "embeddings", "POST", payload)
        val vectors = if (profile.kind == "ollama-embeddings") result.getJSONArray("embeddings") else
            JSONArray(result.getJSONArray("data").objects().sortedBy { it.getInt("index") }.map { it.getJSONArray("embedding") })
        require(vectors.length() == texts.size)
        return (0 until vectors.length()).map { index ->
            val values = vectors.getJSONArray(index)
            require(values.length() in 1..32768)
            FloatArray(values.length()) { values.getDouble(it).toFloat().also { v -> require(v.isFinite()) } }
        }
    }
}

object ImageApi : VisionProvider {
    override suspend fun describe(profile: ConnectionProfile, jpeg: ByteArray, question: String): String {
        require(jpeg.size <= 4_000_000 && question.length <= 10000 && profile.model.isNotBlank())
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", question))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpeg))))
        val payload = JSONObject().put("model", profile.model).put("max_tokens", profile.outputTokens).put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val response = SafeHttp.json(profile, "chat/completions", "POST", payload)
        return response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }
}
