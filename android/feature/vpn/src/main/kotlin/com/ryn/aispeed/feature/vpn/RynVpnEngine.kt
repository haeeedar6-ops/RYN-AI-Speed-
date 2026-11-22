package com.ryn.aispeed.feature.vpn

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.ryn.aispeed.core.common.ModuleEvent
import com.ryn.aispeed.core.common.ModuleEventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnTunnelState {
    DISCONNECTED,
    CONNECTING,
    VERIFYING_ROUTE,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

data class VpnServerProfile(
    val serverId: String,
    val serverName: String,
    val country: String,
    val region: String,
    val endpoint: String,
    val publicKey: String,
    val serverType: String,       // PRIVATE | COMMUNITY
    val operatorVerified: Boolean,
    val noLogsVerified: Boolean,
    val healthStatus: String,     // HEALTHY | DEGRADED | OFFLINE
    val capacityStatus: String,   // LOW | MEDIUM | HIGH | FULL
    val pingMs: Double?,
    val jitterMs: Double?,
    val packetLossPct: Double?,
    val historicalStabilityPct: Double = 99.0
)

/**
 * Weighted VPN Server Auto-Selector (never picks by geography alone and never trusts unverified community servers).
 */
object VpnServerScorer {
    private val WG_PUBLIC_KEY_REGEX = Regex("^[A-Za-z0-9+/]{43}=$")

    fun isValidWireGuardKey(key: String): Boolean = WG_PUBLIC_KEY_REGEX.matches(key.trim())

    fun isEligible(server: VpnServerProfile): Boolean {
        if (server.healthStatus != "HEALTHY") return false
        if (server.capacityStatus == "FULL") return false
        if (!isValidWireGuardKey(server.publicKey)) return false
        if (server.serverType == "COMMUNITY" && (!server.operatorVerified || !server.noLogsVerified)) {
            return false
        }
        return true
    }

    /**
     * Higher score is better (0.0 .. 100.0).
     * Weights: Ping 35%, Packet Loss 30%, Jitter 15%, Capacity 10%, Stability & Private Bonus 10%.
     */
    fun scoreServer(server: VpnServerProfile): Double {
        if (!isEligible(server)) return -1.0
        val ping = (server.pingMs ?: 250.0).coerceIn(1.0, 500.0)
        val loss = (server.packetLossPct ?: 5.0).coerceIn(0.0, 100.0)
        val jitter = (server.jitterMs ?: 30.0).coerceIn(0.0, 200.0)

        val pingScore = (1.0 - (ping / 500.0)) * 35.0
        val lossScore = (1.0 - (loss / 100.0)) * 30.0
        val jitterScore = (1.0 - (jitter / 200.0)) * 15.0
        val capacityScore = when (server.capacityStatus) {
            "LOW" -> 10.0
            "MEDIUM" -> 7.0
            "HIGH" -> 3.0
            else -> 4.0
        }
        val stabilityScore = (server.historicalStabilityPct.coerceIn(0.0, 100.0) / 100.0) * 7.0
        val privateBonus = if (server.serverType == "PRIVATE") 3.0 else 0.0

        return pingScore + lossScore + jitterScore + capacityScore + stabilityScore + privateBonus
    }

    fun selectBestServer(servers: List<VpnServerProfile>): VpnServerProfile? =
        servers.filter { isEligible(it) }.maxByOrNull { scoreServer(it) }
}

/**
 * Generates strict WireGuard tunnel configs with full DNS & IPv6 leak protection.
 * Private keys stay strictly on-device and are NEVER logged or transmitted to Backend/AI.
 */
object WireGuardConfigBuilder {
    // Strict allow-lists: values are interpolated into a config file, so any CR/LF or extra token
    // could inject additional directives (config injection).
    private val ENDPOINT_REGEX = Regex("^(?:[A-Za-z0-9.-]{1,253}|\\[[0-9A-Fa-f:]+\\]):(?:[1-9][0-9]{0,4})$")
    private val ADDRESS_REGEX = Regex("^[0-9A-Fa-f:.]{2,45}/[0-9]{1,3}$")
    private val DNS_REGEX = Regex("^[0-9A-Fa-f:.]{2,45}$")

    fun buildConfig(
        clientPrivateKeyBase64: String,
        clientAddressCidr: String,
        server: VpnServerProfile,
        dnsResolvers: List<String> = listOf("1.1.1.1", "9.9.9.9")
    ): String {
        require(VpnServerScorer.isValidWireGuardKey(clientPrivateKeyBase64)) { "Invalid client WireGuard private key" }
        require(VpnServerScorer.isValidWireGuardKey(server.publicKey)) { "Invalid server WireGuard public key" }
        require(ENDPOINT_REGEX.matches(server.endpoint)) { "Invalid server endpoint format (host:port required)" }
        require(ADDRESS_REGEX.matches(clientAddressCidr)) { "Invalid client address (CIDR required)" }
        require(dnsResolvers.isNotEmpty() && dnsResolvers.all { DNS_REGEX.matches(it) }) { "Invalid DNS resolver" }

        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $clientPrivateKeyBase64")
            appendLine("Address = $clientAddressCidr")
            appendLine("DNS = ${dnsResolvers.joinToString(", ")}")
            appendLine("MTU = 1280")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${server.publicKey}")
            appendLine("Endpoint = ${server.endpoint}")
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("PersistentKeepalive = 25")
        }
    }
}

