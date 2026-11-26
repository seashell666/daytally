package com.beike.daytally.bodyfat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.beike.daytally.Repo
import com.beike.daytally.muscle.MusclePolicy
import com.beike.daytally.widget.WidgetRefresh

/**
 * 只接收日刻自己交给 OKOK 适配层的 PendingIntent。
 *
 * 收到真实结果后直接落库，不再让用户面对一个“确认一下”的手动弹窗：
 * - 体脂率写 BodyFatStore（独立记录、可多次测量）
 * - 肌肉重量写 Repo 的 muscle 指标（按测量日记录、走原有同步）
 *
 * 如果 OKOK 只回了其中一项，就只落有效的一项，并明确告诉用户“部分记录”；
 * 两项都无效时不写任何数据。
 */
class BodyFatResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BodyFatBridgeContract.ACTION_RESULT) return

        val session = OkokMeasureSession.current(context) ?: run {
            // 过期/未知会话：不落库，避免旧测量串进新记录。
            return
        }
        val sessionId = intent.getStringExtra(BodyFatBridgeContract.EXTRA_SESSION_ID)
        if (sessionId != session.id) return

        val now = System.currentTimeMillis()
        val measuredAt = intent.getLongExtra(BodyFatBridgeContract.EXTRA_MEASURED_AT, now)
        val bodyFat = intent.getDoubleExtra(BodyFatBridgeContract.EXTRA_VALUE, Double.NaN)
        val muscleKg = intent.getDoubleExtra(BodyFatBridgeContract.EXTRA_MUSCLE_KG, Double.NaN)
        val error = intent.getStringExtra(BodyFatBridgeContract.EXTRA_ERROR)

        var savedBodyFat = false
        var savedMuscle = false

        if (BodyFatPolicy.isValidValue(bodyFat)) {
            runCatching {
                BodyFatStore.add(
                    context,
                    bodyFat,
                    BodyFatBridgeContract.SOURCE_OKOK,
                    measuredAt,
                    intent.getStringExtra(BodyFatBridgeContract.EXTRA_RECORD_ID)
                        ?: OkokResultPolicy.bodyFatRecordId(session.id),
                )
                savedBodyFat = true
            }
        }
        if (MusclePolicy.isValidValue(muscleKg)) {
            runCatching {
                savedMuscle = Repo.get(context).setMuscle(muscleKg, measuredAt)
            }
        }

        val status = when {
            savedBodyFat && savedMuscle -> "ok"
            savedBodyFat || savedMuscle -> "partial"
            else -> "failed"
        }
        OkokMeasureSession.markResult(context, session.id, status, bodyFat, muscleKg)
        WidgetRefresh.refreshAll(context)
        WidgetRefresh.refreshBodyFat(context)

        val message = when {
            savedBodyFat && savedMuscle ->
                "已记录：体脂 ${BodyFatPolicy.format(bodyFat)}% · 肌肉 ${MusclePolicy.format(muscleKg)} 公斤"
            savedBodyFat ->
                "已记录体脂 ${BodyFatPolicy.format(bodyFat)}%；这次没拿到有效肌肉重量"
            savedMuscle ->
                "已记录肌肉 ${MusclePolicy.format(muscleKg)} 公斤；这次没拿到有效体脂率"
            error != null ->
                "OKOK 没有回传有效结果，本次没有写入数据"
            else ->
                "OKOK 结果无效，本次没有写入数据"
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
