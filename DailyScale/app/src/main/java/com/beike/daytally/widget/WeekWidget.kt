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
 * 桌面 4×2 大卡组件（对照原型 weekcard）：
 * 顶部 4 个可点 tab 切换项目、今日总量、sub 说明、7 天组数条带。
 * 达成（累计 >= 目标）时整卡切换金色横向渐变、文字转白。
 */
class WeekWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_TAB = "com.beike.daytally.WEEK_TAB"
        const val EXTRA_KEY = "week_key"
        private const val PREFS = "ds_widget"
        private const val PREF_KEY = "week_key"
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_METRIC = "metric"
        private val KEYS = listOf("push", "horse", "pullup", "custom")
        private val WK = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        private val TAB_IDS = intArrayOf(R.id.wtab_0, R.id.wtab_1, R.id.wtab_2, R.id.wtab_3)
        private val W_IDS = intArrayOf(R.id.ww_0, R.id.ww_1, R.id.ww_2, R.id.ww_3, R.id.ww_4, R.id.ww_5, R.id.ww_6)
        private val N_IDS = intArrayOf(R.id.wn_0, R.id.wn_1, R.id.wn_2, R.id.wn_3, R.id.wn_4, R.id.wn_5, R.id.wn_6)
        private val D_IDS = intArrayOf(R.id.wd_0, R.id.wd_1, R.id.wd_2, R.id.wd_3, R.id.wd_4, R.id.wd_5, R.id.wd_6)

        /** 当前大卡选中的项目（1×1 方块点击时也写这个键，用来联动） */
        fun currentKey(ctx: Context): String {
            val k = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_KEY, KEYS[0]) ?: KEYS[0]
            return if (k in KEYS) k else KEYS[0]
        }

        /** 仅改选中项目，不刷新（由调用方统一刷新所有组件） */
        fun setCurrentKey(ctx: Context, key: String) {
            if (key !in KEYS) return
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_KEY, key).apply()
        }

        /** 生成大卡的 RemoteViews（桌面刷新与 App 内预览共用同一套渲染） */
        fun buildCard(context: Context, repo: Repo, key: String): RemoteViews {
            val m = repo.metric(key)
            val done = repo.isDone(m)
            val views = RemoteViews(context.packageName, R.layout.widget_week)

            views.setInt(
                R.id.week_root, "setBackgroundResource",
                if (done) R.drawable.bg_gold_h else R.drawable.bg_week
            )

            // tabs
            KEYS.forEachIndexed { i, k ->
                val mm = repo.metric(k)
                views.setTextViewText(TAB_IDS[i], mm.name)
                if (k == key) {
                    views.setInt(TAB_IDS[i], "setBackgroundResource", R.drawable.bg_weektab_on)
                    views.setTextColor(TAB_IDS[i], Color.WHITE)
                } else {
                    views.setInt(TAB_IDS[i], "setBackgroundResource", R.drawable.bg_weektab_off)
                    views.setTextColor(TAB_IDS[i], C.PURPLE_DEEP)
                }
                val tabIntent = Intent(context, WeekWidget::class.java).apply {
                    action = ACTION_TAB
                    putExtra(EXTRA_KEY, k)
                    setPackage(context.packageName)
                }
                val pi = PendingIntent.getBroadcast(
                    context, 2000 + i, tabIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(TAB_IDS[i], pi)
            }

            // 今日总量
            val todayGroups = repo.todayRaw(m).toInt()
            val total = todayGroups * m.per
            views.setTextViewText(R.id.week_val, total.toString())
            views.setTextColor(R.id.week_val, if (done) Color.WHITE else C.INK)
            views.setTextViewText(R.id.week_unit, m.unit)
            views.setTextColor(R.id.week_unit, if (done) 0xD9FFFFFF.toInt() else C.INK2)
            views.setTextViewText(
                R.id.week_sub,
                "今天 $todayGroups 组 · 每组 ${m.per} ${m.unit} · 纵轴上限 ${m.maxGroups} 组"
            )
            views.setTextColor(R.id.week_sub, if (done) 0xD9FFFFFF.toInt() else C.INK3)

            // 7 天条带（固定周二 → 周一，今天之后留空）
            val cs = repo.seriesColors(m)
            for (k in 0 until 7) {
                val (d, i, outside) = repo.stripSlot(k)
                views.setTextViewText(W_IDS[k], WK[(k + 2) % 7])
                views.setTextColor(
                    W_IDS[k],
                    if (done) 0xD9FFFFFF.toInt()
                    else if (i == repo.todayIdx) C.RED else 0xFF9C92AC.toInt()
                )
                val g = if (outside) null else repo.rawVal(m, i)
                if (g == null) {
                    views.setTextViewText(N_IDS[k], "--")
                    views.setTextColor(N_IDS[k], if (done) 0xB3FFFFFF.toInt() else 0xFFB7AEC6.toInt())
                } else {
                    views.setTextViewText(N_IDS[k], g.toInt().toString())
                    views.setTextColor(N_IDS[k], if (done) Color.WHITE else (cs.getOrNull(i) ?: C.INK_NONE))
                }
                views.setTextViewText(D_IDS[k], String.format("%02d/%02d", d.monthValue, d.dayOfMonth))
                views.setTextColor(D_IDS[k], if (done) 0xCCFFFFFF.toInt() else 0xFFA99FB8.toInt())
            }

            // 点卡：打开 App 趋势页对应项目
            val openIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_PAGE, "trend")
                putExtra(EXTRA_METRIC, key)
                setPackage(context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openPi = PendingIntent.getActivity(
                context, 2100, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.week_root, openPi)
            return views
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TAB) {
            val k = intent.getStringExtra(EXTRA_KEY)
            if (k != null && k in KEYS) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(PREF_KEY, k).apply()
                refreshAll(context)
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val repo = Repo.get(context).also { it.ensureToday() }
        ids.forEach { render(context, manager, repo, it) }
    }

    private fun render(context: Context, manager: AppWidgetManager, repo: Repo, id: Int) {
        manager.updateAppWidget(id, buildCard(context, repo, currentKey(context)))
    }

    private fun refreshAll(context: Context) {
        val repo = Repo.get(context).also { it.ensureToday() }
        val manager = AppWidgetManager.getInstance(context)
        val cn = ComponentName(context, WeekWidget::class.java)
        manager.getAppWidgetIds(cn).forEach { id -> render(context, manager, repo, id) }
    }
}