/**
 * Android VpnService shell (DNS + IPv6 leak protection routes).
 *
 * IMPORTANT / HONESTY: this class only builds the TUN interface. Nothing here reads or writes packets
 * and no WireGuard backend (com.wireguard.android:tunnel) is integrated yet, so an established tunnel
 * would black-hole ALL device traffic. [establishHardenedTunnel] therefore refuses to run until a real
 * backend is attached ([backendAttached]). `setBlocking(true)` is NOT a kill switch (it only makes
 * the TUN file descriptor blocking); the real kill switch is Android's "Always-on VPN + Block
 * connections without VPN" which is reported by [isOsLockdownKillSwitchActive].
 */
class RynVpnService : VpnService() {
    private var tunnelInterface: ParcelFileDescriptor? = null

    /** Must be set to true only by the P5 WireGuard backend integration once it services the TUN fd. */
    @Volatile
    var backendAttached: Boolean = false

    fun establishHardenedTunnel(
        sessionName: String,
        tunnelIpv4Address: String,
        dnsServers: List<String> = listOf("1.1.1.1", "9.9.9.9"),
        enforceIpv6Blackhole: Boolean = true
    ): ParcelFileDescriptor? {
        check(backendAttached) {
            "WIREGUARD_BACKEND_NOT_INTEGRATED: refusing to route device traffic into an unserviced tunnel"
        }
        val builder = Builder()
            .setSession(sessionName)
            .setMtu(1280)
            .setBlocking(true) // blocking TUN I/O only; this is NOT a kill switch
            .addAddress(tunnelIpv4Address, 32)
            .addRoute("0.0.0.0", 0)

        if (enforceIpv6Blackhole) {
            // Route all IPv6 into the tunnel to prevent IPv6 leaks to physical ISP interface
            builder.addAddress("fd00:7279:6e00::2", 128)
            builder.addRoute("::", 0)
        }

        dnsServers.forEach { builder.addDnsServer(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        tunnelInterface?.close()
        tunnelInterface = builder.establish()
        return tunnelInterface
    }

    fun isOsLockdownKillSwitchActive(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isLockdownEnabled else false

    fun tearDownTunnel() {
        runCatching { tunnelInterface?.close() }
        tunnelInterface = null
    }

    override fun onRevoke() {
        tearDownTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        tearDownTunnel()
        super.onDestroy()
    }
}

class VpnStateController(private val eventBus: ModuleEventBus) {
    private val _state = MutableStateFlow(VpnTunnelState.DISCONNECTED)
    val state: StateFlow<VpnTunnelState> = _state.asStateFlow()

    /**
     * Enforces the only legal state machine. In particular CONNECTED is reachable solely from
     * VERIFYING_ROUTE, so the UI can never claim "connected" without a real route verification.
     * Returns false (and changes nothing) for an illegal transition.
     */
    fun transitionTo(newState: VpnTunnelState): Boolean {
        val current = _state.value
        if (current == newState) return true
        if (newState !in ALLOWED.getValue(current)) return false
        _state.value = newState
        eventBus.emit(
            ModuleEvent.NetworkStateChanged(
                online = true,
                vpnActive = (newState == VpnTunnelState.CONNECTED)
            )
        )
        return true
    }

    private companion object {
        val ALLOWED: Map<VpnTunnelState, Set<VpnTunnelState>> = mapOf(
            VpnTunnelState.DISCONNECTED to setOf(VpnTunnelState.CONNECTING),
            VpnTunnelState.CONNECTING to setOf(VpnTunnelState.VERIFYING_ROUTE, VpnTunnelState.ERROR, VpnTunnelState.DISCONNECTING),
            VpnTunnelState.VERIFYING_ROUTE to setOf(VpnTunnelState.CONNECTED, VpnTunnelState.ERROR, VpnTunnelState.DISCONNECTING),
            VpnTunnelState.CONNECTED to setOf(VpnTunnelState.DISCONNECTING, VpnTunnelState.ERROR),
            VpnTunnelState.DISCONNECTING to setOf(VpnTunnelState.DISCONNECTED, VpnTunnelState.ERROR),
            VpnTunnelState.ERROR to setOf(VpnTunnelState.DISCONNECTED, VpnTunnelState.CONNECTING)
        )
    }
}
