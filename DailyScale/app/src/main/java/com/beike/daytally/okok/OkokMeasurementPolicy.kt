package com.beike.daytally.okok

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 只做纯规则：把 OKOK 的测量字段换成日刻的体脂率 / 肌肉重量，并校验范围。
 *
 * OKOK 的 WeightEntity 里：
 * - getAxunge() 是体脂率百分比；
 * - getDbMuscle() 是肌肉百分比；
 * - getMuscle() 在部分设备上也是百分比兜底；
 * - getWeight() 是总体重（公斤）。
 * OKOK 自己显示肌肉重量时用的是“总体重 × 肌肉百分比 / 100”，这里沿用同一口径。
 */
object OkokMeasurementPolicy {
    const val MIN_BODY_FAT = 1.0
    const val MAX_BODY_FAT = 75.0
    const val MIN_MUSCLE_KG = 20.0
    const val MAX_MUSCLE_KG = 120.0
    const val MIN_WEIGHT_KG = 20.0
    const val MAX_WEIGHT_KG = 300.0
    val SESSION_TIMEOUT_MS: Long = TimeUnit.MINUTES.toMillis(30)

    data class OkokMeasurement(
        val bodyFat: Double,
        val muscleKg: Double,
        val weightKg: Double,
        val measuredAt: Long,
    ) {
        val hasBodyFat: Boolean get() = isValidBodyFat(bodyFat)
        val hasMuscle: Boolean get() = isValidMuscleKg(muscleKg)
        val hasAny: Boolean get() = hasBodyFat || hasMuscle
    }

    fun fromWeightEntity(
        weightKg: Double,
        bodyFat: Double,
        musclePercent: Double,
        measuredAt: Long,
    ): OkokMeasurement {
        val safeWeight = if (weightKg.isFinite() && weightKg in MIN_WEIGHT_KG..MAX_WEIGHT_KG) {
            weightKg
        } else {
            Double.NaN
        }
        val muscleKg = if (
            safeWeight.isFinite() &&
            musclePercent.isFinite() &&
            musclePercent > 0.0 &&
            musclePercent <= 100.0
        ) {
            round1(safeWeight * musclePercent / 100.0)
        } else {
            Double.NaN
        }
        val safeBodyFat = if (isValidBodyFat(bodyFat)) round1(bodyFat) else Double.NaN
        return OkokMeasurement(
            bodyFat = safeBodyFat,
            muscleKg = if (isValidMuscleKg(muscleKg)) muscleKg else Double.NaN,
            weightKg = safeWeight,
            measuredAt = measuredAt,
        )
    }

    /**
     * 用反射读取目标 APK 的 WeightEntity，避免把 OKOK 的类直接编译进模块。
     * 任何字段读不到都返回 NaN，绝不猜值。
     */
    fun readWeightEntity(entity: Any, measuredAt: Long): OkokMeasurement {
        val weight = number(entity, "getWeight")
        val bodyFat = number(entity, "getAxunge")
        val dbMuscle = number(entity, "getDbMuscle")
        val fallbackMuscle = number(entity, "getMuscle")
        val musclePercent = when {
            dbMuscle.isFinite() && dbMuscle > 0.0 -> dbMuscle
            fallbackMuscle.isFinite() && fallbackMuscle > 0.0 -> fallbackMuscle
            else -> Double.NaN
        }
        return fromWeightEntity(weight, bodyFat, musclePercent, measuredAt)
    }

    fun isValidBodyFat(value: Double): Boolean =
        value.isFinite() && value in MIN_BODY_FAT..MAX_BODY_FAT

    fun isValidMuscleKg(value: Double): Boolean =
        value.isFinite() && value in MIN_MUSCLE_KG..MAX_MUSCLE_KG

    /**
     * OKOK 的 WeightEntity 自带 weight_time。用它判断“这是不是一次新测量”，
     * 比只看会话年龄可靠：旧结果会被它自己的时间戳暴露出来。
     */
    fun readWeightTimeRaw(entity: Any): String? = runCatching {
        val method = entity.javaClass.methods.firstOrNull {
            it.name == "getWeight_time" && it.parameterCount == 0
        } ?: return@runCatching null
        (method.invoke(entity) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun parseWeightTime(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyyMMddHHmmss",
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getDefault()
                }.parse(text)
            }.getOrNull() ?: continue
            return parsed.time
        }
        return null
    }

    /**
     * 是不是“同一条 OKOK 记录被又回传了一遍”。
     *
     * 真机核对过 OKOK 自己的数据库：人每站一次秤，它就新增一条记录、换一个新的
     * weight_time（13:13:12 和 13:27:21 两次真实站秤就是两条不同的记录）。
     * 所以只有 weight_time 和上次已回传的一模一样，才能断定是重复回传；
     * 数值相同但时间不同的，是真人又测了一次，必须照常记进日刻。
     */
    fun isSameOkokRecord(weightTimeRaw: String?, lastDeliveredWeightTime: String?): Boolean =
        !weightTimeRaw.isNullOrEmpty() && weightTimeRaw == lastDeliveredWeightTime

    fun isSessionActive(capturedAt: Long, now: Long): Boolean {
        if (capturedAt <= 0L || now <= capturedAt) return capturedAt > 0L && now >= capturedAt
        return now - capturedAt <= SESSION_TIMEOUT_MS
    }

    fun format(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

    fun round1(value: Double): Double {
        if (!value.isFinite()) return Double.NaN
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toDouble()
    }

    private fun number(entity: Any, methodName: String): Double = runCatching {
        val method = entity.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterCount == 0
        } ?: return@runCatching Double.NaN
        when (val value = method.invoke(entity)) {
            is Number -> value.toDouble()
            else -> Double.NaN
        }
    }.getOrDefault(Double.NaN)
}
