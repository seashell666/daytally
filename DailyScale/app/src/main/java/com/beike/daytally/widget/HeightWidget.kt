package com.beike.daytally.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.beike.daytally.MainActivity
import com.beike.daytally.R
import com.beike.daytally.Repo
import com.beike.daytally.util.C

/**
 * 身高专用 4×2 大卡组件（原型左侧身高大卡）：
 * 显示今日身高 + 周二→周一 7 天条带；
 * 点卡片主体 → 进 App 趋势页的身高统计；
 * 点右上「去记录」→ 打开 App 今天页并直接弹出改身高弹窗（同时起输入法）。
 */
class HeightWidget : AppWidgetProvider() {

    companion object {
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_METRIC = "metric"
        private const val EXTRA_HEIGHT_SHEET = "height_sheet"
        private val WK = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        private val W_IDS = intArrayOf(R.id.hw_0, R.id.hw_1, R.id.hw_2, R.id.hw_3, R.id.hw_4, R.id.hw_5, R.id.hw_6)
        private val N_IDS = intArrayOf(R.id.hn_0, R.id.hn_1, R.id.hn_2, R.id.hn_3, R.id.hn_4, R.id.hn_5, R.id.hn_6)
        private val D_IDS = intArrayOf(R.id.hd_0, R.id.hd_1, R.id.hd_2, R.id.hd_3, R.id.hd_4, R.id.hd_5, R.id.hd_6)

        fun buildCard(context: Context, repo: Repo): RemoteViews {
            val h = repo.metric("height")
            val views = RemoteViews(context.packageName, R.layout.widget_height)

            val cs = repo.seriesColors(h)
            val today = repo.rawVal(h, repo.todayIdx)
            if (today == null) {
                views.setTextViewText(R.id.wh_val, "--.-")
                views.setTextColor(R.id.wh_val, C.INK3)
                views.setTextViewText(R.id.wh_sub, "今天还没记录")
            } else {
                views.setTextViewText(R.id.wh_val, repo.fmt(today, 1))
                views.setTextColor(R.id.wh_val, cs.getOrNull(repo.todayIdx) ?: C.INK_NONE)
                views.setTextViewText(R.id.wh_sub, "今天已记录 ${repo.fmt(today, 1)} 厘米")
            }
            views.setTextColor(R.id.wh_sub, C.INK3)

            // 7 天条带（固定周二 → 周一，今天之后留空）
            for (k in 0 until 7) {
                val (d, i, outside) = repo.stripSlot(k)
                views.setTextViewText(W_IDS[k], WK[(k + 2) % 7])
                views.setTextColor(
                    W_IDS[k],
                    if (i == repo.todayIdx) C.RED else 0xFF9C92AC.toInt()
                )
                val v = if (outside) null else repo.rawVal(h, i)
                if (v == null) {
                    views.setTextViewText(N_IDS[k], "--")
                    views.setTextColor(N_IDS[k], 0xFFB7AEC6.toInt())
                } else {
                    views.setTextViewText(N_IDS[k], repo.fmt(v, 1))
                    views.setTextColor(N_IDS[k], cs.getOrNull(i) ?: C.INK_NONE)
                }
                views.setTextViewText(D_IDS[k], String.format("%02d/%02d", d.monthValue, d.dayOfMonth))
                views.setTextColor(D_IDS[k], 0xFFA99FB8.toInt())
            }

            // 点主体：趋势页 · 身高
            val trendIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_PAGE, "trend")
                putExtra(EXTRA_METRIC, "height")
                setPackage(context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val trendPi = PendingIntent.getActivity(
                context, 3100, trendIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.wh_root, trendPi)

            // 点「去记录」：直接浮一层输入弹窗（不跳 App 页面）
            val sheetIntent = Intent(context, com.beike.daytally.HeightInputActivity::class.java).apply {
                setPackage(context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val sheetPi = PendingIntent.getActivity(
                context, 3101, sheetIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.wh_btn, sheetPi)
            return views
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val repo = Repo.get(context).also { it.ensureToday() }
        ids.forEach { manager.updateAppWidget(it, buildCard(context, repo)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
    }

    private fun refreshAll(context: Context) {
        val repo = Repo.get(context).also { it.ensureToday() }
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, HeightWidget::class.java))
            .forEach { manager.updateAppWidget(it, buildCard(context, repo)) }
    }
}
