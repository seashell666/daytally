package com.beike.daytally.bodyfat

import android.content.Context

/**
 * 一次 OKOK 测量会话的本地状态：sessionId、开始时间、结果状态。
 * 只存 sessionId 和结果摘要，不存 OKOK 的账号、设备或原始对象。
 */
object OkokMeasureSession {
    private const val PREF = "ds_okok_measure"
    private const val KEY_ID = "session_id"
    private const val KEY_STARTED = "started_at"
    private const val KEY_STATUS = "result_status"
    private const val KEY_BODY_FAT = "body_fat"
    private const val KEY_MUSCLE = "muscle_kg"

    data class Session(val id: String, val startedAt: Long, val resultStatus: String?)

    fun begin(context: Context, id: String, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_ID, id)
            .putLong(KEY_STARTED, now)
            .remove(KEY_STATUS)
            .remove(KEY_BODY_FAT)
            .remove(KEY_MUSCLE)
            .apply()
    }

    fun current(context: Context, now: Long = System.currentTimeMillis()): Session? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val id = p.getString(KEY_ID, null) ?: return null
        val started = p.getLong(KEY_STARTED, 0L)
        if (!OkokResultPolicy.isSessionActive(started, now)) return null
        return Session(id, started, p.getString(KEY_STATUS, null))
    }

    fun markResult(
        context: Context,
        id: String,
        status: String,
        bodyFat: Double = Double.NaN,
        muscleKg: Double = Double.NaN,
    ) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (p.getString(KEY_ID, null) != id) return
        p.edit()
            .putString(KEY_STATUS, status)
            .putFloat(KEY_BODY_FAT, bodyFat.toFloat())
            .putFloat(KEY_MUSCLE, muscleKg.toFloat())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
