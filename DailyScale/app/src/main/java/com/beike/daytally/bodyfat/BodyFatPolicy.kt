package com.beike.daytally.bodyfat

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 体脂率的纯规则层：不依赖 Android，方便在 JVM 单测里审计范围、来源和日期。 */
object BodyFatPolicy {
    const val METRIC_TYPE = "body_fat"
    const val UNIT = "%"
    const val MIN_VALUE = 1.0
    const val MAX_VALUE = 75.0

    const val SOURCE_OKOK = "okok"
    const val SOURCE_OKOK_MANUAL = "okok-manual"
    const val SOURCE_MANUAL = "manual"

    fun isValidValue(value: Double): Boolean =
        !value.isNaN() && !value.isInfinite() && value in MIN_VALUE..MAX_VALUE

    fun normalizeSource(raw: String?): String {
        val s = raw.orEmpty().trim().lowercase()
        return when {
            s == SOURCE_OKOK -> SOURCE_OKOK
            s == SOURCE_OKOK_MANUAL || s == "okok_manual" -> SOURCE_OKOK_MANUAL
            s.contains("okok") -> SOURCE_OKOK_MANUAL
            s == SOURCE_MANUAL -> SOURCE_MANUAL
            else -> SOURCE_MANUAL
        }
    }

    fun sourceLabel(source: String): String = when (source) {
        SOURCE_OKOK -> "OKOK 测量"
        SOURCE_OKOK_MANUAL -> "OKOK · 已确认"
        else -> "手动记录"
    }

    fun localDate(measuredAt: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(measuredAt).atZone(zone).toLocalDate()

    fun timeLabel(measuredAt: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(measuredAt).atZone(zone)
        return String.format("%02d:%02d", t.hour, t.minute)
    }

    fun dateTimeLabel(measuredAt: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(measuredAt).atZone(zone)
        return String.format("%02d/%02d %02d:%02d", t.monthValue, t.dayOfMonth, t.hour, t.minute)
    }

    fun format(value: Double): String = String.format(java.util.Locale.CHINA, "%.1f", value)
}

data class BodyFatRecord(
    val recordId: String,
    val value: Double,
    val measuredAt: Long,
    val source: String,
    val deviceId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val unit: String = BodyFatPolicy.UNIT,
    val metricType: String = BodyFatPolicy.METRIC_TYPE,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("recordId", recordId)
        .put("metricType", metricType)
        .put("value", value)
        .put("unit", unit)
        .put("measuredAt", measuredAt)
        .put("source", source)
        .put("deviceId", deviceId)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        fun new(
            value: Double,
            source: String,
            deviceId: String,
            measuredAt: Long = System.currentTimeMillis(),
            recordId: String = UUID.randomUUID().toString(),
            now: Long = System.currentTimeMillis(),
        ): BodyFatRecord {
            require(BodyFatPolicy.isValidValue(value)) { "体脂率必须在 1.0 ~ 75.0 之间" }
            val safeMeasured = if (measuredAt in 1L..(now + 24 * 60 * 60 * 1000L)) measuredAt else now
            val safeDevice = deviceId.trim().ifBlank { "android" }.take(128)
            return BodyFatRecord(
                recordId = recordId.take(128),
                value = Math.round(value * 10.0) / 10.0,
                measuredAt = safeMeasured,
                source = BodyFatPolicy.normalizeSource(source),
                deviceId = safeDevice,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun fromJson(raw: JSONObject): BodyFatRecord? = runCatching {
            val id = raw.optString("recordId").trim()
            val value = raw.optDouble("value", Double.NaN)
            val measuredAt = raw.optLong("measuredAt", 0L)
            val createdAt = raw.optLong("createdAt", measuredAt)
            val updatedAt = raw.optLong("updatedAt", createdAt)
            val metricType = raw.optString("metricType", BodyFatPolicy.METRIC_TYPE)
            val unit = raw.optString("unit", BodyFatPolicy.UNIT)
            if (id.isBlank() || metricType != BodyFatPolicy.METRIC_TYPE ||
                unit != BodyFatPolicy.UNIT || !BodyFatPolicy.isValidValue(value) || measuredAt <= 0
            ) {
                null
            } else {
                BodyFatRecord(
                    recordId = id.take(128),
                    value = Math.round(value * 10.0) / 10.0,
                    measuredAt = measuredAt,
                    source = BodyFatPolicy.normalizeSource(raw.optString("source")),
                    deviceId = raw.optString("deviceId", "android").take(128),
                    createdAt = if (createdAt > 0) createdAt else measuredAt,
                    updatedAt = if (updatedAt > 0) updatedAt else measuredAt,
                )
            }
        }.getOrNull()
    }
}
