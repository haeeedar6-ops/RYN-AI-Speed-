package com.ryn.aispeed.ui

import android.content.Context
import androidx.compose.runtime.compositionLocalOf

/** All user-visible text of the unified UI, in two languages. Switching language needs no restart. */
interface RynStrings {
    val languageCode: String
    val isRtl: Boolean
    val languageName: String

    // navigation
    val navHome: String
    val navDownloads: String
    val navBrowser: String
    val navNetwork: String
    val navVpn: String
    val navSmartCore: String
    val navChat: String
    val navSettings: String
    val tagline: String

    // common
    val save: String
    val cancel: String

    // banners
    val bannerReady: String
    val bannerCheckingShared: String
    val bannerPaused: String
    val bannerResuming: String
    val bannerCanceled: String
    val bannerCaptured: String
    val bannerAutoOn: String
    val bannerAutoOff: String
    fun downloadDone(name: String): String
    val downloadInterrupted: String
    fun downloadFailed(message: String): String

    // home
    val mapCaption: String
    val goVpn: String
    val goDownloads: String
    val goNetwork: String
    fun statDownloads(n: Int): String
    fun statPing(ms: Int, lossPct: Int): String
    val statNoTest: String
    fun statServers(n: Int): String
    val autoTitle: String
    val autoHint: String

    // vpn
    val vpnProtected: String
    val vpnUnprotected: String
    val vpnUnprotectedSub: String
    val vpnConnectingTitle: String
    val vpnFailedTitle: String
    val vpnQuickConnect: String
    val vpnDisconnect: String
    val vpnConnectingBtn: String
    val vpnAddFirst: String
    val vpnPermissionDenied: String
    fun vpnCountries(n: Int): String
    val vpnAddServer: String
    val vpnEmptyTitle: String
    val vpnEmptyBody: String
    fun vpnServersCount(n: Int): String
    val vpnConnect: String
    val vpnDelete: String
    val vpnKillTitle: String
    val vpnKillBody: String
    val vpnKillButton: String
    fun vpnExit(country: String, ms: Long): String
    fun vpnImported(n: Int): String
    fun vpnImportFailed(msg: String): String
    fun vpnImportPartial(ok: Int, failed: Int, msg: String): String

    // network
    val netTitle: String
    val netRun: String
    val netRunning: String
    fun netResult(pingMs: Int, jitterMs: Int, lossPct: Int): String
    val netNote: String
    val dnsUnavailable: String

    // smart core
    val scTitle: String
    val scIntro: String
    val scNoData: String
    val scAnalysis: String
    fun scRecommendation(connections: Int): String
    val scLevelsTitle: String
    val scLevelRead: String
    val scLevelSafe: String
    val scLevelProtected: String
    val scManualWins: String

    // chat
    val chatGreeting1: String
    val chatGreeting2: String
    val chatSuggestions: List<String>
    val chatPlaceholder: String
    val chatTyping: String
    val chatNoKeyBanner: String
    fun chatOfflineReply(title: String, connections: Int): String
    val chatSettingsTitle: String
    val chatKeyHelp: String
    val chatKeyLabel: String
    val chatModelLabel: String
    val promptLanguage: String

    // notifications
    val notifChannelName: String
    fun notifProgress(count: Int, percent: Int): String

    // browser
    val browserPlaceholder: String

    // settings
    val settingsTitle: String
    val settingsLanguage: String
    val settingsAbout: String
}

