package com.openjarvis.murena

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.TimeUnit

class ProtocolTests {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }
    private fun profile(kind: String, streaming: Boolean = false) = ConnectionProfile(
        id = kind, name = "Test $kind", kind = kind, url = server.url("/v1").toString(), model = "fixture-model",
        secret = "fixture-secret-never-real", allowLocalHttp = true, streaming = streaming, voice = "fixture-voice"
    )
    private fun enqueue(json: JSONObject) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(json.toString())) }
    private fun textResponse(text: String): JSONObject = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "stop").put("message", JSONObject().put("role", "assistant").put("content", text))))
    private fun tool(): ToolDefinition = ToolDefinition("echo", "Echo fixture", schema("text" to "string"))
    private fun events(vararg payloads: JSONObject): String = payloads.joinToString("") { "data: $it\n\n" }
    private val messages = listOf(ConversationMessage("user", "Bonjour"))

    @Test fun profileRepresentationDoesNotExposeCredentials() {
        val p = profile("mistral").copy(headers = mapOf("X-Private" to "header-secret"))
        assertFalse(p.toString().contains(p.secret)); assertFalse(p.toString().contains("header-secret"))
    }
    @Test fun unsafeBaseUrlsAreRejected() {
        listOf("http://127.0.0.1:1234", "https://name:password@example.invalid", "https://example.invalid?api_key=secret", "file:///tmp/model").forEach {
            assertTrue(runCatching { SafeHttp.validateUrl(it, false) }.isFailure)
        }
        assertTrue(SafeHttp.validateUrl("https://example.invalid/v1", false).isHttps)
    }
    @Test fun httpNetworkClassificationRejectsPublicAndMetadataAddresses() {
        assertTrue(SafeHttp.isLocal(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))))
        assertTrue(SafeHttp.isLocal(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))))
        assertFalse(SafeHttp.isLocal(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))))
        assertFalse(SafeHttp.isLocal(InetAddress.getByAddress(byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()))))
    }
    @Test fun mistralCompatibleRequestUsesConfiguredModelAndNativeTools() = runBlocking {
        enqueue(textResponse("Bonjour à vous"))
        val response = CompatibleBackend().complete(profile("mistral"), "Système", messages, listOf(tool()))
        assertEquals("Bonjour à vous", response.text)
        val request = server.takeRequest(); val body = JSONObject(request.body.readUtf8())
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer fixture-secret-never-real", request.getHeader("Authorization"))
        assertEquals("fixture-model", body.getString("model"))
        assertEquals("echo", body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
    }
    @Test fun compatibleStreamingAssemblesToolArgumentsOnlyAfterFinish() = runBlocking {
        val first = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(JSONObject()
            .put("index", 0).put("id", "call1").put("function", JSONObject().put("name", "echo").put("arguments", "{\"text\":")))))))
        val second = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(JSONObject()
            .put("index", 0).put("function", JSONObject().put("arguments", "\"ok\"}"))))).put("finish_reason", "tool_calls")))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events(first, second) + "data: [DONE]\n\n"))
        val response = CompatibleBackend().complete(profile("compatible", true), "system", messages, listOf(tool()))
        assertEquals("ok", response.calls.single().arguments.getString("text")); assertEquals("call1", response.calls.single().id)
    }
    @Test fun unfinishedCompatibleStreamIsRejected() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"))
        assertTrue(runCatching { CompatibleBackend().complete(profile("compatible", true), "system", messages, emptyList()) }.isFailure)
    }
    @Test fun httpErrorDoesNotExposeResponseBodySecrets() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("fixture-secret-never-real private user text"))
        val exception = runCatching { CompatibleBackend().complete(profile("mistral"), "system", messages, emptyList()) }.exceptionOrNull()!!
        assertTrue(exception.message.orEmpty().contains("401")); assertFalse(exception.message.orEmpty().contains("private user text"))
    }
    @Test fun openAiUsesCompletionTokenParameter() = runBlocking {
        enqueue(textResponse("OK"))
        CompatibleBackend().complete(profile("openai"), "system", messages, emptyList())
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(body.has("max_completion_tokens")); assertFalse(body.has("max_tokens")); assertFalse(body.has("temperature"))
    }
    @Test fun claudeNativeToolUseAndOpaqueBlocksArePreserved() = runBlocking {
        val content = JSONArray().put(JSONObject().put("type", "thinking").put("thinking", "opaque thought fixture").put("signature", "opaque-signature"))
            .put(JSONObject().put("type", "text").put("text", "Je propose cet outil."))
            .put(JSONObject().put("type", "tool_use").put("id", "tool1").put("name", "echo").put("input", JSONObject().put("text", "bonjour")))
        enqueue(JSONObject().put("content", content).put("stop_reason", "tool_use"))
        val response = AnthropicBackend().complete(profile("anthropic"), "system", messages, listOf(tool()))
        assertEquals("bonjour", response.calls.single().arguments.getString("text"))
        assertFalse(response.text.contains("opaque thought")); assertEquals("opaque-signature", response.raw!!.getJSONObject(0).getString("signature"))
        val request = server.takeRequest()
        assertEquals("fixture-secret-never-real", request.getHeader("x-api-key")); assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        enqueue(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Fait"))).put("stop_reason", "end_turn"))
        AnthropicBackend().complete(profile("anthropic"), "system", messages + ConversationMessage("assistant", response.text, response.calls, raw = response.raw, protocol = response.protocol) +
            ConversationMessage("tool", "ok", toolId = "tool1", toolName = "echo"), listOf(tool()))
        val next = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("opaque-signature", next.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("signature"))
    }
    @Test fun claudeStreamHandlesNativeJsonDeltas() = runBlocking {
        val stream = events(
            JSONObject().put("type", "content_block_start").put("index", 0).put("content_block", JSONObject().put("type", "tool_use").put("id", "id1").put("name", "echo").put("input", JSONObject())),
            JSONObject().put("type", "content_block_delta").put("index", 0).put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", "{\"text\":\"yes\"}")),
            JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", "tool_use")),
            JSONObject().put("type", "message_stop"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(stream))
        val result = AnthropicBackend().complete(profile("anthropic", true), "system", messages, listOf(tool()))
        assertEquals("yes", result.calls.single().arguments.getString("text"))
    }
    @Test fun responsesApiPreservesStatelessReasoningOutput() = runBlocking {
        val output = JSONArray().put(JSONObject().put("type", "reasoning").put("id", "r1").put("encrypted_content", "encrypted-fixture"))
            .put(JSONObject().put("type", "function_call").put("call_id", "c1").put("name", "echo").put("arguments", "{\"text\":\"OK\"}"))
        enqueue(JSONObject().put("status", "completed").put("output", output))
        val result = ResponsesBackend().complete(profile("responses"), "system", messages, listOf(tool()))
        assertEquals("responses", result.protocol); assertEquals("encrypted-fixture", result.raw!!.getJSONObject(0).getString("encrypted_content"))
        val request = server.takeRequest(); val body = JSONObject(request.body.readUtf8())
        assertEquals("/v1/responses", request.path); assertFalse(body.getBoolean("store")); assertTrue(body.getJSONArray("include").strings().contains("reasoning.encrypted_content"))
    }
    @Test fun geminiPreservesFunctionThoughtSignatureWithoutDisplayingIt() = runBlocking {
        val parts = JSONArray().put(JSONObject().put("functionCall", JSONObject().put("name", "echo").put("args", JSONObject().put("text", "ok"))).put("thoughtSignature", "opaque-signature"))
        enqueue(JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP").put("content", JSONObject().put("parts", parts)))))
        val result = GeminiBackend().complete(profile("gemini"), "system", messages, listOf(tool()))
        assertEquals("opaque-signature", result.raw!!.getJSONObject(0).getString("thoughtSignature")); assertFalse(result.text.contains("opaque"))
        assertEquals("fixture-secret-never-real", server.takeRequest().getHeader("x-goog-api-key"))
    }
    @Test fun ollamaParsesNativeNdjsonAndObjectArguments() = runBlocking {
        val message = JSONObject().put("content", "").put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject().put("name", "echo").put("arguments", JSONObject().put("text", "local")))))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/x-ndjson").setBody(JSONObject().put("message", message).put("done", false).toString() + "\n" + JSONObject().put("done", true).put("done_reason", "stop") + "\n"))
        val result = OllamaBackend().complete(profile("ollama", true), "system", messages, listOf(tool()))
        assertEquals("local", result.calls.single().arguments.getString("text")); assertEquals("/v1/api/chat", server.takeRequest().path)
    }
    @Test fun voxtralTranscriptionUsesMultipartAudioAndFrenchLanguage() = runBlocking {
        enqueue(JSONObject().put("text", "Bonjour tout le monde."))
        val result = ApiTranscription().transcribe(profile("voxtral-stt"), WaveCodec.encode(ByteArray(3200)), "fr-FR")
        assertEquals("Bonjour tout le monde.", result)
        val request = server.takeRequest(); val body = request.body.readUtf8()
        assertEquals("/v1/audio/transcriptions", request.path); assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
        assertTrue(body.contains("name=\"language\"")); assertTrue(body.contains("\r\nfr\r\n")); assertTrue(body.contains("filename=\"commande.wav\""))
    }
    @Test fun voxtralTranscriptionStreamRequiresDoneEvent() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events(
            JSONObject().put("type", "transcription.text.delta").put("text", "Bonjour"), JSONObject().put("type", "transcription.done"))))
        assertEquals("Bonjour", ApiTranscription().transcribe(profile("voxtral-stt", true), WaveCodec.encode(ByteArray(3200)), "fr-FR"))
    }
    @Test fun voxtralTtsJsonResponseIsDecodedAsAudioNotTreatedAsRawWav() = runBlocking {
        val pcm = byteArrayOf(1, 0, 2, 0, 3, 0)
        enqueue(JSONObject().put("audio_data", Base64.getEncoder().encodeToString(WaveCodec.encode(pcm, 24000))))
        val result = ApiSpeech().synthesize(profile("voxtral-tts"), "Bonjour", 1f)
        assertArrayEquals(pcm, result.samples); assertEquals(24000, result.sampleRate)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("fixture-voice", body.getString("voice_id")); assertFalse(body.has("voice")); assertFalse(body.getBoolean("stream"))
    }
    @Test fun voxtralTtsStreamConvertsFloat32LittleEndian() = runBlocking {
        val bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(0f).putFloat(0.5f).putFloat(-1f).array()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events(
            JSONObject().put("type", "speech.audio.delta").put("audio_data", Base64.getEncoder().encodeToString(bytes)), JSONObject().put("type", "speech.audio.done"))))
        val chunks = mutableListOf<PcmAudio>()
        ApiSpeech().stream(profile("voxtral-tts", true), "Bonjour", 1f) { chunks.add(it) }
        val pcm = ByteBuffer.wrap(chunks.single().samples).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, pcm.short.toInt()); assertEquals(16383, pcm.short.toInt()); assertEquals(-32767, pcm.short.toInt())
    }
    @Test fun compatibleTtsAcceptsRawWav() = runBlocking {
        val audio = WaveCodec.encode(byteArrayOf(0, 0, 1, 0), 24000)
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(okio.Buffer().write(audio)))
        assertEquals(4, ApiSpeech().synthesize(profile("openai-tts"), "test", 1f).samples.size)
    }
    @Test fun malformedWavIsRejected() {
        assertTrue(runCatching { WaveCodec.decode(ByteArray(100)) }.isFailure)
        val wav = WaveCodec.encode(ByteArray(20)); assertTrue(runCatching { WaveCodec.decode(wav.copyOf(wav.size - 1)) }.isFailure)
    }
    @Test fun homeAssistantCannotTargetUnlistedEntityOrDevice() = runBlocking {
        val home = HomeAssistantConnection(profile("homeassistant").copy(allowlist = listOf("light.salon")))
        assertFalse(home.allowed("light.chambre")); assertTrue(home.allowed("light.salon"))
        assertTrue(runCatching { home.call("light.salon", "light.turn_on", JSONObject().put("device_id", "other")) }.isFailure)
        assertEquals(0, server.requestCount)
    }
    @Test fun homeAssistantReportsAcceptedRequestAndObservedStateSeparately() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[]"))
        enqueue(JSONObject().put("entity_id", "light.salon").put("state", "off").put("attributes", JSONObject()))
        val home = HomeAssistantConnection(profile("homeassistant").copy(allowlist = listOf("light.salon")))
        val result = home.call("light.salon", "light.turn_on", JSONObject())
        assertTrue(result.getBoolean("request_accepted")); assertEquals("off", result.getJSONObject("observed").getString("state"))
        val request = server.takeRequest(); assertEquals("/v1/api/services/light/turn_on", request.path)
        assertEquals("light.salon", JSONObject(request.body.readUtf8()).getString("entity_id"))
    }
    @Test fun mcpNegotiatesSessionAndEnforcesToolAllowlist() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val j = JSONObject(request.body.clone().readUtf8())
                if (!j.has("id")) return MockResponse().setResponseCode(202)
                val result = when (j.getString("method")) {
                    "initialize" -> JSONObject().put("protocolVersion", "2025-11-25").put("capabilities", JSONObject().put("tools", JSONObject())).put("serverInfo", JSONObject().put("name", "fixture").put("version", "1"))
                    "tools/list" -> JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "echo").put("inputSchema", schema("text" to "string"))))
                    else -> JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "OK")))
                }
                return MockResponse().setHeader("Content-Type", "application/json").setHeader("Mcp-Session-Id", "session-fixture")
                    .setBody(JSONObject().put("jsonrpc", "2.0").put("id", j.get("id")).put("result", result).toString())
            }
        }
        val client = McpConnection(profile("mcp").copy(allowlist = listOf("echo")))
        assertEquals("echo", client.listTools().single().name)
        assertTrue(runCatching { client.callTool("not-allowed", JSONObject()) }.isFailure)
        assertEquals(3, server.requestCount)
        client.callTool("echo", JSONObject().put("text", "ok"))
        assertEquals("initialize", JSONObject(server.takeRequest().body.readUtf8()).getString("method"))
        assertEquals("session-fixture", server.takeRequest().getHeader("Mcp-Session-Id"))
        server.takeRequest()
        assertEquals("2025-11-25", server.takeRequest().getHeader("MCP-Protocol-Version"))
    }
    @Test fun embeddingVectorsAreValidated() = runBlocking {
        enqueue(JSONObject().put("data", JSONArray().put(JSONObject().put("index", 0).put("embedding", JSONArray().put(0.1).put(0.2)))))
        assertArrayEquals(floatArrayOf(0.1f, 0.2f), VectorApi.embed(profile("embeddings"), listOf("bonjour")).single(), 0.0001f)
    }
    @Test fun requestCancellationDoesNotWaitForNetworkTimeout() = runBlocking {
        server.enqueue(MockResponse().setBody(textResponse("late").toString()).setHeadersDelay(3, TimeUnit.SECONDS))
        val task = launch { CompatibleBackend().complete(profile("mistral"), "system", messages, emptyList()) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        withTimeout(1500) { task.cancelAndJoin() }
        assertTrue(task.isCancelled)
    }
}

