package com.ryn.aispeed

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Environment
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ryn.aispeed.core.common.ModuleEventBus
import com.ryn.aispeed.core.common.RynResult
import com.ryn.aispeed.core.database.DownloadDao
import com.ryn.aispeed.core.database.DownloadEntity
import com.ryn.aispeed.feature.ai.AiAutoManager
import com.ryn.aispeed.feature.browser.ExternalLinkExtractor
import com.ryn.aispeed.feature.downloads.engine.RynDownloadEngine
import com.ryn.aispeed.feature.downloads.ui.DownloadManagerScreen
import com.ryn.aispeed.feature.network.DnsBenchmarkEntry
import com.ryn.aispeed.feature.network.LatencyReport
import com.ryn.aispeed.feature.network.RynNetworkOptimizer
import com.ryn.aispeed.ui.RynBrowserScreen
import com.ryn.aispeed.ui.RynChatScreen
import com.ryn.aispeed.ui.VpnScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private val RynDarkColorScheme = darkColorScheme(
    primary = Color(0xFF10B981),
    onPrimary = Color(0xFF041C14),
    secondary = Color(0xFF059669),
    background = Color(0xFF0B1114),
    surface = Color(0xFF111A1E),
    onBackground = Color(0xFFECFDF5),
    onSurface = Color(0xFFD1FAE5)
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var downloadDao: DownloadDao

    private val eventBus = ModuleEventBus()
    private val networkOptimizer = RynNetworkOptimizer()
    private val aiAutoManager = AiAutoManager(initialEnabled = true)
    private lateinit var downloadEngine: RynDownloadEngine

    private var sharedUrlState = mutableStateOf<String?>(null)
    private var vpnPermissionStatus = mutableStateOf("افتح تبويب VPN لإضافة سيرفراتك والاتصال")

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vpnPermissionStatus.value = if (result.resultCode == RESULT_OK) {
            "تم منح صلاحية VpnService — أضف ملف سيرفر WireGuard للاتصال الفعلي"
        } else {
            "تم رفض صلاحية VPN من النظام"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dlRoot = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(filesDir, "downloads")
        dlRoot.mkdirs()
        // One engine per PROCESS (not per Activity): rotating the screen must not create a second engine
        // that cannot see (or pause) the downloads already running.
        downloadEngine = DownloadEngineHolder.get(downloadDao, eventBus, dlRoot)

        handleIncomingShareIntent(intent)

        setContent {
            MaterialTheme(colorScheme = RynDarkColorScheme) {
                RynMainAppShell(
                    downloadDao = downloadDao,
                    downloadEngine = downloadEngine,
                    networkOptimizer = networkOptimizer,
                    aiAutoManager = aiAutoManager,
                    sharedIncomingUrl = sharedUrlState.value,
                    onConsumeSharedUrl = { sharedUrlState.value = null },
                    vpnStatusText = vpnPermissionStatus.value,
                    onRequestVpnPermission = { requestSystemVpnPermission() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val extracted = ExternalLinkExtractor.extractFirstSafeUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            if (!extracted.isNullOrBlank()) {
                sharedUrlState.value = extracted
            }
        }
    }

    private fun requestSystemVpnPermission() {
        val prepIntent = VpnService.prepare(this)
        if (prepIntent != null) {
            vpnPermissionLauncher.launch(prepIntent)
        } else {
            vpnPermissionStatus.value = "صلاحية VpnService مفعّلة — جاهز لربط سيرفر WireGuard"
        }
    }
}

@Composable
private fun RynMainAppShell(
    downloadDao: DownloadDao,
    downloadEngine: RynDownloadEngine,
    networkOptimizer: RynNetworkOptimizer,
    aiAutoManager: AiAutoManager,
    sharedIncomingUrl: String?,
    onConsumeSharedUrl: () -> Unit,
    vpnStatusText: String,
    onRequestVpnPermission: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val downloads by downloadDao.observeAll().collectAsState(initial = emptyList())
    var selectedTab by remember { mutableIntStateOf(0) }
    var statusBanner by remember { mutableStateOf("النواة المشفرة SQLCipher نشطة") }
    var autoEnabled by remember { mutableStateOf(aiAutoManager.isMasterEnabled()) }
    var lastLatency by remember { mutableStateOf<LatencyReport?>(null) }
    var dnsEntries by remember { mutableStateOf<List<DnsBenchmarkEntry>>(emptyList()) }
    var isTestingNetwork by remember { mutableStateOf(false) }

    // Side effects must not run during composition (they could fire twice on a retried pass).
    // scope.launch keeps the download alive when the effect key flips to null after consumption.
    LaunchedEffect(sharedIncomingUrl) {
        val urlToStart = sharedIncomingUrl ?: return@LaunchedEffect
        selectedTab = 1
        onConsumeSharedUrl()
        scope.launch {
            statusBanner = "جاري فحص الرابط المشارك..."
            statusBanner = describeDownloadResult(downloadEngine.startDownload(urlToStart))
        }
    }

    val tabs = listOf("الرئيسية", "التنزيلات", "المتصفح", "الشبكة/VPN", "RYN AI")

    Scaffold(
        containerColor = Color(0xFF0B1114),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF111A1E)) {
                tabs.forEachIndexed { idx, label ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        icon = {
                            Text(
                                text = when (idx) {
                                    0 -> "⚡"
                                    1 -> "⬇"
                                    2 -> "🌐"
                                    3 -> "🛡"
                                    else -> "🤖"
                                }
                            )
                        },
                        label = { Text(label, maxLines = 1) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> HomeDashboardTab(
                    downloadsCount = downloads.size,
                    lastLatency = lastLatency,
                    vpnStatusText = vpnStatusText,
                    autoEnabled = autoEnabled,
                    statusBanner = statusBanner,
                    onToggleAuto = { enabled ->
                        autoEnabled = enabled
                        aiAutoManager.setMasterEnabled(enabled)
                        statusBanner = if (enabled) "المدير التلقائي الذكي مفعّل" else "تم إيقاف المدير التلقائي فوراً"
                    },
                    onGoToDownloads = { selectedTab = 1 },
                    onGoToNetwork = { selectedTab = 3 }
                )

                1 -> DownloadManagerScreen(
                    downloads = downloads,
                    onAddUrl = { url ->
                        scope.launch {
                            statusBanner = "جاري تنزيل الرابط..."
                            statusBanner = describeDownloadResult(downloadEngine.startDownload(url))
                        }
                    },
                    onPause = { id ->
                        downloadEngine.pauseDownload(id)
                        statusBanner = "تم إيقاف التنزيل مؤقتاً"
                    },
                    onResume = { id ->
                        scope.launch {
                            statusBanner = "جاري استئناف التنزيل..."
                            statusBanner = describeDownloadResult(downloadEngine.resumeDownload(id))
                        }
                    },
                    onCancel = { id ->
                        scope.launch {
                            downloadEngine.cancelDownload(id)
                            statusBanner = "تم إلغاء التنزيل وحذف الملف المؤقت"
                        }
                    }
                )

                2 -> RynBrowserScreen(
                    onDownloadIntercepted = { url ->
                        selectedTab = 1
                        scope.launch {
                            statusBanner = "تم التقاط رابط من المتصفح..."
                            statusBanner = describeDownloadResult(downloadEngine.startDownload(url))
                        }
                    }
                )

                3 -> VpnScreen(
                    lastLatency = lastLatency,
                    dnsEntries = dnsEntries,
                    isTesting = isTestingNetwork,
                    onRunDiagnostics = {
                        if (!isTestingNetwork) {
                            scope.launch {
                                isTestingNetwork = true
                                val pingRes = networkOptimizer.measureTcpLatency("1.1.1.1", 443, 5)
                                if (pingRes is RynResult.Success) lastLatency = pingRes.data
                                val dnsRes = networkOptimizer.benchmarkDnsResolvers()
                                if (dnsRes is RynResult.Success) dnsEntries = dnsRes.data
                                isTestingNetwork = false
                            }
                        }
                    }
                )

                4 -> RynChatScreen(lastLatency = lastLatency)
            }
        }
    }
}

@Composable
private fun HomeDashboardTab(
    downloadsCount: Int,
    lastLatency: LatencyReport?,
    vpnStatusText: String,
    autoEnabled: Boolean,
    statusBanner: String,
    onToggleAuto: (Boolean) -> Unit,
    onGoToDownloads: () -> Unit,
    onGoToNetwork: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "RYN-AI-Speed v1.0",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF10B981)
        )
        Text(text = statusBanner, color = Color(0xFF9CA3AF))

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111A1E)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("حالة الشبكة الحقيقية", color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                val pingVal = lastLatency?.avgPingMs
                Text(
                    text = if (pingVal != null) "Ping: ${pingVal.toInt()} ms • فقدان الحزم: ${lastLatency.packetLossPct.toInt()}%"
                    else "اضغط فحص الشبكة لقياس Ping و DNS فعلياً",
                    color = Color(0xFFECFDF5)
                )
                Text(text = "VPN: $vpnStatusText", color = Color(0xFFD1FAE5))
                Text(text = "سجل التنزيلات المشفر: $downloadsCount ملف", color = Color(0xFFD1FAE5))
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111A1E)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("المدير التلقائي الذكي (AI Auto)", color = Color(0xFFECFDF5), fontWeight = FontWeight.SemiBold)
                    Text("مفتاح إيقاف شامل فوري", color = Color(0xFF9CA3AF), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = autoEnabled, onCheckedChange = onToggleAuto)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onGoToDownloads,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                modifier = Modifier.weight(1f)
            ) {
                Text("مدير التنزيلات", color = Color(0xFF041C14), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onGoToNetwork, modifier = Modifier.weight(1f)) {
                Text("فحص الشبكة", color = Color(0xFF10B981))
            }
        }
    }
}

/** One download engine for the whole process (survives Activity re-creation). */
private object DownloadEngineHolder {
    @Volatile
    private var engine: RynDownloadEngine? = null

    fun get(dao: DownloadDao, bus: ModuleEventBus, root: File): RynDownloadEngine =
        engine ?: synchronized(this) {
            engine ?: RynDownloadEngine(downloadDao = dao, eventBus = bus, rootDownloadDir = root).also { engine = it }
        }
}

private fun describeDownloadResult(res: RynResult<DownloadEntity>): String = when (res) {
    is RynResult.Success -> "اكتمل التنزيل: ${res.data.fileName}"
    is RynResult.Failure ->
        if (res.code == "DOWNLOAD_INTERRUPTED") "تم إيقاف التنزيل" else "فشل التنزيل: ${res.message}"
}
