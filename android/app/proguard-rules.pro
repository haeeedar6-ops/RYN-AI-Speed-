# RYN-AI-Speed ProGuard / R8 Keep Rules
-keep class net.zetetic.database.** { *; }
-keep class com.ryn.aispeed.core.database.** { *; }
-keep class com.wireguard.** { *; }
-dontwarn com.wireguard.**
-keep class com.ryn.aispeed.feature.vpn.** { *; }
