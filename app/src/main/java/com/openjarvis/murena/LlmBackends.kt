package com.openjarvis.murena

import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Providers can be registered without editing the Android execution engine. */
object LlmRegistry {
    private val factories = ConcurrentHashMap<String, () -> LlmBackend>()
    init {
        listOf("openai", "mistral", "openrouter", "groq", "lmstudio", "llamacpp", "compatible").forEach {
            register(it) { CompatibleBackend() }
        }
        register("anthropic") { AnthropicBackend() }
        register("responses") { ResponsesBackend() }
        register("gemini") { GeminiBackend() }
        register("ollama") { OllamaBackend() }
    }
    fun register(kind: String, factory: () -> LlmBackend) { factories[kind] = factory }
    fun backend(profile: ConnectionProfile): LlmBackend = factories[profile.kind]?.invoke()
        ?: error("Ce profil n'est pas un fournisseur de raisonnement.")
}

private fun function(tool: ToolDefinition): JSONObject = JSONObject().put("name", tool.name)
    .put("description", tool.description).put("parameters", tool.parameters)
private fun parseArguments(value: Any?): JSONObject = when (value) {
    is JSONObject -> value
    is String -> JSONObject(value.ifBlank { "{}" })
    else -> JSONObject()
}
private fun ensureCalls(calls: List<ToolCall>): List<ToolCall> {
    require(calls.size <= 16 && calls.map { it.id }.distinct().size == calls.size) { "Plan d'outils invalide." }
    calls.forEach { require(it.name.matches(Regex("[A-Za-z0-9_-]{1,64}")) && it.arguments.toString().length <= 50000) }
    return calls
}
private fun requireComplete(reason: String?) {
    if (reason in setOf("length", "max_tokens", "MAX_TOKENS", "content_filter", "SAFETY", "RECITATION"))
        throw IOException("Réponse interrompue ou refusée : aucun outil partiel ne sera exécuté.")
}
private fun chatMessages(system: String, messages: List<ConversationMessage>): JSONArray = JSONArray().apply {
    put(JSONObject().put("role", "system").put("content", system))
    messages.forEach { m -> put(JSONObject().put("role", m.role).apply {
        put("content", m.text)
        if (m.calls.isNotEmpty()) put("tool_calls", JSONArray(m.calls.map {
            JSONObject().put("id", it.id).put("type", "function").put("function", JSONObject()
                .put("name", it.name).put("arguments", it.arguments.toString()))
        }))
        if (m.role == "tool") put("tool_call_id", m.toolId)
    }) }
}

