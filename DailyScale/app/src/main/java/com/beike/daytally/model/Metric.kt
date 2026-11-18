package com.beike.daytally.model

/**
 * 一个记录项目。
 * @param per 每组数值；0 表示身高类（按当天原始值记录，不乘组）
 * @param goal 累计目标；0 表示无目标（身高）
 * @param maxGroups 趋势纵轴上限（组）；0 表示身高固定区间
 * @param data key=yyyy-MM-dd，value=当天原始值（组数 或 厘米）
 */
data class Metric(
    val key: String,
    var name: String,
    var unit: String,
    val dec: Int,
    var per: Int,
    var goal: Int,
    var maxGroups: Int,
    val data: MutableMap<String, Double>
)