object ArabicStrings : RynStrings {
    override val languageCode = "ar"
    override val isRtl = true
    override val languageName = "العربية"
    override val navHome = "الرئيسية"
    override val navDownloads = "مدير التنزيلات"
    override val navBrowser = "المتصفح"
    override val navNetwork = "تحسين الإنترنت"
    override val navVpn = "VPN"
    override val navSmartCore = "العقل الذكي"
    override val navChat = "الدردشة"
    override val navSettings = "الإعدادات"
    override val tagline = "تنزيل أسرع، اتصال أذكى، حماية أقوى"
    override val save = "حفظ"
    override val cancel = "إلغاء"
    override val bannerReady = "النواة المشفرة نشطة"
    override val bannerCheckingShared = "جاري فحص الرابط المشارك..."
    override val bannerPaused = "تم إيقاف التنزيل مؤقتاً"
    override val bannerResuming = "جاري استئناف التنزيل..."
    override val bannerCanceled = "تم إلغاء التنزيل وحذف الملف المؤقت"
    override val bannerCaptured = "تم التقاط رابط من المتصفح..."
    override val bannerAutoOn = "المدير التلقائي الذكي مفعّل"
    override val bannerAutoOff = "تم إيقاف المدير التلقائي فوراً"
    override fun downloadDone(name: String) = "اكتمل التنزيل: $name"
    override val downloadInterrupted = "تم إيقاف التنزيل"
    override fun downloadFailed(message: String) = "فشل التنزيل: $message"
    override val mapCaption = "السيرفرات المتاحة"
    override val goVpn = "إدارة الـVPN"
    override val goDownloads = "مدير التنزيلات"
    override val goNetwork = "فحص الشبكة"
    override fun statDownloads(n: Int) = "$n تنزيل"
    override fun statPing(ms: Int, lossPct: Int) = "$ms ms • $lossPct% فقدان"
    override val statNoTest = "لا يوجد فحص"
    override fun statServers(n: Int) = "$n سيرفر"
    override val autoTitle = "المدير التلقائي الذكي"
    override val autoHint = "مفتاح إيقاف شامل فوري"
    override val vpnProtected = "محمي"
    override val vpnUnprotected = "غير محمي"
    override val vpnUnprotectedSub = "اتصالك مكشوف. اضغط اتصال سريع"
    override val vpnConnectingTitle = "جاري الاتصال..."
    override val vpnFailedTitle = "فشل الاتصال"
    override val vpnQuickConnect = "⚡ اتصال سريع (تلقائي)"
    override val vpnDisconnect = "قطع الاتصال"
    override val vpnConnectingBtn = "جاري الاتصال..."
    override val vpnAddFirst = "أضف سيرفرات WireGuard أولاً (زر الإضافة بالأسفل)"
    override val vpnPermissionDenied = "تم رفض صلاحية VPN من النظام"
    override fun vpnCountries(n: Int) = "الدول ($n)"
    override val vpnAddServer = "＋ إضافة سيرفر (.conf)"
    override val vpnEmptyTitle = "لا توجد سيرفرات بعد"
    override val vpnEmptyBody = "الـVPN يحتاج سيرفرات WireGuard حقيقية بعدة دول. خياراتك: (1) اشتراك بمزوّد يعطيك ملفات WireGuard مثل Mullvad، أو (2) سيرفرك الخاص. حمّل ملفات .conf ثم اضغط «إضافة سيرفر». سمِّ الملف بكود الدولة (us-nyc.conf أو se-sto.conf) ليظهر العلم والاسم."
    override fun vpnServersCount(n: Int) = "$n سيرفر"
    override val vpnConnect = "اتصال"
    override val vpnDelete = "حذف"
    override val vpnKillTitle = "قاطع الاتصال (Kill Switch)"
    override val vpnKillBody = "أندرويد يوفّر قاطعاً حقيقياً: فعّل «VPN دائم التشغيل» و«حظر الاتصالات بدون VPN» من إعدادات النظام."
    override val vpnKillButton = "فتح إعدادات VPN في النظام"
    override fun vpnExit(country: String, ms: Long) = "الخروج من: $country  •  زمن التحقق: $ms ms"
    override fun vpnImported(n: Int) = "تمت إضافة $n سيرفر"
    override fun vpnImportFailed(msg: String) = "فشل الاستيراد: $msg"
    override fun vpnImportPartial(ok: Int, failed: Int, msg: String) = "تمت إضافة $ok وفشل $failed: $msg"
    override val netTitle = "فحص الشبكة الحقيقي"
    override val netRun = "فحص Ping و DNS"
    override val netRunning = "جاري الفحص..."
    override fun netResult(pingMs: Int, jitterMs: Int, lossPct: Int) = "Ping: $pingMs ms • Jitter: $jitterMs ms • فقدان الاتصال: $lossPct%"
    override val netNote = "القياس يتم عبر اتصالات TCP حقيقية (وليس ICMP)، وكل النتائج من جهازك فقط."
    override val dnsUnavailable = "غير متاح"
    override val scTitle = "العقل الذكي (Smart Core)"
    override val scIntro = "يحلّل قياساتك الحقيقية ويقترح قرارات واضحة. لا ينفّذ أي شي حساس بدون موافقتك، واختيارك اليدوي يتفوق دائماً."
    override val scNoData = "لا توجد قياسات بعد. شغّل فحص الشبكة من صفحة «تحسين الإنترنت»."
    override val scAnalysis = "التحليل الحالي (قراءة فقط)"
    override fun scRecommendation(connections: Int) = "التوصية: $connections اتصالات تنزيل"
    override val scLevelsTitle = "مستويات الصلاحية"
    override val scLevelRead = "قراءة: قياسات وتقارير، بدون تغيير"
    override val scLevelSafe = "فعل آمن: إيقاف تنزيل، تغيير عدد الاتصالات"
    override val scLevelProtected = "فعل محمي: تغيير VPN/DNS، يحتاج موافقتك الصريحة"
    override val scManualWins = "إذا اخترت سيرفراً أو إعداداً بنفسك، لا يغيّره الذكاء الاصطناعي."
    override val chatGreeting1 = "مرحباً 👋"
    override val chatGreeting2 = "كيف أقدر أساعدك اليوم؟"
    override val chatSuggestions = listOf(
        "افحص الإنترنت عندي",
        "ليش التنزيل بطيء؟",
        "اشرحلي كيف يشتغل الـVPN",
        "اكتب لي رسالة قصيرة بالعربي"
    )
    override val chatPlaceholder = "اسأل RYN AI"
    override val chatTyping = "✦ جاري الكتابة..."
    override val chatNoKeyBanner = "بدون مفتاح Gemini الردود محلية ومحدودة. أضفه من ⚙"
    override fun chatOfflineReply(title: String, connections: Int) =
        "$title\nالتوصية: $connections اتصالات تنزيل.\n\n💡 لتفعيل الدردشة الذكية الكاملة أضف مفتاح Gemini المجاني من زر ⚙ بالأعلى."
    override val chatSettingsTitle = "إعدادات Gemini"
    override val chatKeyHelp = "احصل على مفتاح مجاني من aistudio.google.com ثم الصقه هنا. يُحفظ مشفراً على جهازك فقط."
    override val chatKeyLabel = "Gemini API key"
    override val chatModelLabel = "النموذج"
    override val promptLanguage = "العربية البسيطة"
    override val notifChannelName = "التنزيلات الجارية"
    override fun notifProgress(count: Int, percent: Int) = "$count تنزيل جاري • $percent%"
    override val browserPlaceholder = "ابحث أو اكتب عنوان موقع"
    override val settingsTitle = "الإعدادات"
    override val settingsLanguage = "اللغة"
    override val settingsAbout = "RYN-AI-Speed • نسخة Android"
}