open class CompatibleBackend : LlmBackend {
    override suspend fun models(profile: ConnectionProfile): List<String> = SafeHttp.json(profile, "models").optJSONArray("data")
        ?.objects()?.mapNotNull { it.nullableString("id") }?.distinct()?.sorted() ?: emptyList()
    override suspend fun complete(profile: ConnectionProfile, system: String, messages: List<ConversationMessage>, tools: List<ToolDefinition>, onText: (String) -> Unit): Completion {
        require(profile.model.isNotBlank()) { "Sélectionnez un modèle dans le profil." }
        val payload = JSONObject().put("model", profile.model).put("messages", chatMessages(system, messages))
            .put("stream", profile.streaming)
        // Reasoning models can reject temperature and legacy max_tokens. Responses is also available.
        if (profile.kind == "openai") payload.put("max_completion_tokens", profile.outputTokens)
        else payload.put("max_tokens", profile.outputTokens).put("temperature", profile.temperature)
        if (tools.isNotEmpty() && profile.tools) payload.put("tools", JSONArray(tools.map {
            JSONObject().put("type", "function").put("function", function(it))
        })).put("tool_choice", "auto")
        return SafeHttp.execute(profile, SafeHttp.request(profile, "chat/completions", "POST", payload.toString().toRequestBody(SafeHttp.jsonType))) { response ->
            if (!profile.streaming || !response.header("Content-Type").orEmpty().contains("text/event-stream")) {
                val json = JSONObject(SafeHttp.limitedBytes(response, 2_000_000).toString(Charsets.UTF_8))
                val choice = json.getJSONArray("choices").getJSONObject(0)
                requireComplete(choice.nullableString("finish_reason"))
                val m = choice.getJSONObject("message")
                val text = m.nullableString("content").orEmpty()
                onText(text)
                Completion(text, ensureCalls(m.optJSONArray("tool_calls")?.objects()?.map {
                    val f = it.getJSONObject("function")
                    ToolCall(it.getString("id"), f.getString("name"), parseArguments(f.get("arguments")))
                } ?: emptyList()))
            } else {
                val text = StringBuilder()
                val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>()
                var finished = false
                SafeHttp.readSse(response) { _, data ->
                    if (data != "[DONE]") {
                        val json = JSONObject(data)
                        if (json.has("error")) throw IOException("Le flux a été refusé.")
                        val choice = json.optJSONArray("choices")?.optJSONObject(0)
                        if (choice != null) {
                            val reason = choice.nullableString("finish_reason")
                            requireComplete(reason)
                            if (reason != null) finished = true
                            val delta = choice.optJSONObject("delta") ?: JSONObject()
                            delta.nullableString("content")?.let { text.append(it); onText(it) }
                            delta.optJSONArray("tool_calls")?.objects()?.forEach { c ->
                                val entry = calls.getOrPut(c.optInt("index")) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                                c.nullableString("id")?.let { entry.first.append(it) }
                                c.optJSONObject("function")?.let { f ->
                                    f.nullableString("name")?.let { entry.second.append(it) }
                                    f.nullableString("arguments")?.let { entry.third.append(it) }
                                }
                            }
                        }
                    }
                }
                check(finished) { "Flux interrompu avant la fin de la réponse." }
                Completion(text.toString(), ensureCalls(calls.values.map {
                    ToolCall(it.first.toString(), it.second.toString(), JSONObject(it.third.toString().ifBlank { "{}" }))
                }))
            }
        }
    }
}