class ConfirmationAndRoutingTests {
    @Test fun actionNeedsMatchingExplicitApproval() = runTest {
        val gate = ConfirmationGate()
        var executed = false
        val task = launch { if (gate.request("test", "exact arguments")) executed = true }
        val pending = gate.pending.first { it != null }!!
        assertFalse(executed); assertFalse(gate.approve("wrong-id", true)); assertFalse(executed)
        assertTrue(gate.approve(pending.id, true)); task.join(); assertTrue(executed); assertNull(gate.pending.value)
    }
    @Test fun expiryNeverApprovesAutomatically() = runTest {
        val gate = ConfirmationGate()
        assertFalse(gate.request("test", "details", 10)); assertNull(gate.pending.value)
    }
    @Test fun staleApprovalIsRejectedEvenBeforeTimerRuns() = runTest {
        var clock = 100L
        val gate = ConfirmationGate { clock }
        val result = async { gate.request("test", "details", 100) }
        val pending = gate.pending.first { it != null }!!
        clock = 201L; gate.approve(pending.id, true)
        assertFalse(result.await())
    }
    @Test fun cancellationClearsConfirmation() = runTest {
        val gate = ConfirmationGate()
        val task = launch { gate.request("test", "details") }
        gate.pending.first { it != null }; task.cancelAndJoin(); assertNull(gate.pending.value)
    }
    @Test fun extraAndFractionalArgumentsAreRejected() {
        val definition = schema("volume" to "integer")
        assertTrue(runCatching { ArgumentValidation.validate(JSONObject().put("volume", 3.5), definition) }.isFailure)
        assertTrue(runCatching { ArgumentValidation.validate(JSONObject().put("volume", 5).put("other", true), definition) }.isFailure)
        ArgumentValidation.validate(JSONObject().put("volume", 5), definition)
    }
    private val emptyGateway = object : ToolGateway {
        override suspend fun definitions() = emptyList<ToolDefinition>()
        override suspend fun execute(call: ToolCall, providerLabel: String) = error("No tool expected")
    }
    @Test fun fallbackUsesTheSecondProfileRatherThanRepeatingTheFirst() = runBlocking {
        val used = mutableListOf<String>()
        val engine = ConversationEngine { p -> object : LlmBackend {
            override suspend fun models(profile: ConnectionProfile) = emptyList<String>()
            override suspend fun complete(profile: ConnectionProfile, system: String, messages: List<ConversationMessage>, tools: List<ToolDefinition>, onText: (String) -> Unit): Completion {
                used.add(p.id); if (p.id == "first") throw RemoteFailure(429)
                return Completion("réponse du secours")
            }
        } }
        val profiles = listOf(ConnectionProfile(id = "first"), ConnectionProfile(id = "second"))
        val result = engine.run("bonjour", emptyList(), profiles, emptyGateway)
        assertEquals(listOf("first", "second"), used); assertEquals("second", result.provider.id)
    }
    @Test fun noProviderFallbackOrToolReplayAfterAnExecutedOperation() = runBlocking {
        var calls = 0; var operations = 0; val used = mutableListOf<String>()
        val engine = ConversationEngine { p -> object : LlmBackend {
            override suspend fun models(profile: ConnectionProfile) = emptyList<String>()
            override suspend fun complete(profile: ConnectionProfile, system: String, messages: List<ConversationMessage>, tools: List<ToolDefinition>, onText: (String) -> Unit): Completion {
                used.add(p.id)
                if (++calls == 1) return Completion("", listOf(ToolCall("id", "test", JSONObject())))
                throw IOException("lost response")
            }
        } }
        val gateway = object : ToolGateway {
            override suspend fun definitions() = listOf(ToolDefinition("test", "test", schema()))
            override suspend fun execute(call: ToolCall, providerLabel: String): String { operations++; return "accepted" }
        }
        assertTrue(runCatching { engine.run("test", emptyList(), listOf(ConnectionProfile(id = "first"), ConnectionProfile(id = "second")), gateway) }.isFailure)
        assertEquals(1, operations); assertFalse(used.contains("second"))
    }
    @Test fun localCommandsAreParsedWithoutAModelOrNetwork() {
        assertEquals("android_settings", LocalCommands.parse("Ouvre les réglages Wi-Fi")!!.name)
        assertEquals(30, LocalCommands.parse("Volume à 30 %")!!.arguments.getInt("percent"))
        assertNull(LocalCommands.parse("volume à 200 %")); assertNull(LocalCommands.parse("fais n'importe quoi"))
    }
}
