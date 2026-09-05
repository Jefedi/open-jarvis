package com.openjarvis.bridge
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class SocketServerTest {
    @Test fun requestRoundTrip() {
        val request = JSONObject().put("requestId", "abc123").put("cmd", "status")
        assertEquals("status", JSONObject(request.toString()).getString("cmd"))
        assertEquals("abc123", JSONObject(request.toString()).getString("requestId"))
    }
    @Test fun specialCharactersRemainData() {
        val text = "hello\nworld\"test\tvalue"
        assertEquals(text, JSONObject(JSONObject().put("cmd", text).toString()).getString("cmd"))
    }
}
