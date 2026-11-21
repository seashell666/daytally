package com.beike.daytally.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机/升级后把实时同步长连接接回来；没配同步服务时 start() 自己会什么都不做。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> SyncPushService.start(context.applicationContext)
        }
    }
}
