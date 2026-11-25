package com.ryn.aispeed.feature.vpn

data class ParsedWireGuardConfig(
    val endpointHost: String,
    val endpointPort: Int,
    val fullTunnel: Boolean
)

/**
 * Strict validator for user-imported WireGuard files. Only the documented keys are accepted, so a file can
 * never smuggle in scripts (PostUp/PreUp...) or extra peers. Values are limited to a safe character set.
 */
object WireGuardConfigParser {
    private const val MAX_CHARS = 16_384
    private val INTERFACE_KEYS = setOf("privatekey", "address", "dns", "mtu", "listenport")
    private val PEER_KEYS = setOf("publickey", "presharedkey", "endpoint", "allowedips", "persistentkeepalive")
    private val KEY_REGEX = Regex("^[A-Za-z0-9+/]{43}=$")
    private val SAFE_VALUE = Regex("^[A-Za-z0-9.:/,+=\\[\\] _-]*$")
    private val HOST_REGEX = Regex("^(?:[A-Za-z0-9.-]{1,253}|\\[[0-9A-Fa-f:]+\\])$")

    fun parse(text: String): ParsedWireGuardConfig {
        require(text.isNotBlank() && text.length <= MAX_CHARS) { "ملف الإعداد فارغ أو كبير جداً" }

        var section = ""
        var interfaces = 0
        var peers = 0
        val iface = HashMap<String, String>()
        val peer = HashMap<String, String>()

        for (rawLine in text.lines()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                when (line.lowercase()) {
                    "[interface]" -> {
                        section = "i"
                        interfaces++
                    }
                    "[peer]" -> {
                        section = "p"
                        peers++
                    }
                    else -> throw IllegalArgumentException("قسم غير معروف: $line")
                }
                continue
            }
            val eq = line.indexOf('=')
            require(eq > 0) { "سطر غير صالح: $line" }
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            require(SAFE_VALUE.matches(value)) { "قيمة تحتوي أحرفاً غير مسموحة في $key" }
            when (section) {
                "i" -> {
                    require(key in INTERFACE_KEYS) { "مفتاح غير مسموح في [Interface]: $key" }
                    iface[key] = value
                }
                "p" -> {
                    require(key in PEER_KEYS) { "مفتاح غير مسموح في [Peer]: $key" }
                    peer[key] = value
                }
                else -> throw IllegalArgumentException("سطر خارج أي قسم")
            }
        }

        require(interfaces == 1 && peers == 1) { "يجب أن يحتوي الملف على [Interface] واحد و[Peer] واحد" }
        require(KEY_REGEX.matches(iface["privatekey"].orEmpty())) { "PrivateKey غير صالح" }
        require(!iface["address"].isNullOrBlank()) { "Address مفقود" }
        require(KEY_REGEX.matches(peer["publickey"].orEmpty())) { "PublicKey غير صالح" }
        peer["presharedkey"]?.let { require(KEY_REGEX.matches(it)) { "PresharedKey غير صالح" } }

        val endpoint = peer["endpoint"] ?: throw IllegalArgumentException("Endpoint مفقود")
        val idx = endpoint.lastIndexOf(':')
        require(idx > 0 && idx < endpoint.length - 1) { "Endpoint يجب أن يكون host:port" }
        val host = endpoint.substring(0, idx)
        val port = endpoint.substring(idx + 1).toIntOrNull() ?: throw IllegalArgumentException("منفذ Endpoint غير صالح")
        require(port in 1..65535) { "منفذ Endpoint خارج النطاق" }
        require(HOST_REGEX.matches(host)) { "عنوان Endpoint غير صالح" }

        val fullTunnel = peer["allowedips"].orEmpty().split(',').map { it.trim() }.contains("0.0.0.0/0")
        return ParsedWireGuardConfig(host, port, fullTunnel)
    }
}

object CountryCatalog {
    private val NAMES: Map<String, String> = mapOf(
        "US" to "الولايات المتحدة", "GB" to "بريطانيا", "DE" to "ألمانيا", "FR" to "فرنسا",
        "NL" to "هولندا", "SE" to "السويد", "CH" to "سويسرا", "CA" to "كندا", "JP" to "اليابان",
        "SG" to "سنغافورة", "TR" to "تركيا", "AE" to "الإمارات", "SA" to "السعودية", "EG" to "مصر",
        "IT" to "إيطاليا", "ES" to "إسبانيا", "PL" to "بولندا", "RO" to "رومانيا", "FI" to "فنلندا",
        "NO" to "النرويج", "AU" to "أستراليا", "BR" to "البرازيل", "IN" to "الهند", "KR" to "كوريا الجنوبية",
        "HK" to "هونغ كونغ", "IE" to "أيرلندا", "AT" to "النمسا", "BE" to "بلجيكا", "DK" to "الدنمارك",
        "CZ" to "التشيك", "UA" to "أوكرانيا", "ZA" to "جنوب أفريقيا", "MX" to "المكسيك", "AR" to "الأرجنتين",
        "PT" to "البرتغال", "GR" to "اليونان", "HU" to "المجر", "BG" to "بلغاريا", "LU" to "لوكسمبورغ",
        "LV" to "لاتفيا", "LT" to "ليتوانيا", "EE" to "إستونيا", "IS" to "آيسلندا", "NZ" to "نيوزيلندا",
        "MY" to "ماليزيا", "TH" to "تايلاند", "ID" to "إندونيسيا", "PH" to "الفلبين", "VN" to "فيتنام",
        "TW" to "تايوان", "CL" to "تشيلي", "CO" to "كولومبيا", "RS" to "صربيا", "SK" to "سلوفاكيا",
        "SI" to "سلوفينيا", "HR" to "كرواتيا", "AL" to "ألبانيا", "MD" to "مولدوفا", "CY" to "قبرص"
    )

    const val UNKNOWN = "XX"

    fun nameAr(code: String): String = if (code == UNKNOWN) "غير محدد" else NAMES[code] ?: code

    fun flagEmoji(code: String): String {
        if (code.length != 2 || !code.all { it in 'A'..'Z' } || code == UNKNOWN) return "🌐"
        val base = 0x1F1E6 - 'A'.code
        return String(Character.toChars(base + code[0].code)) + String(Character.toChars(base + code[1].code))
    }

    /** "us-nyc-wg-001.conf" -> "US", "se_sto.conf" -> "SE"; unknown -> "XX". */
    fun guessFromName(fileName: String): String {
        val tokens = fileName.uppercase().split(Regex("[^A-Z]+")).filter { it.length == 2 }
        return tokens.firstOrNull { it in NAMES } ?: UNKNOWN
    }
}

data class ExitInfo(val ip: String, val countryCode: String?)

object TraceParser {
    /** Parses Cloudflare's /cdn-cgi/trace body ("ip=...\nloc=XX\n..."). */
    fun parse(body: String): ExitInfo? {
        val map = HashMap<String, String>()
        for (line in body.lines()) {
            val i = line.indexOf('=')
            if (i > 0) map[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        val ip = map["ip"]?.takeIf { it.isNotBlank() } ?: return null
        return ExitInfo(ip, map["loc"]?.uppercase()?.takeIf { it.length == 2 })
    }
}
