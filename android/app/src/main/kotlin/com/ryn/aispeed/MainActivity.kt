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
import com.ryn.aispeed.feature.ai.AiAutoManager
import com.ryn.aispeed.feature.ai.LocalAiRulesEngine
import com.ryn.aispeed.feature.browser.ExternalLinkExtractor
import com.ryn.aispeed.feature.browser.HardenedWebViewConfigurator
import com.ryn.aispeed.feature.downloads.engine.RynDownloadEngine
import com.ryn.aispeed.feature.downloads.ui.DownloadManagerScreen
import com.ryn.aispeed.feature.network.DnsBenchmarkEntry
import com.ryn.aispeed.feature.network.LatencyReport
import com.ryn.aispeed.feature.network.RynNetworkOptimizer
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
    private var vpnPermissionStatus = mutableStateOf("جاهز (يتطلب إعداد سيرفر WireGuard)")

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
        downloadEngine = RynDownloadEngine(
            downloadDao = downloadDao,
            eventBus = eventBus,
            rootDownloadDir = dlRoot
        )

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

    if (sharedIncomingUrl != null) {
        selectedTab = 1
        val urlToStart = sharedIncomingUrl
        onConsumeSharedUrl()
        scope.launch {
            statusBanner = "جاري فحص الرابط المشارك..."
            when (val res = downloadEngine.startDownload(urlToStart)) {
                is RynResult.Success -> statusBanner = "اكتمل التنزيل: ${res.data.fileName}"
                is RynResult.Failure -> statusBanner = "خطأ تنزيل: ${res.message}"
            }
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
                            when (val res = downloadEngine.startDownload(url)) {
                                is RynResult.Success -> statusBanner = "اكتمل التنزيل بنجاح: ${res.data.fileName}"
                                is RynResult.Failure -> statusBanner = "فشل التنزيل: ${res.message}"
                            }
                        }
                    },
                    onPause = { statusBanner = "تم طلب إيقاف مؤقت للتنزيل" },
                    onResume = { statusBanner = "تم طلب استئناف التنزيل" }
                )

                2 -> BuiltInBrowserTab(
                    onDownloadIntercepted = { url ->
                        selectedTab = 1
                        scope.launch {
                            statusBanner = "تم التقاط رابط من المتصفح..."
                            when (val res = downloadEngine.startDownload(url)) {
                                is RynResult.Success -> statusBanner = "اكتمل تنزيل المتصفح: ${res.data.fileName}"
                                is RynResult.Failure -> statusBanner = "خطأ تنزيل: ${res.message}"
                            }
                        }
                    }
                )

                3 -> NetworkAndVpnTab(
                    lastLatency = lastLatency,
                    dnsEntries = dnsEntries,
                    isTesting = isTestingNetwork,
                    vpnStatusText = vpnStatusText,
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
                    },
                    onRequestVpnPermission = onRequestVpnPermission
                )

                4 -> AiAssistantTab(lastLatency = lastLatency)
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

@Composable
private fun BuiltInBrowserTab(onDownloadIntercepted: (String) -> Unit) {
    var addressInput by remember { mutableStateOf("https://duckduckgo.com") }
    var currentUrl by remember { mutableStateOf("https://duckduckgo.com") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111A1E))
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = addressInput,
                onValueChange = { addressInput = it },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    val trimmed = addressInput.trim()
                    val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
                    currentUrl = normalized
                    webViewRef?.loadUrl(normalized)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
            ) {
                Text("فتح", color = Color(0xFF041C14))
            }
            OutlinedButton(onClick = { HardenedWebViewConfigurator.wipeAllBrowsingData(webViewRef) }) {
                Text("مسح", color = Color(0xFF10B981))
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    HardenedWebViewConfigurator.applySecuritySettings(this)
                    webViewClient = HardenedWebViewConfigurator.createHardenedWebViewClient(
                        onPageChanged = { url, _ -> addressInput = url },
                        onBlockedDangerousUrl = {}
                    )
                    HardenedWebViewConfigurator.attachDownloadInterceptor(this) { dl ->
                        if (dl.canEngineHandle) onDownloadIntercepted(dl.url)
                    }
                    loadUrl(currentUrl)
                    webViewRef = this
                }
            }
        )
    }
}

@Composable
private fun NetworkAndVpnTab(
    lastLatency: LatencyReport?,
    dnsEntries: List<DnsBenchmarkEntry>,
    isTesting: Boolean,
    vpnStatusText: String,
    onRunDiagnostics: () -> Unit,
    onRequestVpnPermission: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("تشخيص الشبكة و VPN", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
        }
        item {
            Button(
                onClick = onRunDiagnostics,
                enabled = !isTesting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (isTesting) "جاري الفحص الحقيقي..." else "تشغيل فحص Ping و DNS الحقيقي",
                    color = Color(0xFF041C14),
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (lastLatency != null) {
            item {
                val ping = lastLatency.avgPingMs
                val jitter = lastLatency.jitterMs
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111A1E)), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("الهدف: ${lastLatency.host}:${lastLatency.port}", color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                        Text("Ping: ${ping?.toInt() ?: -1} ms • Jitter: ${jitter?.toInt() ?: 0} ms", color = Color(0xFFECFDF5))
                        Text("فقدان الحزم: ${lastLatency.packetLossPct.toInt()}%", color = Color(0xFFD1FAE5))
                    }
                }
            }
        }
        items(dnsEntries) { entry ->
            val ms = entry.avgLatencyMs
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111A1E)), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${entry.name} (${entry.resolverIp})", color = Color(0xFFECFDF5))
                    Text(
                        if (entry.reachable && ms != null) "${ms.toInt()} ms" else "غير متاح",
                        color = if (entry.reachable) Color(0xFF10B981) else Color(0xFFEF4444)
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111A1E)), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("حماية WireGuard VPN + Kill Switch", color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                    Text(vpnStatusText, color = Color(0xFFECFDF5))
                    OutlinedButton(onClick = onRequestVpnPermission, modifier = Modifier.fillMaxWidth()) {
                        Text("تفعيل صلاحية VpnService للنظام", color = Color(0xFF10B981))
                    }
                }
            }
        }
    }
}

@Composable
private fun AiAssistantTab(lastLatency: LatencyReport?) {
    val messages = remember {
        mutableStateListOf("RYN AI: أهلاً بك! اكتب «افحص الإنترنت» أو «ليش التنزيل بطيء؟» للتشخيص المحلي الفوري.")
    }
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("مساعد RYN الذكي (محلي Offline)", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { msg ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111A1E)), modifier = Modifier.fillMaxWidth()) {
                    Text(msg, color = Color(0xFFECFDF5), modifier = Modifier.padding(12.dp))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("اكتب أمرك هنا...") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    val q = input.trim()
                    if (q.isNotEmpty()) {
                        messages.add("أنت: $q")
                        val diag = LocalAiRulesEngine.diagnoseNetworkOffline(
                            pingMs = lastLatency?.avgPingMs,
                            jitterMs = lastLatency?.jitterMs,
                            packetLossPct = lastLatency?.packetLossPct ?: 0.0
                        )
                        messages.add("RYN AI: ${diag.titleAr} — التوصية: ${diag.recommendedConnections} اتصالات تنزيل. (${diag.suggestedAction})")
                        input = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
            ) {
                Text("إرسال", color = Color(0xFF041C14), fontWeight = FontWeight.Bold)
            }
        }
    }
}
