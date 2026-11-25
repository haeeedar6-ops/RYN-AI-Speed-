package com.ryn.aispeed.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserInputTest {
    @Test
    fun `addresses and searches are resolved safely`() {
        assertEquals("https://example.com", BrowserInput.resolve("example.com"))
        assertEquals("https://example.com/a", BrowserInput.resolve(" http://example.com/a "))
        assertEquals("https://duckduckgo.com/?q=hello+world", BrowserInput.resolve("hello world"))
        assertEquals("https://duckduckgo.com/?q=kotlin", BrowserInput.resolve("kotlin"))
        assertEquals(BrowserInput.HOME_URL, BrowserInput.resolve("   "))
    }
}