object EnglishStrings : RynStrings {
    override val languageCode = "en"
    override val isRtl = false
    override val languageName = "English"
    override val navHome = "Home"
    override val navDownloads = "Download Manager"
    override val navBrowser = "Browser"
    override val navNetwork = "Network Optimizer"
    override val navVpn = "VPN"
    override val navSmartCore = "Smart Core"
    override val navChat = "Chat"
    override val navSettings = "Settings"
    override val tagline = "Faster downloads, smarter connection, stronger protection"
    override val save = "Save"
    override val cancel = "Cancel"
    override val bannerReady = "Encrypted core is active"
    override val bannerCheckingShared = "Checking the shared link..."
    override val bannerPaused = "Download paused"
    override val bannerResuming = "Resuming download..."
    override val bannerCanceled = "Download canceled and temp file deleted"
    override val bannerCaptured = "Link captured from the browser..."
    override val bannerAutoOn = "Smart auto manager enabled"
    override val bannerAutoOff = "Auto manager stopped immediately"
    override fun downloadDone(name: String) = "Download complete: $name"
    override val downloadInterrupted = "Download stopped"
    override fun downloadFailed(message: String) = "Download failed: $message"
    override val mapCaption = "Available servers"
    override val goVpn = "Manage VPN"
    override val goDownloads = "Download Manager"
    override val goNetwork = "Network test"
    override fun statDownloads(n: Int) = "$n downloads"
    override fun statPing(ms: Int, lossPct: Int) = "$ms ms • $lossPct% loss"
    override val statNoTest = "No test yet"
    override fun statServers(n: Int) = "$n servers"
    override val autoTitle = "Smart auto manager"
    override val autoHint = "Instant master kill switch"
    override val vpnProtected = "Protected"
    override val vpnUnprotected = "Not protected"
    override val vpnUnprotectedSub = "Your connection is exposed. Tap Quick Connect"
    override val vpnConnectingTitle = "Connecting..."
    override val vpnFailedTitle = "Connection failed"
    override val vpnQuickConnect = "⚡ Quick Connect (auto)"
    override val vpnDisconnect = "Disconnect"
    override val vpnConnectingBtn = "Connecting..."
    override val vpnAddFirst = "Add WireGuard servers first (button below)"
    override val vpnPermissionDenied = "VPN permission was denied by the system"
    override fun vpnCountries(n: Int) = "Countries ($n)"
    override val vpnAddServer = "＋ Add server (.conf)"
    override val vpnEmptyTitle = "No servers yet"
    override val vpnEmptyBody = "A VPN needs real WireGuard servers in several countries. Options: (1) a provider that gives you WireGuard files, such as Mullvad, or (2) your own server. Download the .conf files and tap «Add server». Name each file with its country code (us-nyc.conf or se-sto.conf) to get the flag and name."
    override fun vpnServersCount(n: Int) = "$n servers"
    override val vpnConnect = "Connect"
    override val vpnDelete = "Delete"
    override val vpnKillTitle = "Kill Switch"
    override val vpnKillBody = "Android has a real kill switch: enable «Always-on VPN» and «Block connections without VPN» in system settings."
    override val vpnKillButton = "Open system VPN settings"
    override fun vpnExit(country: String, ms: Long) = "Exit: $country  •  Verified in: $ms ms"
    override fun vpnImported(n: Int) = "$n server(s) added"
    override fun vpnImportFailed(msg: String) = "Import failed: $msg"
    override fun vpnImportPartial(ok: Int, failed: Int, msg: String) = "$ok added, $failed failed: $msg"
    override val netTitle = "Real network test"
    override val netRun = "Test Ping and DNS"
    override val netRunning = "Testing..."
    override fun netResult(pingMs: Int, jitterMs: Int, lossPct: Int) = "Ping: $pingMs ms • Jitter: $jitterMs ms • Connection loss: $lossPct%"
    override val netNote = "Measured with real TCP connections (not ICMP). All results come from your device only."
    override val dnsUnavailable = "Unavailable"
    override val scTitle = "Smart Core"
    override val scIntro = "Analyzes your real measurements and proposes clear decisions. It never performs anything sensitive without your approval, and your manual choice always wins."
    override val scNoData = "No measurements yet. Run the network test from the Network Optimizer page."
    override val scAnalysis = "Current analysis (read-only)"
    override fun scRecommendation(connections: Int) = "Recommendation: $connections download connections"
    override val scLevelsTitle = "Permission levels"
    override val scLevelRead = "Read: measurements and reports, no changes"
    override val scLevelSafe = "Safe action: pause a download, change connection count"
    override val scLevelProtected = "Protected action: change VPN/DNS, needs your explicit approval"
    override val scManualWins = "If you pick a server or a setting yourself, the AI does not override it."
    override val chatGreeting1 = "Hello 👋"
    override val chatGreeting2 = "How can I help you today?"
    override val chatSuggestions = listOf(
        "Check my internet",
        "Why is my download slow?",
        "Explain how the VPN works",
        "Write me a short message"
    )
    override val chatPlaceholder = "Ask RYN AI"
    override val chatTyping = "✦ Typing..."
    override val chatNoKeyBanner = "Without a Gemini key replies are local and limited. Add one from ⚙"
    override fun chatOfflineReply(title: String, connections: Int) =
        "$title\nRecommendation: $connections download connections.\n\n💡 To enable the full smart chat, add your free Gemini key from the ⚙ button above."
    override val chatSettingsTitle = "Gemini settings"
    override val chatKeyHelp = "Get a free key from aistudio.google.com and paste it here. It is stored encrypted on your device only."
    override val chatKeyLabel = "Gemini API key"
    override val chatModelLabel = "Model"
    override val promptLanguage = "English"
    override val notifChannelName = "Active downloads"
    override fun notifProgress(count: Int, percent: Int) = "$count active download(s) • $percent%"
    override val browserPlaceholder = "Search or type a website address"
    override val settingsTitle = "Settings"
    override val settingsLanguage = "Language"
    override val settingsAbout = "RYN-AI-Speed • Android edition"
}

val LocalStrings = compositionLocalOf<RynStrings> { ArabicStrings }

object LanguageStore {
    private const val PREFS = "ryn_prefs"
    private const val KEY = "lang"

    fun load(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "ar") ?: "ar"

    fun save(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
    }

    fun stringsFor(code: String): RynStrings = if (code == "en") EnglishStrings else ArabicStrings
}
