package com.openjarvis.murena

import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class NetworkBoundaryTest {
    @Test fun explicitHttpOptInStillRejectsPublicIpv4() {
        assertTrue(runCatching { SafeHttp.validateUrl("http://8.8.8.8/v1", true) }.isFailure)
        assertTrue(runCatching { SafeHttp.validateUrl("http://1.1.1.1/v1", true) }.isFailure)
    }
    @Test fun explicitHttpOptInStillRejectsLinkLocalAndPublicIpv6() {
        assertTrue(runCatching { SafeHttp.validateUrl("http://169.254.169.254/latest", true) }.isFailure)
        assertTrue(runCatching { SafeHttp.validateUrl("http://[2001:4860:4860::8888]/v1", true) }.isFailure)
    }
    @Test fun literalPrivateAndLoopbackAddressesRemainAvailableAfterOptIn() {
        listOf("http://127.0.0.1:1234/v1", "http://192.168.1.2:11434", "http://10.0.0.2", "http://[::1]:1234", "http://[fd00::1]:1234").forEach {
            assertEquals("http", SafeHttp.validateUrl(it, true).scheme)
        }
    }
    @Test fun httpsLiteralAddressIsNotSubjectToTheCleartextException() {
        assertTrue(SafeHttp.validateUrl("https://1.1.1.1/v1", false).isHttps)
    }
    @Test fun streamingLineSupportsCrlfAndAnUnterminatedFinalLine() {
        val source = Buffer().writeUtf8("first\r\nlast")
        assertEquals("first", SafeHttp.boundedLine(source, 10))
        assertEquals("last", SafeHttp.boundedLine(source, 10))
        assertNull(SafeHttp.boundedLine(source, 10))
    }
    @Test fun streamingLineRejectsOversizedFramesBeforeParsing() {
        val source = Buffer().writeUtf8("0123456789ABCDE\n")
        assertTrue(runCatching { SafeHttp.boundedLine(source, 10) }.isFailure)
    }
}
