package com.ryn.aispeed.ui

import android.content.Context
import com.ryn.aispeed.core.security.KeystoreKeyManager
import com.ryn.aispeed.feature.vpn.RynWireGuardManager
import com.ryn.aispeed.feature.vpn.VpnController
import com.ryn.aispeed.feature.vpn.VpnProfileStore

/** One VPN stack per process, so the connection state survives screen changes and rotation. */
object VpnRuntime {
    class Stack(val store: VpnProfileStore, val controller: VpnController)

    @Volatile
    private var stack: Stack? = null

    fun get(context: Context): Stack =
        stack ?: synchronized(this) {
            stack ?: create(context.applicationContext).also { stack = it }
        }

    private fun create(appContext: Context): Stack {
        val store = VpnProfileStore(appContext, KeystoreKeyManager(appContext))
        return Stack(store, VpnController(store, RynWireGuardManager(appContext)))
    }
}
