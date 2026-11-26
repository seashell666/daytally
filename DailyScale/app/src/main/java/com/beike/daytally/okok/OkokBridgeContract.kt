package com.beike.daytally.okok

/**
 * 日刻 ↔ OKOK 适配层的最小协议。
 *
 * 只在两个进程之间传递一次测量结果，不碰 OKOK 的登录、账号、会员或网络请求。
 * 所有常量必须与 DayTally 的 BodyFatBridgeContract 保持一致。
 */
object OkokBridgeContract {
    const val DAYTALLY_PACKAGE = "com.beike.daytally"

    const val ACTION_RESULT = "com.beike.daytally.bodyfat.RESULT"
    const val EXTRA_RESULT_PENDING_INTENT = "com.beike.daytally.bodyfat.RESULT_PENDING_INTENT"
    const val EXTRA_SESSION_ID = "com.beike.daytally.bodyfat.SESSION_ID"

    const val EXTRA_VALUE = "value"
    const val EXTRA_MUSCLE_KG = "muscleKg"
    const val EXTRA_WEIGHT_KG = "weightKg"
    const val EXTRA_MEASURED_AT = "measuredAt"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_RECORD_ID = "recordId"
    const val EXTRA_METRIC_TYPE = "metricType"
    const val EXTRA_UNIT = "unit"
    const val EXTRA_ERROR = "error"

    const val METRIC_BODY_FAT = "body_fat"
    const val METRIC_MUSCLE = "muscle"
    const val UNIT_BODY_FAT = "%"
    const val UNIT_MUSCLE = "公斤"
    const val SOURCE_OKOK = "okok"
}
