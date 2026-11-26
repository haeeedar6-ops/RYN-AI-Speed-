package com.ryn.aispeed.ui

enum class Section(val icon: String) {
    HOME("⚡"),
    DOWNLOADS("⬇"),
    BROWSER("🌐"),
    NETWORK("📶"),
    VPN("🛡"),
    SMART("🧠"),
    CHAT("✦"),
    SETTINGS("⚙");

    fun label(s: RynStrings): String = when (this) {
        HOME -> s.navHome
        DOWNLOADS -> s.navDownloads
        BROWSER -> s.navBrowser
        NETWORK -> s.navNetwork
        VPN -> s.navVpn
        SMART -> s.navSmartCore
        CHAT -> s.navChat
        SETTINGS -> s.navSettings
    }
}
