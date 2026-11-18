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
import com.beike.daytally.Sound
import com.beike.daytally.util.C
import com.beike.daytally.util.Ui

/**
 * 1×1 桌面方块组件。每个计数项目一个子类 receiver，绑定固定 key。
 * 点圆环：广播加一组；点卡片：打开 App 对应趋势页。
 */
open class WidgetProvider(private val key: String) : AppWidgetProvider() {

    companion object {
        const val ACTION_ADD = "com.beike.daytally.WIDGET_ADD"
        const val ACTION_REMOVE = "com.beike.daytally.WIDGET_REMOVE"
        const val EXTRA_KEY = "metric_key"
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_METRIC = "metric"
        private val STEP_RES = intArrayOf(
            R.drawable.bg_step_0, R.drawable.bg_step_1, R.drawable.bg_step_2, R.drawable.bg_step_3,
            R.drawable.bg_step_4, R.drawable.bg_step_5, R.drawable.bg_step_6, R.drawable.bg_step_7
        )

        /** 生成方块组件的 RemoteViews（桌面刷新与 App 内预览共用同一套渲染） */
        fun buildTile(
            context: Context,
            repo: Repo,
            key: String,
            target: Class<*> = PushWidget::class.java
        ): RemoteViews {
            val m = repo.metric(key)
            val groups = repo.todayRaw(m).toInt()
            val done = repo.isDone(m)
            val idx = minOf(groups, 7)
            val fgIdx = if (groups == 1) 0 else idx
            val fg = if (done) Color.WHITE else C.FG_WHITE[fgIdx]
            val cum = repo.cumTotal(m)
            val progress = if (m.goal > 0) (cum / m.goal).toFloat().coerceIn(0f, 1f) else 0f

            val views = RemoteViews(context.packageName, R.layout.widget_tile)

            if (done) {
                views.setInt(R.id.tile_root, "setBackgroundResource", R.drawable.bg_gold_v)
            } else {
                views.setInt(R.id.tile_root, "setBackgroundResource", STEP_RES[idx])
            }

            val size = RingBitmap.defaultSize(context)
            views.setImageViewBitmap(
                R.id.widget_ring,
                RingBitmap.draw(size, progress, fg, 0x3DFFFFFF)
            )
            views.setTextViewText(R.id.widget_cnt, groups.toString())
            views.setTextColor(R.id.widget_cnt, fg)
            views.setTextViewText(R.id.widget_lbl, m.name)
            views.setTextColor(R.id.widget_lbl, fg)

            // 点圆环：加一组
            val addIntent = Intent(context, target).apply {
                action = ACTION_ADD
                putExtra(EXTRA_KEY, key)
                setPackage(context.packageName)
            }
            val addPi = PendingIntent.getBroadcast(
                context, key.hashCode(), addIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_ring, addPi)

            // 点卡片：进 App 趋势页
            val openIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_PAGE, "trend")
                putExtra(EXTRA_METRIC, key)
                setPackage(context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openPi = PendingIntent.getActivity(
                context, 1000 + key.hashCode(), openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.tile_root, openPi)
            return views
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_ADD -> {
                val k = intent.getStringExtra(EXTRA_KEY) ?: key
                val repo = Repo.get(context).also { it.ensureToday() }
                repo.metric(k).let { repo.addGroup(it) }
                /* 点方块同时把大卡切到这个项目：两者是一套东西，原型里就是这么联动的 */
                WeekWidget.setCurrentKey(context, k)
                refreshAll(context)
            }
            ACTION_REMOVE -> {
                /* 来自 Vector 模块：桌面上长按方块 = 退一组 */
                val k = intent.getStringExtra(EXTRA_KEY) ?: key
                val repo = Repo.get(context).also { it.ensureToday() }
                repo.metric(k).let { repo.removeGroup(it) }
                Sound.get(context).remove()
                refreshAll(context)
            }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val repo = Repo.get(context).also { it.ensureToday() }
        ids.forEach { render(context, manager, repo, it) }
    }

    private fun render(context: Context, manager: AppWidgetManager, repo: Repo, id: Int) {
        manager.updateAppWidget(id, buildTile(context, repo, key, javaClass))
    }

    /** 刷新自己这类方块，并连带刷新大卡（数据变了、选中项目也可能变了） */
    private fun refreshAll(context: Context) {
        val repo = Repo.get(context).also { it.ensureToday() }
        val manager = AppWidgetManager.getInstance(context)
        val cn = ComponentName(context, javaClass)
        manager.getAppWidgetIds(cn).forEach { render(context, manager, repo, it) }

        val weekCn = ComponentName(context, WeekWidget::class.java)
        manager.getAppWidgetIds(weekCn).forEach { id ->
            manager.updateAppWidget(
                id, WeekWidget.buildCard(context, repo, WeekWidget.currentKey(context))
            )
        }
    }
}

class PushWidget : WidgetProvider("push")
class HorseWidget : WidgetProvider("horse")
class PullupWidget : WidgetProvider("pullup")
class CustomWidget : WidgetProvider("custom")