class AnthropicBackend : LlmBackend {
    override suspend fun models(profile: ConnectionProfile): List<String> = SafeHttp.json(profile, "models").optJSONArray("data")?.objects()
        ?.mapNotNull { it.nullableString("id") }?.sorted() ?: emptyList()
    override suspend fun complete(profile: ConnectionProfile, system: String, messages: List<ConversationMessage>, tools: List<ToolDefinition>, onText: (String) -> Unit): Completion {
        require(profile.model.isNotBlank()) { "Sélectionnez un modèle Claude." }
        val history = JSONArray()
        messages.forEach { m ->
            val content = if (m.protocol == "anthropic" && m.raw != null) m.raw else JSONArray().apply {
                when (m.role) {
                    "tool" -> put(JSONObject().put("type", "tool_result").put("tool_use_id", m.toolId).put("content", m.text))
                    else -> {
                        if (m.text.isNotEmpty()) put(JSONObject().put("type", "text").put("text", m.text))
                        m.calls.forEach { put(JSONObject().put("type", "tool_use").put("id", it.id).put("name", it.name).put("input", it.arguments)) }
                    }
                }
            }
            val role = if (m.role == "assistant") "assistant" else "user"
            val previous = history.optJSONObject(history.length() - 1)
            if (previous?.optString("role") == role) content.objects().forEach { previous.getJSONArray("content").put(it) }
            else history.put(JSONObject().put("role", role).put("content", content))
        }
        val payload = JSONObject().put("model", profile.model).put("system", system).put("messages", history)
            .put("max_tokens", profile.outputTokens).put("stream", profile.streaming)
        if (tools.isNotEmpty() && profile.tools) payload.put("tools", JSONArray(tools.map {
            JSONObject().put("name", it.name).put("description", it.description).put("input_schema", it.parameters)
        }))
        return SafeHttp.execute(profile, SafeHttp.request(profile, "messages", "POST", payload.toString().toRequestBody(SafeHttp.jsonType))) { response ->
            val blocks = sortedMapOf<Int, JSONObject>()
            val arguments = mutableMapOf<Int, StringBuilder>()
            var finished = !profile.streaming
            if (profile.streaming && response.header("Content-Type").orEmpty().contains("text/event-stream")) {
                SafeHttp.readSse(response) { event, data ->
                    val j = JSONObject(data)
                    when (j.optString("type", event)) {
                        "error" -> throw IOException("Le flux Claude a été interrompu.")
                        "content_block_start" -> blocks[j.getInt("index")] = j.getJSONObject("content_block")
                        "content_block_delta" -> {
                            val i = j.getInt("index"); val d = j.getJSONObject("delta")
                            val b = blocks[i] ?: error("Bloc Claude manquant.")
                            when (d.getString("type")) {
                                "text_delta" -> { val t = d.getString("text"); b.put("text", b.optString("text") + t); onText(t) }
                                "input_json_delta" -> arguments.getOrPut(i) { StringBuilder() }.append(d.getString("partial_json"))
                                "thinking_delta" -> b.put("thinking", b.optString("thinking") + d.optString("thinking"))
                                "signature_delta" -> b.put("signature", b.optString("signature") + d.optString("signature"))
                            }
                        }
                        "message_delta" -> requireComplete(j.getJSONObject("delta").nullableString("stop_reason"))
                        "message_stop" -> finished = true
                    }
                }
            } else {
                val j = JSONObject(SafeHttp.limitedBytes(response, 2_000_000).toString(Charsets.UTF_8))
                requireComplete(j.nullableString("stop_reason"))
                j.getJSONArray("content").objects().forEachIndexed { i, b -> blocks[i] = b }
                finished = true
                blocks.values.filter { it.optString("type") == "text" }.forEach { onText(it.optString("text")) }
            }
            check(finished) { "Flux Claude incomplet." }
            arguments.forEach { (i, value) -> blocks.getValue(i).put("input", JSONObject(value.toString())) }
            val raw = JSONArray(blocks.values.toList())
            Completion(blocks.values.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") },
                ensureCalls(blocks.values.filter { it.optString("type") == "tool_use" }.map {
                    ToolCall(it.getString("id"), it.getString("name"), it.getJSONObject("input"))
                }), raw, "anthropic")
        }
    }
}

class ResponsesBackend : LlmBackend {
    override suspend fun models(profile: ConnectionProfile): List<String> = CompatibleBackend().models(profile)
    override suspend fun complete(profile: ConnectionProfile, system: String, messages: List<ConversationMessage>, tools: List<ToolDefinition>, onText: (String) -> Unit): Completion {
        require(profile.model.isNotBlank()) { "Sélectionnez un modèle OpenAI." }
        val input = JSONArray()
        messages.forEach { m ->
            if (m.protocol == "responses" && m.raw != null) m.raw.objects().forEach { input.put(it) }
            else when (m.role) {
                "tool" -> input.put(JSONObject().put("type", "function_call_output").put("call_id", m.toolId).put("output", m.text))
                else -> {
                    if (m.text.isNotEmpty()) input.put(JSONObject().put("role", m.role).put("content", m.text))
                    m.calls.forEach { input.put(JSONObject().put("type", "function_call").put("call_id", it.id).put("name", it.name).put("arguments", it.arguments.toString())) }
                }
            }
        }
        val payload = JSONObject().put("model", profile.model).put("instructions", system).put("input", input)
            .put("store", false).put("stream", profile.streaming).put("max_output_tokens", profile.outputTokens)
            .put("include", JSONArray().put("reasoning.encrypted_content"))
        if (tools.isNotEmpty() && profile.tools) payload.put("tools", JSONArray(tools.map {
            function(it).put("type", "function").put("strict", false)
        }))
        return SafeHttp.execute(profile, SafeHttp.request(profile, "responses", "POST", payload.toString().toRequestBody(SafeHttp.jsonType))) { response ->
            var result: JSONObject? = null
            if (profile.streaming && response.header("Content-Type").orEmpty().contains("text/event-stream")) {
                SafeHttp.readSse(response) { event, data ->
                    if (data != "[DONE]") {
                        val j = JSONObject(data)
                        when (j.optString("type", event)) {
                            "response.output_text.delta" -> onText(j.getString("delta"))
                            "response.completed" -> result = j.getJSONObject("response")
                            "error", "response.failed", "response.incomplete" -> throw IOException("Réponse OpenAI interrompue ou refusée.")
                        }
                    }
                }
            } else result = JSONObject(SafeHttp.limitedBytes(response, 2_000_000).toString(Charsets.UTF_8))
            val j = result ?: error("Flux Responses incomplet.")
            require(j.optString("status", "completed") == "completed") { "Réponse Responses non terminée." }
            val output = j.getJSONArray("output")
            val text = output.objects().filter { it.optString("type") == "message" }.flatMap {
                it.optJSONArray("content")?.objects() ?: emptyList()
            }.filter { it.optString("type") in setOf("output_text", "refusal") }.joinToString("") {
                it.optString("text", it.optString("refusal"))
            }
            if (!profile.streaming) onText(text)
            Completion(text, ensureCalls(output.objects().filter { it.optString("type") == "function_call" }.map {
                ToolCall(it.getString("call_id"), it.getString("name"), parseArguments(it.get("arguments")))
            }), output, "responses")
        }
    }
}

