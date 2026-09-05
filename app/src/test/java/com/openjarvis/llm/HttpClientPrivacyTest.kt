package com.openjarvis.llm

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpClientPrivacyTest {
    @Test
    fun sharedClientDoesNotInstallLoggingInterceptors() {
        val interceptors = HttpClient.client.interceptors + HttpClient.client.networkInterceptors
        assertFalse(interceptors.any { it.javaClass.name.contains("LoggingInterceptor") })
    }

    @Test
    fun requestUsesOnlyTheLocalTestServerAndPreservesAuthentication() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            server.start()
            val request = Request.Builder()
                .url(server.url("/v1/test"))
                .header("Authorization", "Bearer fixture-not-a-real-secret")
                .build()
            HttpClient.client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("ok", response.body?.string())
            }
            val received = server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("Bearer fixture-not-a-real-secret", received?.getHeader("Authorization"))
            assertEquals("/v1/test", received?.path)
        }
    }

    @Test
    fun callsHaveAFiniteTimeout() {
        assertTrue(HttpClient.client.callTimeoutMillis > 0)
        assertTrue(HttpClient.client.connectTimeoutMillis > 0)
        assertTrue(HttpClient.client.readTimeoutMillis > 0)
    }
}
