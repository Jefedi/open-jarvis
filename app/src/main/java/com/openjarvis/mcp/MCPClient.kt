package com.openjarvis.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Legacy JSON-only transport; not yet a conformant Streamable HTTP/SSE MCP client. */
class MCPClient(val server: MCPServer) {
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var isConnected = false
    private var availableTools = emptyList<MCPTool>()

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
                put("params", JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().put("name", "open-jarvis").put("version", "1.0.0"))
                })
            }.toString()
            val request = Request.Builder().url(server.url)
                .post(requestBody.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body?.string() ?: return@withContext false
                val json = JSONObject(body)
                if (json.has("error") || json.optJSONObject("result") == null) return@withContext false
                isConnected = true
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            disconnect()
            false
        }
    }

    suspend fun listTools(): List<MCPTool> = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext emptyList()
        try {
            val requestBody = JSONObject().put("jsonrpc", "2.0").put("id", 2)
                .put("method", "tools/list").toString()
            val request = Request.Builder().url(server.url)
                .post(requestBody.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val tools = JSONObject(body).optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
                availableTools = (0 until tools.length()).map { i ->
                    val tool = tools.getJSONObject(i)
                    MCPTool(tool.getString("name"), tool.optString("description", ""), tool.optJSONObject("inputSchema"))
                }
                availableTools
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun callTool(toolName: String, arguments: JSONObject): String = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext "Error: Not connected"
        try {
            val requestBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 3)
                put("method", "tools/call")
                put("params", JSONObject().put("name", toolName).put("arguments", arguments))
            }.toString()
            val request = Request.Builder().url(server.url)
                .post(requestBody.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Error: HTTP ${response.code}"
                val body = response.body?.string() ?: return@withContext "Error: Empty response"
                val json = JSONObject(body)
                if (json.has("error")) return@withContext "Error: MCP server rejected the request"
                val result = json.optJSONObject("result") ?: return@withContext "Error: Missing result"
                val content = result.optJSONArray("content") ?: return@withContext "Error: Missing content"
                val text = (0 until content.length()).mapNotNull { index ->
                    content.optJSONObject(index)?.takeIf { it.optString("type") == "text" }?.optString("text")
                }.joinToString("\n")
                if (result.optBoolean("isError", false)) "Error: $text" else text
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "Error: MCP request failed"
        }
    }

    fun disconnect() { isConnected = false; availableTools = emptyList() }
    fun isConnected(): Boolean = isConnected
    fun getToolCount(): Int = availableTools.size

    companion object {
        const val HTTP = "http"
        const val SSE = "sse"
    }
}

data class MCPServer(val id: String, val name: String, val url: String, val apiKey: String? = null, val enabled: Boolean = true)
data class MCPTool(val name: String, val description: String, val inputSchema: JSONObject? = null)
