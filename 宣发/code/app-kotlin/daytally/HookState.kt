package com.beike.daytally

import android.content.Context
import android.os.SystemClock

/**
 * 桌面钩子的“心跳台账”。
 *
 * 桌面进程里的 Vector 模块成功加载、或成功处理一次长按，就会给 App 发一条广播；
 * 这里记下时间、来源桌面包名和长按次数。设置页只读这份台账，不猜、不假装。
 */
object HookState {

    const val ACTION_ALIVE = "com.beike.daytally.HOOK_ALIVE"
    const val TOKEN = "daytally-hook-v1"

    private const val PREFS = "ds_cfg"
    private const val K_BOOT = "hook_at_boot"
    private const val K_WALL = "hook_at_wall"
    private const val K_PKG = "hook_pkg"
    private const val K_HIT = "hook_hit"
    private const val K_COUNT = "hook_count"

    /** 超过这个时长没再收到心跳，就提示用户复测一次，避免模块被关掉后还显示“已接上” */
    private const val FRESH_MS = 12L * 60L * 60L * 1000L

    data class Info(
        val bootAt: Long,
        val wallAt: Long,
        val pkg: String?,
        val lastWasHit: Boolean,
        val count: Int
    ) {
        val seen: Boolean get() = bootAt > 0
        val afterBoot: Boolean get() = bootAt > 0 && bootAt <= SystemClock.elapsedRealtime()
        val fresh: Boolean get() = afterBoot && SystemClock.elapsedRealtime() - bootAt <= FRESH_MS
    }

    fun record(c: Context, pkg: String?, hit: Boolean) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = p.getInt(K_COUNT, 0) + 1
        p.edit()
            .putLong(K_BOOT, SystemClock.elapsedRealtime())
            .putLong(K_WALL, System.currentTimeMillis())
            .putString(K_PKG, pkg)
            .putBoolean(K_HIT, hit)
            .putInt(K_COUNT, count)
            .apply()
    }

    fun read(c: Context): Info {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Info(
            bootAt = p.getLong(K_BOOT, 0L),
            wallAt = p.getLong(K_WALL, 0L),
            pkg = p.getString(K_PKG, null),
            lastWasHit = p.getBoolean(K_HIT, false),
            count = p.getInt(K_COUNT, 0)
        )
    }
}
