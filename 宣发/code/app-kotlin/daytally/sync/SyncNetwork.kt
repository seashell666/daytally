package com.beike.daytally.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * 只在“当前连的是 Wi-Fi”时同步。服务端在家里的局域网，走流量是连不上的；
 * 而 Android 的 Wi-Fi 在断线重连时会重新回调 onAvailable —— 这就是用户说的
 * “出门断开、回家连上 Wi-Fi 后自动对齐”的触发点。
 */
object SyncNetwork {

    @Volatile private var installed = false

    fun isOnWifi(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val n = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(n) ?: return false
        cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    fun install(c: Context) {
        if (installed) return
        val app = c.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (wifi(cm, network)) SyncManager.request(app, "wifi_reconnect")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    SyncManager.request(app, "wifi_cap")
                }
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(cb)
            installed = true
        }
    }

    private fun wifi(cm: ConnectivityManager, n: Network): Boolean = runCatching {
        cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)
}