class GeminiBackend : LlmBackend {
    override suspend fun models(profile: ConnectionProfile): List<String> = SafeHttp.json(profile, "models").optJSONArray("models")?.objects()
        ?.filter { it.optJSONArray("supportedGenerationMethods")?.strings()?.contains("generateContent") == true }
        ?.map { it.getString("name").removePrefix("models/") }?.sorted() ?: emptyList()
    override suspend fun complete(profile: ConnectionProfile, system: String, messages: List<ConversationMessage>, tools: List<ToolDefinition>, onText: (String) -> Unit): Completion {
        require(profile.model.matches(Regex("[A-Za-z0-9._-]+"))) { "Identifiant de modèle Gemini invalide." }
        val contents = JSONArray()
        messages.forEach { m ->
            val parts = if (m.protocol == "gemini" && m.raw != null) m.raw else JSONArray().apply {
                if (m.role == "tool") put(JSONObject().put("functionResponse", JSONObject().put("name", m.toolName)
                    .put("response", JSONObject().put("result", m.text)).apply { if (messages.any { previous -> previous.protocol == "gemini" && previous.raw?.objects()?.any { part -> part.optJSONObject("functionCall")?.nullableString("id") == m.toolId } == true }) put("id", m.toolId) }))
                else {
                    if (m.text.isNotEmpty()) put(JSONObject().put("text", m.text))
                    m.calls.forEach { put(JSONObject().put("functionCall", JSONObject().put("name", it.name).put("args", it.arguments))) }
                }
            }
            val role = if (m.role == "assistant") "model" else "user"
            val previous = contents.optJSONObject(contents.length() - 1)
            if (previous?.optString("role") == role) parts.objects().forEach { previous.getJSONArray("parts").put(it) }
            else contents.put(JSONObject().put("role", role).put("parts", parts))
        }
        val payload = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents).put("generationConfig", JSONObject().put("maxOutputTokens", profile.outputTokens).put("temperature", profile.temperature))
        if (tools.isNotEmpty() && profile.tools) payload.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray(tools.map { JSONObject().put("name", it.name).put("description", it.description).put("parametersJsonSchema", it.parameters) }))))
        val suffix = if (profile.streaming) "streamGenerateContent?alt=sse" else "generateContent"
        val path = "models/${profile.model}:$suffix"
        return SafeHttp.execute(profile, SafeHttp.request(profile, path, "POST", payload.toString().toRequestBody(SafeHttp.jsonType))) { response ->
            val parts = JSONArray()
            var finished = false
            fun receive(j: JSONObject) {
                val candidate = j.optJSONArray("candidates")?.optJSONObject(0) ?: throw IOException("Réponse Gemini absente ou refusée.")
                candidate.nullableString("finishReason")?.let { requireComplete(it); finished = true }
                candidate.optJSONObject("content")?.optJSONArray("parts")?.objects()?.forEach { part ->
                    // Preserve opaque thoughtSignature fields exactly; never display hidden reasoning.
                    parts.put(part)
                    if (!part.optBoolean("thought")) part.nullableString("text")?.let(onText)
                }
            }
            if (profile.streaming && response.header("Content-Type").orEmpty().contains("text/event-stream")) {
                SafeHttp.readSse(response) { _, data -> receive(JSONObject(data)) }
            } else { receive(JSONObject(SafeHttp.limitedBytes(response, 2_000_000).toString(Charsets.UTF_8))); finished = true }
            check(finished) { "Flux Gemini incomplet." }
            val calls = parts.objects().mapNotNull { part -> part.optJSONObject("functionCall")?.let {
                ToolCall(it.optString("id", "call_" + UUID.randomUUID().toString().replace("-", "")), it.getString("name"), it.optJSONObject("args") ?: JSONObject())
            } }
            Completion(parts.objects().filterNot { it.optBoolean("thought") }.joinToString("") { it.nullableString("text").orEmpty() }, ensureCalls(calls), parts, "gemini")
        }
    }
}

