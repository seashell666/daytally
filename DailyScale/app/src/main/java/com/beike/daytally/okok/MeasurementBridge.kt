package com.beike.daytally.okok

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 只做一次单向的结果搬运：
 * DayTally 把“只允许回传给日刻”的 PendingIntent 交给 OKOK 的启动 Intent；
 * 本模块在 OKOK 的 MainActivity 收到它时暂存，在测量结果方法被调用时把
 * 体脂率、肌肉重量（公斤）和总体重回传给日刻。
 *
 * 不伪造数据、不绕过登录/会员、不修改 OKOK 网络请求，也不监听所有 Dialog。
 */
object MeasurementBridge {
    private const val TAG = "DayTallyOKOK"
    private const val MAX_SESSION_AGE_MS = 30L * 60L * 1000L
    /**
     * 页面刚打开 3 秒内就回传的，不可能是这次站秤测出来的（秤要先连上、称稳、再测阻抗）。
     * 只挡这 3 秒：实测从进入称重页到出结果是 12 秒左右，门限设得太高会把真人
     * 快速站秤的结果当成旧结果丢掉。
     */
    private const val MIN_SESSION_AGE_MS = 3_000L
    private const val PREFS = "daytally_bridge"
    private const val KEY_LAST_ENTITY_TIME = "last_entity_time"

    private val lock = Any()
    private var pendingIntent: PendingIntent? = null
    private var sessionId: String? = null
    private var capturedAt: Long = 0L

    /**
     * 从 OKOK MainActivity 的启动 Intent 里取回日刻的 PendingIntent。
     * 只接受 creatorPackage 为 com.beike.daytally 的 PendingIntent，避免别的应用冒充。
     */
    fun capture(activity: Activity, intent: Intent?) {
        if (intent == null) return
        val session = intent.getStringExtra(OkokBridgeContract.EXTRA_SESSION_ID)?.trim()
        if (session.isNullOrEmpty()) return

        val pending = pendingIntentOf(intent) ?: return
        val creator = runCatching { pending.creatorPackage }.getOrNull()
        if (creator != OkokBridgeContract.DAYTALLY_PACKAGE) {
            Log.w(TAG, "ignored measurement intent from $creator")
            return
        }

        synchronized(lock) {
            if (sessionId == session && pendingIntent != null) return
            pendingIntent = pending
            sessionId = session
            capturedAt = System.currentTimeMillis()
        }
        Log.i(TAG, "measurement bridge armed")
    }

    /**
     * 在 OKOK 的 BluetoothUpScaleActivity.startReportActivity(WeightEntity) 里调用。
     * 只回传本次会话；没有会话、过期会话、没有有效结果时都不写日刻。
     */
    fun deliver(activity: Activity, weightEntity: Any) {
        val pending: PendingIntent
        val session: String
        val startedAt: Long
        val now = System.currentTimeMillis()
        val entityTimeRaw = OkokMeasurementPolicy.readWeightTimeRaw(weightEntity)
        val entityTime = OkokMeasurementPolicy.parseWeightTime(entityTimeRaw)
        val measurement = OkokMeasurementPolicy.readWeightEntity(weightEntity, now)
        val signature = "${measurement.bodyFat}|${measurement.muscleKg}|${measurement.weightKg}"
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        Log.i(
            TAG,
            "entity weightTime=$entityTimeRaw parsed=$entityTime values=$signature",
        )

        synchronized(lock) {
            pending = pendingIntent ?: return
            session = sessionId ?: return
            startedAt = capturedAt

            /* 刚打开 OKOK 时，它可能先把上一次留在页面里的结果再报一次；
               这不是本次刚测出来的数，不能写进日刻，也不能丢掉本次会话。 */
            if (now - startedAt < MIN_SESSION_AGE_MS) {
                Log.i(TAG, "ignored result before a fresh measurement")
                return
            }

            if (!OkokMeasurementPolicy.isSessionActive(startedAt, now) ||
                now - startedAt > MAX_SESSION_AGE_MS
            ) {
                pendingIntent = null
                sessionId = null
                capturedAt = 0L
                Log.w(TAG, "measurement session expired")
                return
            }

            /* 同一条 OKOK 记录（weight_time 相同）被再次回传，才是重复；
               数值相同但 weight_time 不同，是真人又测了一次，不能挡。 */
            val lastTime = prefs.getString(KEY_LAST_ENTITY_TIME, null)
            if (OkokMeasurementPolicy.isSameOkokRecord(entityTimeRaw, lastTime)) {
                Log.i(TAG, "ignored duplicate delivery of the same weightTime")
                return
            }

            pendingIntent = null
            sessionId = null
            capturedAt = 0L
        }

        val fillIn = Intent().apply {
            action = OkokBridgeContract.ACTION_RESULT
            setPackage(OkokBridgeContract.DAYTALLY_PACKAGE)
            putExtra(OkokBridgeContract.EXTRA_SESSION_ID, session)
            putExtra(OkokBridgeContract.EXTRA_MEASURED_AT, measurement.measuredAt)
            putExtra(OkokBridgeContract.EXTRA_SOURCE, OkokBridgeContract.SOURCE_OKOK)
            putExtra(OkokBridgeContract.EXTRA_METRIC_TYPE, OkokBridgeContract.METRIC_BODY_FAT)
            putExtra(OkokBridgeContract.EXTRA_UNIT, OkokBridgeContract.UNIT_BODY_FAT)
            putExtra(OkokBridgeContract.EXTRA_VALUE, measurement.bodyFat)
            putExtra(OkokBridgeContract.EXTRA_MUSCLE_KG, measurement.muscleKg)
            putExtra(OkokBridgeContract.EXTRA_WEIGHT_KG, measurement.weightKg)
            putExtra(OkokBridgeContract.EXTRA_RECORD_ID, "$session-bf")
            if (!measurement.hasAny) {
                putExtra(
                    OkokBridgeContract.EXTRA_ERROR,
                    "OKOK 没有回传有效的体脂率或肌肉重量",
                )
            }
        }

        runCatching {
            pending.send(activity, 0, fillIn)
            prefs.edit()
                .putString(KEY_LAST_ENTITY_TIME, entityTimeRaw)
                .apply()
            Log.i(
                TAG,
                "measurement delivered: bodyFat=${measurement.bodyFat}, " +
                    "muscleKg=${measurement.muscleKg}, weight=${measurement.weightKg}",
            )
        }.onFailure {
            Log.e(TAG, "measurement delivery failed", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun pendingIntentOf(intent: Intent): PendingIntent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                OkokBridgeContract.EXTRA_RESULT_PENDING_INTENT,
                PendingIntent::class.java,
            )
        } else {
            intent.getParcelableExtra(OkokBridgeContract.EXTRA_RESULT_PENDING_INTENT)
        }
}
