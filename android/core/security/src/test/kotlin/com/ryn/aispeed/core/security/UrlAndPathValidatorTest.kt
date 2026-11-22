package com.ryn.aispeed.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class UrlAndPathValidatorTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun `blocks private, reserved and ula addresses`() {
        listOf("10.0.0.1", "127.0.0.1", "192.168.1.5", "172.16.0.9", "169.254.169.254", "100.64.0.1", "0.0.0.1", "198.18.0.1", "240.0.0.1", "::1", "fd12:3456:789a::1")
            .forEach { assertTrue("should block $it", UrlAndPathValidator.isBlockedAddress(ip(it))) }
    }

    @Test
    fun `allows public addresses`() {
        listOf("8.8.8.8", "1.1.1.1", "93.184.216.34", "2606:4700:4700::1111")
            .forEach { assertFalse("should allow $it", UrlAndPathValidator.isBlockedAddress(ip(it))) }
    }

    @Test
    fun `syntax validation never touches the network and rejects bad schemes`() {
        assertEquals("https://example.invalid/a.zip", UrlAndPathValidator.validateUrlSyntax(" https://example.invalid/a.zip ").toString())
        for (bad in listOf("file:///etc/passwd", "ftp://example.com/x", "javascript:alert(1)", "https:///nohost")) {
            try {
                UrlAndPathValidator.validateUrlSyntax(bad)
                fail("expected rejection of $bad")
            } catch (expected: Exception) {
                // ok
            }
        }
    }

    @Test
    fun `file names are stripped of traversal and reserved names`() {
        assertEquals("passwd", UrlAndPathValidator.sanitizeFileName("../../etc/passwd"))
        assertEquals("_CON.txt", UrlAndPathValidator.sanitizeFileName("CON.txt"))
        assertEquals("download.bin", UrlAndPathValidator.sanitizeFileName("..."))
    }
}
