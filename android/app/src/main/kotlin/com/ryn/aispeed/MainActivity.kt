package com.ryn.aispeed

import android.content.Intent
import android.net.VpnService
import android.os.Build
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.ryn.aispeed.ui.HomeScreen
import com.ryn.aispeed.ui.NetworkScreen
import com.ryn.aispeed.ui.SmartCoreScreen
import com.ryn.aispeed.ui.SettingsScreen
import com.ryn.aispeed.ui.Section
import com.ryn.aispeed.ui.LocalStrings
import com.ryn.aispeed.ui.LanguageStore
import com.ryn.aispeed.ui.RynStrings
import com.ryn.aispeed.ui.RynColors
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
    onSurface = Color(0xFFD1FAE5),
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFECFDF5)
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
        requestNotificationPermissionIfNeeded()

        handleIncomingShareIntent(intent)

        setContent {
            var lang by remember { mutableStateOf(LanguageStore.load(this@MainActivity)) }
            val strings = LanguageStore.stringsFor(lang)
            CompositionLocalProvider(
                LocalStrings provides strings,
                LocalLayoutDirection provides (if (strings.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr)
            ) {
                MaterialTheme(colorScheme = RynDarkColorScheme) {
                    RynMainAppShell(
                        downloadDao = downloadDao,
                        downloadEngine = downloadEngine,
                        networkOptimizer = networkOptimizer,
                        aiAutoManager = aiAutoManager,
                        sharedIncomingUrl = sharedUrlState.value,
                        onConsumeSharedUrl = { sharedUrlState.value = null },
                        language = lang,
                        onLanguageChange = { code ->
                            lang = code
                            LanguageStore.save(this@MainActivity, code)
                        }
                    )
                }
            }
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the service works either way */ }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
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
    language: String,
    onLanguageChange: (String) -> Unit
) {
    val str = LocalStrings.current
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val downloads by downloadDao.observeAll().collectAsState(initial = emptyList())
    var current by remember { mutableStateOf(Section.HOME) }
    var statusBanner by remember { mutableStateOf(str.bannerReady) }
    var autoEnabled by remember { mutableStateOf(aiAutoManager.isMasterEnabled()) }
    var lastLatency by remember { mutableStateOf<LatencyReport?>(null) }
    var dnsEntries by remember { mutableStateOf<List<DnsBenchmarkEntry>>(emptyList()) }
    var isTestingNetwork by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Side effects must not run during composition (they could fire twice on a retried pass).
    // scope.launch keeps the download alive when the effect key flips to null after consumption.
    LaunchedEffect(sharedIncomingUrl) {
        val urlToStart = sharedIncomingUrl ?: return@LaunchedEffect
        current = Section.DOWNLOADS
        onConsumeSharedUrl()
        scope.launch {
            statusBanner = str.bannerCheckingShared
            DownloadForegroundService.ensureRunning(appContext)
            statusBanner = describeDownloadResult(str, downloadEngine.startDownload(urlToStart))
        }
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = current != Section.HOME && !drawerState.isOpen) { current = Section.HOME }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "RYN-AI-Speed",
                    color = RynColors.Accent,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(20.dp)
                )
                Section.entries.forEach { section ->
                    NavigationDrawerItem(
                        label = { Text(section.label(str)) },
                        icon = { Text(section.icon) },
                        selected = current == section,
                        onClick = {
                            current = section
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    ) {
        Scaffold(
            containerColor = RynColors.Bg,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RynColors.Card)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "☰",
                        color = RynColors.Text,
                        fontSize = 24.sp,
                        modifier = Modifier.clickable { scope.launch { drawerState.open() } }
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(current.label(str), color = RynColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (current) {
                    Section.HOME -> HomeScreen(
                        downloadsCount = downloads.size,
                        lastLatency = lastLatency,
                        banner = statusBanner,
                        autoEnabled = autoEnabled,
                        onToggleAuto = { enabled ->
                            autoEnabled = enabled
                            aiAutoManager.setMasterEnabled(enabled)
                            statusBanner = if (enabled) str.bannerAutoOn else str.bannerAutoOff
                        },
                        onGo = { current = it }
                    )

                    Section.DOWNLOADS -> DownloadManagerScreen(
                        downloads = downloads,
                        onAddUrl = { url ->
                            scope.launch {
                                statusBanner = str.bannerCheckingShared
                                DownloadForegroundService.ensureRunning(appContext)
                                statusBanner = describeDownloadResult(str, downloadEngine.startDownload(url))
                            }
                        },
                        onPause = { id ->
                            downloadEngine.pauseDownload(id)
                            statusBanner = str.bannerPaused
                        },
                        onResume = { id ->
                            scope.launch {
                                statusBanner = str.bannerResuming
                                DownloadForegroundService.ensureRunning(appContext)
                                statusBanner = describeDownloadResult(str, downloadEngine.resumeDownload(id))
                            }
                        },
                        onCancel = { id ->
                            scope.launch {
                                downloadEngine.cancelDownload(id)
                                statusBanner = str.bannerCanceled
                            }
                        }
                    )

                    Section.BROWSER -> RynBrowserScreen(
                        onDownloadIntercepted = { url ->
                            current = Section.DOWNLOADS
                            scope.launch {
                                statusBanner = str.bannerCaptured
                                DownloadForegroundService.ensureRunning(appContext)
                                statusBanner = describeDownloadResult(str, downloadEngine.startDownload(url))
                            }
                        }
                    )

                    Section.NETWORK -> NetworkScreen(
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

                    Section.VPN -> VpnScreen()

                    Section.SMART -> SmartCoreScreen(
                        lastLatency = lastLatency,
                        autoEnabled = autoEnabled,
                        onToggleAuto = { enabled ->
                            autoEnabled = enabled
                            aiAutoManager.setMasterEnabled(enabled)
                            statusBanner = if (enabled) str.bannerAutoOn else str.bannerAutoOff
                        }
                    )

                    Section.CHAT -> RynChatScreen(lastLatency = lastLatency)

                    Section.SETTINGS -> SettingsScreen(
                        currentLanguage = language,
                        onLanguageChange = onLanguageChange
                    )
                }
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

private fun describeDownloadResult(str: RynStrings, res: RynResult<DownloadEntity>): String = when (res) {
    is RynResult.Success -> str.downloadDone(res.data.fileName)
    is RynResult.Failure ->
        if (res.code == "DOWNLOAD_INTERRUPTED") str.downloadInterrupted else str.downloadFailed(res.message)
}
