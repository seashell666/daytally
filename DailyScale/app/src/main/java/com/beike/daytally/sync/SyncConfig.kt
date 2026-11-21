package com.beike.daytally.sync

import android.content.Context
import java.net.URI

/**
 * 同步服务地址和访问令牌。
 *
 * 1.2.11 起不再从构建参数里塞地址/Token：公开包也能在设置页自己填，
 * 这样别人 clone 项目、在家里部署好自己的后端，也能直接连自己的服务。
 */
object SyncConfig {

    private const val PREFS = "ds_sync"
    private const val K_BASE = "base_url"
    private const val K_TOKEN = "token"
    private const val DEFAULT_PORT = 8787

    data class Endpoint(
        val host: String,
        val port: Int,
        val token: String
    ) {
        val baseUrl: String get() = "http://$host:$port"
        val enabled: Boolean get() = host.isNotBlank() && port in 1..65535
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前配置；没填地址时返回 null，纯本地模式。 */
    fun endpoint(c: Context): Endpoint? {
        val base = prefs(c).getString(K_BASE, "").orEmpty().trim()
        if (base.isBlank()) return null
        val uri = runCatching { URI(base) }.getOrNull()
        val host = uri?.host?.takeIf { it.isNotBlank() } ?: base
            .removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore(':')
        val port = uri?.port?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
        if (host.isBlank()) return null
        return Endpoint(host, port, token(c))
    }

    fun enabled(c: Context): Boolean = endpoint(c)?.enabled == true

    fun baseUrl(c: Context): String = endpoint(c)?.baseUrl.orEmpty()

    fun token(c: Context): String = prefs(c).getString(K_TOKEN, "").orEmpty().trim()

    fun save(c: Context, host: String, port: Int, token: String) {
        val cleanHost = cleanHost(host)
        prefs(c).edit()
            .putString(K_BASE, "http://$cleanHost:$port")
            .putString(K_TOKEN, token.trim())
            .apply()
    }

    fun clear(c: Context) {
        prefs(c).edit().remove(K_BASE).remove(K_TOKEN).apply()
    }

    /** 判断一次同步开始时用的地址/令牌，现在是否还是同一套；防止换服务器后旧响应写回新配置。 */
    fun same(c: Context, e: Endpoint): Boolean {
        val now = endpoint(c) ?: return false
        return now.host == e.host && now.port == e.port && now.token == e.token
    }

    /** 允许用户只填 IP；顺手带 http:// 或 :端口 也清掉，端口始终以单独那一栏为准。 */
    fun cleanHost(raw: String): String {
        var s = raw.trim()
        s = s.removePrefix("http://").removePrefix("https://")
        s = s.substringBefore('/')
        if (!s.contains('[')) s = s.substringBefore(':')
        return s.trim()
    }
}
