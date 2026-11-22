package com.ryn.aispeed.feature.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ryn.aispeed.core.common.ModuleEvent
import com.ryn.aispeed.core.common.ModuleEventBus
import com.ryn.aispeed.core.common.RynResult
import com.ryn.aispeed.core.common.runIsolated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class LatencyReport(
    val host: String,
    val port: Int,
    val probesSent: Int,
    val probesSucceeded: Int,
    val avgPingMs: Double?,
    val jitterMs: Double?,
    val packetLossPct: Double
)

data class DnsBenchmarkEntry(
    val name: String,
    val resolverIp: String,
    val avgLatencyMs: Double?,
    val reachable: Boolean
)

data class SpeedTestReport(
    val downloadMbps: Double,
    val uploadMbps: Double,
    val pingMs: Double?,
    val jitterMs: Double?,
    val packetLossPct: Double,
    val vpnActive: Boolean
)

data class VpnComparisonDelta(
    val pingDeltaMs: Double?,
    val downloadDeltaMbps: Double,
    val packetLossDeltaPct: Double,
    val summaryRecommendation: String
)

object NetworkMetricsCalculator {
    fun computeStats(host: String, port: Int, probesSent: Int, rttSamplesMs: List<Double>): LatencyReport {
        val sent = probesSent.coerceAtLeast(1)
        val succeeded = rttSamplesMs.size
        val lossPct = ((sent - succeeded).toDouble() / sent.toDouble()) * 100.0
        val avgPing = if (succeeded > 0) rttSamplesMs.average() else null
        val jitter = if (succeeded >= 2) {
            rttSamplesMs.zipWithNext { a, b -> abs(b - a) }.average()
        } else if (succeeded == 1) 0.0 else null

        return LatencyReport(
            host = host,
            port = port,
            probesSent = sent,
            probesSucceeded = succeeded,
            avgPingMs = avgPing,
            jitterMs = jitter,
            packetLossPct = lossPct.coerceIn(0.0, 100.0)
        )
    }

    fun comparePreAndPostVpn(before: SpeedTestReport, after: SpeedTestReport): VpnComparisonDelta {
        val pingDelta = if (before.pingMs != null && after.pingMs != null) after.pingMs - before.pingMs else null
        val dlDelta = after.downloadMbps - before.downloadMbps
        val lossDelta = after.packetLossPct - before.packetLossPct

        val rec = when {
            after.packetLossPct > 5.0 -> "High packet loss detected over VPN; switch to a lower-loss server."
            pingDelta != null && pingDelta > 80.0 -> "VPN adds significant latency (+${pingDelta.toInt()} ms); try a geographically closer node."
            dlDelta > 0.5 && after.packetLossPct <= before.packetLossPct -> "VPN routing improved throughput with stable packet delivery."
            else -> "Connection is within normal variance for your ISP line."
        }
        return VpnComparisonDelta(pingDelta, dlDelta, lossDelta, rec)
    }
}

/**
 * Real Network Diagnostics & Optimizer Engine (zero fabricated metrics).
 */
class RynNetworkOptimizer {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val trustedDnsResolvers = listOf(
        "Cloudflare" to "1.1.1.1",
        "Quad9" to "9.9.9.9",
        "Google" to "8.8.8.8",
        "OpenDNS" to "208.67.222.222"
    )

    suspend fun measureTcpLatency(
        host: String,
        port: Int = 443,
        count: Int = 5,
        timeoutMs: Int = 2000
    ): RynResult<LatencyReport> = runIsolated("PING_TEST_FAILED") {
        withContext(Dispatchers.IO) {
            val samples = mutableListOf<Double>()
            repeat(count.coerceIn(1, 20)) {
                val startNanos = System.nanoTime()
                val ok = runCatching {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, port), timeoutMs)
                    }
                }.isSuccess
                if (ok) {
                    samples += (System.nanoTime() - startNanos) / 1_000_000.0
                }
            }
            NetworkMetricsCalculator.computeStats(host, port, count, samples)
        }
    }

    suspend fun benchmarkDnsResolvers(): RynResult<List<DnsBenchmarkEntry>> = runIsolated("DNS_BENCH_FAILED") {
        withContext(Dispatchers.IO) {
            trustedDnsResolvers.map { (name, ip) ->
                val res = measureTcpLatency(ip, port = 53, count = 3, timeoutMs = 1500)
                when (res) {
                    is RynResult.Success -> DnsBenchmarkEntry(
                        name = name,
                        resolverIp = ip,
                        avgLatencyMs = res.data.avgPingMs,
                        reachable = res.data.probesSucceeded > 0
                    )
                    is RynResult.Failure -> DnsBenchmarkEntry(name, ip, null, false)
                }
            }.sortedWith(compareBy<DnsBenchmarkEntry> { !it.reachable }.thenBy { it.avgLatencyMs ?: Double.MAX_VALUE })
        }
    }

    suspend fun runRealSpeedTest(
        downloadEndpointUrl: String,
        uploadEndpointUrl: String?,
        vpnActive: Boolean
    ): RynResult<SpeedTestReport> = runIsolated("SPEED_TEST_FAILED") {
        withContext(Dispatchers.IO) {
            val latency = (measureTcpLatency("1.1.1.1", 443, 5) as? RynResult.Success)?.data
            val dlStart = System.nanoTime()
            var dlBytes = 0L
            httpClient.newCall(Request.Builder().url(downloadEndpointUrl).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Download benchmark HTTP ${resp.code}")
                val stream = resp.body?.byteStream() ?: throw IllegalStateException("Empty benchmark body")
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buf)
                    if (read == -1) break
                    dlBytes += read
                }
            }
            val dlSec = ((System.nanoTime() - dlStart) / 1_000_000_000.0).coerceAtLeast(0.001)
            val dlMbps = (dlBytes * 8.0) / (dlSec * 1_000_000.0)

            var ulMbps = 0.0
            if (!uploadEndpointUrl.isNullOrBlank()) {
                val payload = ByteArray(512 * 1024) // 512 KB real upload probe
                val ulStart = System.nanoTime()
                val req = Request.Builder()
                    .url(uploadEndpointUrl)
                    .post(payload.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val ulSec = ((System.nanoTime() - ulStart) / 1_000_000_000.0).coerceAtLeast(0.001)
                        ulMbps = (payload.size * 8.0) / (ulSec * 1_000_000.0)
                    }
                }
            }

            SpeedTestReport(
                downloadMbps = dlMbps,
                uploadMbps = ulMbps,
                pingMs = latency?.avgPingMs,
                jitterMs = latency?.jitterMs,
                packetLossPct = latency?.packetLossPct ?: 100.0,
                vpnActive = vpnActive
            )
        }
    }
}

/**
 * Monitors live Wi-Fi <-> Mobile Data <-> VPN connectivity transitions on Android.
 */
class AndroidConnectionMonitor(
    context: Context,
    private val eventBus: ModuleEventBus
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            eventBus.emit(ModuleEvent.NetworkStateChanged(online = online, vpnActive = vpn))
        }

        override fun onLost(network: Network) {
            eventBus.emit(ModuleEvent.NetworkStateChanged(online = false, vpnActive = false))
        }
    }

    fun start() = cm.registerDefaultNetworkCallback(callback)
    fun stop() = runCatching { cm.unregisterNetworkCallback(callback) }
}
