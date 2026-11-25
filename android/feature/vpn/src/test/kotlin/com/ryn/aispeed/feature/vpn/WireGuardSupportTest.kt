package com.ryn.aispeed.feature.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WireGuardSupportTest {
    private val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private fun conf(
        extraInterface: String = "",
        extraPeer: String = "",
        endpoint: String = "vpn.example.com:51820",
        allowed: String = "0.0.0.0/0, ::/0"
    ) = """
        [Interface]
        PrivateKey = $key
        Address = 10.2.0.2/32
        DNS = 10.2.0.1
        $extraInterface

        [Peer]
        PublicKey = $key
        AllowedIPs = $allowed
        Endpoint = $endpoint
        PersistentKeepalive = 25
        $extraPeer
    """.trimIndent()

    private fun assertRejected(text: String) {
        try {
            WireGuardConfigParser.parse(text)
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `valid config is parsed`() {
        val parsed = WireGuardConfigParser.parse(conf())
        assertEquals("vpn.example.com", parsed.endpointHost)
        assertEquals(51820, parsed.endpointPort)
        assertTrue(parsed.fullTunnel)
    }

    @Test
    fun `split tunnel is detected`() {
        assertFalse(WireGuardConfigParser.parse(conf(allowed = "10.0.0.0/8")).fullTunnel)
    }

    @Test
    fun `scripts and extra peers are rejected`() {
        assertRejected(conf(extraInterface = "PostUp = rm -rf /"))
        assertRejected(conf(extraInterface = "PreUp = curl evil"))
        assertRejected(conf() + "\n[Peer]\nPublicKey = $key\nEndpoint = a.b:1\nAllowedIPs = 0.0.0.0/0")
        assertRejected(conf(endpoint = "no-port"))
        assertRejected(conf(endpoint = "host:70000"))
        assertRejected(conf(endpoint = "ho st:51820"))
        assertRejected("")
        assertRejected("PrivateKey = $key")
    }

    @Test
    fun `country is guessed from file names and has flags`() {
        assertEquals("US", CountryCatalog.guessFromName("us-nyc-wg-001.conf"))
        assertEquals("SE", CountryCatalog.guessFromName("mullvad_se_sto.conf"))
        assertEquals(CountryCatalog.UNKNOWN, CountryCatalog.guessFromName("myserver.conf"))
        assertEquals("🇺🇸", CountryCatalog.flagEmoji("US"))
        assertEquals("🌐", CountryCatalog.flagEmoji("XX"))
        assertEquals("ألمانيا", CountryCatalog.nameAr("DE"))
    }

    @Test
    fun `trace parser reads ip and country`() {
        val info = TraceParser.parse("fl=1\nip=203.0.113.9\nloc=nl\nwarp=off\n")
        assertNotNull(info)
        assertEquals("203.0.113.9", info!!.ip)
        assertEquals("NL", info.countryCode)
        assertNull(TraceParser.parse("garbage"))
    }
}
