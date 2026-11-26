package com.beike.daytally.muscle

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 肌肉重量的纯规则层：不依赖 Android，方便 JVM 单测。
 *
 * 单位是公斤；数据来自 OKOK 正常测量结果中的“肌肉重量”，不是身高厘米。
 * 20~120 kg 只做异常值兜底，不限制用户正常 50~52 kg 附近的自然浮动。
 */
object MusclePolicy {
    const val METRIC = "muscle"
    const val UNIT = "公斤"
    const val MIN_VALUE = 20.0
    const val MAX_VALUE = 120.0
    const val SOURCE_OKOK = "okok"
    const val SOURCE_MANUAL = "manual"

    fun isValidValue(value: Double): Boolean =
        !value.isNaN() && !value.isInfinite() && value in MIN_VALUE..MAX_VALUE

    fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

    fun format(value: Double): String =
        String.format(java.util.Locale.CHINA, "%.1f", round1(value))

    fun dayKey(measuredAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(measuredAt).atZone(zone).toLocalDate().toString()

    fun localDate(measuredAt: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(measuredAt).atZone(zone).toLocalDate()
}
