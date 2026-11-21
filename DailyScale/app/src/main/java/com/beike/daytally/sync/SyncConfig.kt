package com.beike.daytally.sync

import com.beike.daytally.BuildConfig

/** 同步配置来自构建时的 DailyScale/sync.properties，不把地址和 Token 写进仓库。 */
object SyncConfig {
    val enabled: Boolean
        get() = BuildConfig.SYNC_BASE_URL.isNotBlank() && BuildConfig.SYNC_TOKEN.isNotBlank()

    val baseUrl: String
        get() = BuildConfig.SYNC_BASE_URL.trim().trimEnd('/')

    val token: String
        get() = BuildConfig.SYNC_TOKEN.trim()
}
