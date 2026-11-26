package com.ryn.aispeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiModelTest {
    private val all = listOf<RynStrings>(ArabicStrings, EnglishStrings)

    @Test
    fun `both languages cover every section and every suggestion`() {
        for (s in all) {
            for (section in Section.entries) {
                assertTrue("${s.languageCode}/${section.name}", section.label(s).isNotBlank())
            }
            assertEquals(4, s.chatSuggestions.size)
            assertTrue(s.vpnEmptyBody.isNotBlank())
        }
    }

    @Test
    fun `direction follows the language`() {
        assertTrue(ArabicStrings.isRtl)
        assertFalse(EnglishStrings.isRtl)
        assertEquals("ar", ArabicStrings.languageCode)
        assertEquals("en", EnglishStrings.languageCode)
    }

    @Test
    fun `parameterised strings embed their values`() {
        assertTrue(ArabicStrings.vpnCountries(3).contains("3"))
        assertTrue(EnglishStrings.vpnCountries(3).contains("3"))
        assertTrue(EnglishStrings.downloadDone("a.zip").contains("a.zip"))
        assertTrue(ArabicStrings.downloadFailed("boom").contains("boom"))
    }

    @Test
    fun `server countries have map positions inside valid coordinates`() {
        for (code in listOf("US", "GB", "DE", "NL", "SE", "JP", "SG", "AU", "BR", "ZA", "AE", "TR")) {
            val geo = CountryGeo.of(code)
            assertNotNull(code, geo)
            assertTrue(geo!!.first in -180.0..180.0)
            assertTrue(geo.second in -56.0..84.0)
        }
        assertNull(CountryGeo.of("XX"))
    }
}