class OllamaBackend : LlmBackend {
    override suspend fun models(profile: ConnectionProfile): List<String> = SafeHttp.json(profile, "api/tags").optJSONArray("models")?.objects()
        ?.mapNotNull { it.nullableString("name") }?.sorted() ?: emptyList()
    override suspend fun complete(profile: ConnectionProfile, system: String, messages: List<ConversationMessage>, tools: List<ToolDefinition>, onText: (String) -> Unit): Completion {
        require(profile.model.isNotBlank()) { "Sélectionnez un modèle installé sur Ollama." }
        val history = chatMessages(system, messages)
        history.objects().forEach { m ->
            m.optJSONArray("tool_calls")?.objects()?.forEach { call ->
                val f = call.getJSONObject("function"); f.put("arguments", parseArguments(f.get("arguments")))
            }
        }
        messages.forEachIndexed { index, message -> if (message.role == "tool") history.getJSONObject(index + 1).put("tool_name", message.toolName) }
        val payload = JSONObject().put("model", profile.model).put("messages", history).put("stream", profile.streaming)
            .put("options", JSONObject().put("temperature", profile.temperature).put("num_ctx", profile.contextTokens).put("num_predict", profile.outputTokens))
        if (tools.isNotEmpty() && profile.tools) payload.put("tools", JSONArray(tools.map { JSONObject().put("type", "function").put("function", function(it)) }))
        return SafeHttp.execute(profile, SafeHttp.request(profile, "api/chat", "POST", payload.toString().toRequestBody(SafeHttp.jsonType))) { response ->
            val text = StringBuilder(); val calls = mutableListOf<ToolCall>(); var finished = false
            fun receive(j: JSONObject) {
                if (j.has("error")) throw IOException("Ollama a refusé la demande.")
                val message = j.optJSONObject("message") ?: JSONObject()
                message.nullableString("content")?.let { text.append(it); onText(it) }
                message.optJSONArray("tool_calls")?.objects()?.forEach {
                    val f = it.getJSONObject("function")
                    calls.add(ToolCall(it.optString("id", "call_" + UUID.randomUUID().toString().replace("-", "")), f.getString("name"), parseArguments(f.get("arguments"))))
                }
                if (j.optBoolean("done")) { requireComplete(j.nullableString("done_reason")); finished = true }
            }
            if (profile.streaming) {
                val reader = response.body?.charStream()?.buffered() ?: error("Flux vide.")
                var size = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    size += line.length; require(size < 8_000_000) { "Flux trop volumineux." }
                    if (line.isNotBlank()) receive(JSONObject(line))
                }
            } else receive(JSONObject(SafeHttp.limitedBytes(response, 2_000_000).toString(Charsets.UTF_8)))
            check(finished) { "Réponse Ollama incomplète." }
            Completion(text.toString(), ensureCalls(calls))
        }
    }
}
