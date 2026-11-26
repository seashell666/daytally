package com.beike.daytally.okok

/** Pure, version-specific rules. Keeping this free of Android types makes the scope easy to audit. */
object OkokRules {
    const val TARGET_PACKAGE = "com.chipsea.btcontrol"
    const val SUPPORTED_VERSION_CODE = 255L
    const val SUPPORTED_VERSION_NAME = "3.7.63"

    private const val INIT_ACTIVITY = "com.chipsea.btcontrol.InitActivity"
    const val HOME_ACTIVITY = "com.chipsea.btcontrol.homePage.NewMainActivity"
    private const val REPORT_ACTIVITY = "com.chipsea.btcontrol.homePage.ReportActivity"
    private const val BODY_MEASURE_REPORT_ACTIVITY =
        "com.chipsea.btcontrol.homePage.BodyMeasureReportActivity"
    private const val BLUETOOTH_UP_SCALE_ACTIVITY =
        "com.chipsea.btcontrol.homePage.BluetoothUpScaleActivity"

    const val HOME_POP_DIALOG_CLASS = "com.chipsea.code.view.dialog.HomePopDilog"

    /**
     * 首页要清掉的块（贝壳 2026-11-26 再次点名）：
     * banner 横幅、饮食横幅、AI 浮标、底部浮层、每日签到入口、热量记录卡、
     * 卡片上方的喇叭动画条（含右侧滚动文字和书本图标）、优惠购页签、
     * 卡片管理入口，以及卡片管理以下的整个「发现实惠好物 / 大家都在看」推荐流。
     */
    private val homeViews = setOf(
        "banner_card",
        "food_banner_view",
        "ai_float_entry",
        "pop_suspension_bottom_ll",
        "diet_card",
        "text_notify_view",
        "find_rb",
        "card_manager_iv",
        "find_card_container",
        // 首页信息流最下面那一段：标题行「查看更多 / 大家都在看」+ 推荐卡片流
        "top_blog_title",
        "punch_cards",
    )

    /** 这三个是底部页签，光隐藏会留一个空槽，要从父容器里摘掉再重排 */
    private val homeRemovedViews = setOf("find_rb")

    fun shouldRemoveView(resourceName: String, activityClassName: String): Boolean =
        activityClassName == HOME_ACTIVITY && resourceName in homeRemovedViews

    fun supportsVersion(versionCode: Long): Boolean = versionCode == SUPPORTED_VERSION_CODE

    fun shouldHideResource(resourceName: String, activityClassName: String): Boolean = when {
        activityClassName == INIT_ACTIVITY && resourceName == "ll_container" -> true
        activityClassName == HOME_ACTIVITY && resourceName in homeViews -> true
        activityClassName == REPORT_ACTIVITY &&
            resourceName == "body_status_edit_guide_hint_booble" -> true
        (activityClassName == REPORT_ACTIVITY || activityClassName == BODY_MEASURE_REPORT_ACTIVITY) &&
            resourceName == "containerLayout" -> true
        activityClassName == BLUETOOTH_UP_SCALE_ACTIVITY &&
            resourceName == "containerLayout" -> true
        else -> false
    }

    /** 没有固定 id 的文字入口，按文案识别：每日签到送好礼 / 大家都在看 */
    fun shouldHideText(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        if (value.contains("签到") && value.contains("好礼")) return true
        if (value.length <= 8 && value.startsWith("大家都在看")) return true
        return false
    }

    fun isHomeAdDialog(dialogClassName: String): Boolean = dialogClassName == HOME_POP_DIALOG_CLASS
}
