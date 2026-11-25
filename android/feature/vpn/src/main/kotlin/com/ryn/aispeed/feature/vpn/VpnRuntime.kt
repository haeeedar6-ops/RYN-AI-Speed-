package com.ryn.aispeed.feature.vpn

import android.content.Context
import android.os.SystemClock
import com.ryn.aispeed.core.security.KeystoreKeyManager
import com.ryn.aispeed.core.security.LogRedactor
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class VpnProfile(
    val id: String,
    val name: String,
    val countryCode: String,
    val endpoint: String,
    val fullTunnel: Boolean
)

/** Imported WireGuard files. The private key never touches disk unencrypted (Android Keystore AES-GCM). */
class VpnProfileStore(context: Context, private val keys: KeystoreKeyManager) {
    private val dir = File(context.applicationContext.filesDir, "vpn").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val lock = Any()

    fun list(): List<VpnProfile> = synchronized(lock) { readIndex() }

    fun importConfig(displayName: String, text: String): VpnProfile = synchronized(lock) {
        val parsed = WireGuardConfigParser.parse(text)
        val id = UUID.randomUUID().toString()
        File(dir, "$id.enc").writeBytes(keys.seal(text.toByteArray(Charsets.UTF_8)))
        val cleanName = displayName.removeSuffix(".conf").replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(60).ifBlank { "server" }
        val profile = VpnProfile(
            id = id,
            name = cleanName,
            countryCode = CountryCatalog.guessFromName(displayName),
            endpoint = "${parsed.endpointHost}:${parsed.endpointPort}",
            fullTunnel = parsed.fullTunnel
        )
        writeIndex(readIndex() + profile)
        profile
    }

    fun readConfig(id: String): String? = synchronized(lock) {
        if (!UUID_REGEX.matches(id)) return@synchronized null
        val file = File(dir, "$id.enc")
        if (file.exists()) String(keys.unseal(file.readBytes()), Charsets.UTF_8) else null
    }

    fun delete(id: String) {
        synchronized(lock) {
            if (!UUID_REGEX.matches(id)) return
            File(dir, "$id.enc").delete()
            writeIndex(readIndex().filter { it.id != id })
        }
    }

    private fun readIndex(): List<VpnProfile> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText(Charsets.UTF_8))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                VpnProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    countryCode = o.getString("country"),
                    endpoint = o.getString("endpoint"),
                    fullTunnel = o.optBoolean("full", true)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(items: List<VpnProfile>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("country", it.countryCode)
                    .put("endpoint", it.endpoint).put("full", it.fullTunnel)
            )
        }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(arr.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private companion object {
        val UUID_REGEX = Regex("^[0-9a-fA-F-]{36}$")
    }
}

/** Thin wrapper over the official WireGuard userspace backend (GoBackend). */
class RynWireGuardManager(context: Context) {
    private val backend = GoBackend(context.applicationContext)
    private val tunnel = RynTunnel()

    suspend fun connect(configText: String) {
        withContext(Dispatchers.IO) {
            val config = Config.parse(ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)))
            backend.setState(tunnel, Tunnel.State.UP, config)
        }
    }

    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
        }
    }

    fun isUp(): Boolean = runCatching { backend.getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)

    private class RynTunnel : Tunnel {
        override fun getName(): String = "ryn0"
        override fun onStateChange(newState: Tunnel.State) {
            // State is read through backend.getState(); nothing to do here.
        }
    }
}

/** Real end-to-end check: asks Cloudflare which IP/country our traffic currently exits from. */
object PublicIpProbe {
    suspend fun probe(): ExitInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("https://1.1.1.1/cdn-cgi/trace").openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 6_000
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode == 200) {
                    TraceParser.parse(conn.inputStream.bufferedReader().use { it.readText() })
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            null
        }
    }
}

sealed interface VpnUiState {
    data object Disconnected : VpnUiState
    data class Connecting(val label: String) : VpnUiState
    data class Connected(
        val name: String,
        val countryCode: String,
        val ip: String?,
        val exitCountry: String?,
        val latencyMs: Long?,
        val warning: String?
    ) : VpnUiState
    data class Failed(val message: String) : VpnUiState
}

/**
 * Connect / auto-connect / disconnect. "Connected" is only reported after a REAL request through the
 * tunnel succeeded (plan rule: never claim a VPN connection without a real connection test).
 */
class VpnController(
    private val store: VpnProfileStore,
    private val wireGuard: RynWireGuardManager
) {
    private val _state = MutableStateFlow<VpnUiState>(VpnUiState.Disconnected)
    val state: StateFlow<VpnUiState> = _state.asStateFlow()
    private val mutex = Mutex()

    init {
        if (wireGuard.isUp()) {
            _state.value = VpnUiState.Connected("VPN", CountryCatalog.UNKNOWN, null, null, null, null)
        }
    }

    suspend fun connect(profile: VpnProfile): Boolean = mutex.withLock { connectLocked(profile) }

    /** Tries the given servers one by one (up to [MAX_AUTO_ATTEMPTS]) and keeps the first that really works. */
    suspend fun connectAuto(profiles: List<VpnProfile>): Boolean = mutex.withLock {
        if (profiles.isEmpty()) {
            _state.value = VpnUiState.Failed("لا توجد سيرفرات. أضف ملفات WireGuard أولاً")
            return@withLock false
        }
        for (profile in profiles.take(MAX_AUTO_ATTEMPTS)) {
            if (connectLocked(profile)) return@withLock true
        }
        false
    }

    suspend fun disconnect() {
        mutex.withLock {
            runCatching { wireGuard.disconnect() }
            _state.value = VpnUiState.Disconnected
        }
    }

    private suspend fun connectLocked(profile: VpnProfile): Boolean {
        _state.value = VpnUiState.Connecting("${CountryCatalog.nameAr(profile.countryCode)} — ${profile.name}")
        val before = PublicIpProbe.probe()
        val config = store.readConfig(profile.id)
        if (config == null) {
            _state.value = VpnUiState.Failed("ملف السيرفر غير موجود")
            return false
        }
        try {
            wireGuard.connect(config)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            _state.value = VpnUiState.Failed(LogRedactor.redact(e.message ?: "تعذّر تشغيل النفق").take(160))
            return false
        }

        val started = SystemClock.elapsedRealtime()
        var exit: ExitInfo? = null
        for (attempt in 1..VERIFY_ATTEMPTS) {
            exit = PublicIpProbe.probe()
            if (exit != null) break
            delay(1_500L)
        }
        val latency = SystemClock.elapsedRealtime() - started
        if (exit == null) {
            runCatching { wireGuard.disconnect() }
            _state.value = VpnUiState.Failed("النفق اشتغل لكن اختبار الاتصال فشل (السيرفر لا يرد)")
            return false
        }
        val warning = if (before != null && before.ip == exit.ip) {
            "عنوان IP لم يتغيّر، تحقق من AllowedIPs في ملف السيرفر"
        } else {
            null
        }
        _state.value = VpnUiState.Connected(
            name = profile.name,
            countryCode = profile.countryCode,
            ip = exit.ip,
            exitCountry = exit.countryCode,
            latencyMs = latency,
            warning = warning
        )
        return true
    }

    private companion object {
        const val MAX_AUTO_ATTEMPTS = 4
        const val VERIFY_ATTEMPTS = 4
    }
}
