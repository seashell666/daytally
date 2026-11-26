package com.beike.daytally.bodyfat

/**
 * 日刻 ↔ OKOK 适配层的窄契约。
 *
 * 日刻用 OKOK 自己的 AppWidget://WeightUpScale 入口打开官方称重流程，
 * 同时把一个只能写回日刻的 PendingIntent 放进启动 Intent；适配层在用户正常测量完成后，
 * 用这个 PendingIntent 把 WeightEntity 里的真实体脂率和肌肉重量送回来。
 *
 * 这样既不需要把 Receiver 暴露给任意应用，也不需要给适配层开放网络或存储权限，
 * 更不会修改 OKOK 的登录、会员、服务器请求或测量结果。
 */
object BodyFatBridgeContract {
    const val OKOK_PACKAGE = "com.chipsea.btcontrol"
    const val OKOK_MAIN_ACTIVITY = "com.chipsea.btcontrol.MainActivity"
    const val OKOK_UP_SCALE_URL = "AppWidget://WeightUpScale?params=0"
    const val ACTION_RESULT = "com.beike.daytally.bodyfat.RESULT"

    /** OKOK 启动 Intent 里的 PendingIntent extra；适配层原样取出即可。 */
    const val EXTRA_RESULT_PENDING_INTENT = "com.beike.daytally.bodyfat.RESULT_PENDING_INTENT"
    /** 一次测量会话；Receiver 只接受当前未过期会话的结果，防止旧结果串入。 */
    const val EXTRA_SESSION_ID = "com.beike.daytally.bodyfat.SESSION_ID"
    /** 体脂率（%），沿用旧字段名，方便和上一版 Receiver 兼容。 */
    const val EXTRA_VALUE = "value"
    /** 肌肉重量（公斤）；由 OKOK WeightEntity 的体重 × 肌肉百分比换算。 */
    const val EXTRA_MUSCLE_KG = "muscleKg"
    /** OKOK 当次称出的总体重（公斤），只用于校验和日志，不落库。 */
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
    const val SOURCE_MANUAL = "manual"
}
