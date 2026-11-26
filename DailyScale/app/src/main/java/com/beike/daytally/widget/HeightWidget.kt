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
import com.beike.daytally.bodyfat.BodyFatEntryActivity
import com.beike.daytally.util.C

/**
 * 肌肉重量专用 4×2 大卡组件。
 *
 * 类名和布局名继续沿用 HeightWidget/widget_height，是为了让已经放在桌面的旧组件
 * 在升级后还能原地刷新，不用用户重新添加；显示内容和单位已切换为肌肉重量/公斤。
 * 点卡片主体 → 进 App 趋势页的肌肉统计；
 * 点右上「去记录」→ 走日刻的 OKOK 测量入口。
 */
class HeightWidget : AppWidgetProvider() {

    companion object {
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_METRIC = "metric"
        private val WK = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        private val W_IDS = intArrayOf(R.id.hw_0, R.id.hw_1, R.id.hw_2, R.id.hw_3, R.id.hw_4, R.id.hw_5, R.id.hw_6)
        private val N_IDS = intArrayOf(R.id.hn_0, R.id.hn_1, R.id.hn_2, R.id.hn_3, R.id.hn_4, R.id.hn_5, R.id.hn_6)
        private val D_IDS = intArrayOf(R.id.hd_0, R.id.hd_1, R.id.hd_2, R.id.hd_3, R.id.hd_4, R.id.hd_5, R.id.hd_6)

        fun buildCard(context: Context, repo: Repo): RemoteViews {
            val h = repo.metric("muscle")
            val views = RemoteViews(context.packageName, R.layout.widget_height)
            views.setTextViewText(R.id.wh_unit, "公斤")

            val cs = repo.seriesColors(h)
            val today = repo.rawVal(h, repo.todayIdx)
            if (today == null) {
                views.setTextViewText(R.id.wh_val, "--.-")
                views.setTextColor(R.id.wh_val, C.INK3)
                views.setTextViewText(R.id.wh_sub, "今天还没记录")
            } else {
                views.setTextViewText(R.id.wh_val, repo.fmt(today, 1))
                views.setTextColor(R.id.wh_val, cs.getOrNull(repo.todayIdx) ?: C.INK_NONE)
                // 原型口径：11/18 已记录
                val d = java.time.LocalDate.now()
                views.setTextViewText(R.id.wh_sub,
                    String.format("%02d/%02d 已记录", d.monthValue, d.dayOfMonth))
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

            // 点主体：趋势页 · 肌肉重量
            val trendIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_PAGE, "trend")
                putExtra(EXTRA_METRIC, "muscle")
                setPackage(context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val trendPi = PendingIntent.getActivity(
                context, 3100, trendIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.wh_root, trendPi)

            // 点「去记录」：走 OKOK 正常称重入口，测完自动回传体脂和肌肉重量
            val sheetIntent = Intent(context, BodyFatEntryActivity::class.java).apply {
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
