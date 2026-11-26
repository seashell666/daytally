package com.beike.daytally.bodyfat

import com.beike.daytally.muscle.MusclePolicy

/**
 * OKOK 测量结果纯规则：把 WeightEntity 的原始数值换算成日刻能落库的值。
 *
 * WeightEntity 里 axunge 是体脂率（%），muscle 是肌肉百分比；
 * OKOK 自己显示的肌肉重量 = weight × muscle / 100（公斤）。
 * 这里只做同一套换算和范围校验，不修改、不猜补 OKOK 的结果。
 */
data class OkokMeasurement(
    val bodyFat: Double,
    val muscleKg: Double,
    val weightKg: Double,
) {
    val hasBodyFat: Boolean get() = BodyFatPolicy.isValidValue(bodyFat)
    val hasMuscle: Boolean get() = MusclePolicy.isValidValue(muscleKg)
    val hasAny: Boolean get() = hasBodyFat || hasMuscle
}

object OkokResultPolicy {
    /** 一次“去记录”会话的有效期；过期结果一律不落库，避免旧测量串进新记录。 */
    const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L

    fun isSessionActive(startedAt: Long, now: Long): Boolean =
        startedAt > 0L && now >= startedAt && now - startedAt <= SESSION_TIMEOUT_MS

    fun fromWeightEntity(
        weightKg: Double,
        bodyFat: Double,
        musclePercent: Double,
    ): OkokMeasurement {
        val safeWeight = if (weightKg.isNaN() || weightKg.isInfinite() || weightKg <= 0.0) {
            Double.NaN
        } else {
            weightKg
        }
        val safeBodyFat = if (BodyFatPolicy.isValidValue(bodyFat)) bodyFat else Double.NaN
        val calculatedMuscle = if (safeWeight.isNaN() || musclePercent.isNaN() ||
            musclePercent.isInfinite() || musclePercent <= 0.0
        ) {
            Double.NaN
        } else {
            MusclePolicy.round1(safeWeight * musclePercent / 100.0)
        }
        val safeMuscle = if (MusclePolicy.isValidValue(calculatedMuscle)) calculatedMuscle else Double.NaN
        return OkokMeasurement(safeBodyFat, safeMuscle, safeWeight)
    }

    fun bodyFatRecordId(sessionId: String): String = "$sessionId-bf".take(128)
}
