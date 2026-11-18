package com.beike.daytally.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.beike.daytally.Repo

/**
 * 一处刷新所有桌面组件。
 * App 内加组 / 减组 / 改身高 / 改项目之后必须调用，否则桌面还停在旧数字。
 */
object WidgetRefresh {

    /**
     * 把「每组多少个」同步给桌面模块，方块上那颗浮动数字要用它显示 +12 / −11。
     * 不指定包名，桌面进程里模块动态注册的接收器才收得到。
     */
    private fun syncPer(ctx: Context, repo: Repo) {
        runCatching {
            val i = Intent("com.beike.daytally.PER_SYNC")
            listOf("push", "horse", "pullup", "custom").forEach { k ->
                i.putExtra("per_$k", repo.metric(k).per)
            }
            i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(i)
        }
    }

    fun refreshAll(ctx: Context) {
        val app = ctx.applicationContext
        val repo = Repo.get(app).also { it.ensureToday() }
        val manager = AppWidgetManager.getInstance(app) ?: return
        syncPer(app, repo)

        // 4 个 1×1 方块
        listOf(
            PushWidget::class.java, HorseWidget::class.java,
            PullupWidget::class.java, CustomWidget::class.java
        ).forEach { cls ->
            val ids = manager.getAppWidgetIds(ComponentName(app, cls))
            val key = when (cls) {
                PushWidget::class.java -> "push"
                HorseWidget::class.java -> "horse"
                PullupWidget::class.java -> "pullup"
                else -> "custom"
            }
            ids.forEach { id ->
                manager.updateAppWidget(id, WidgetProvider.buildTile(app, repo, key, cls))
            }
        }

        // 计数大卡
        manager.getAppWidgetIds(ComponentName(app, WeekWidget::class.java)).forEach { id ->
            manager.updateAppWidget(
                id, WeekWidget.buildCard(app, repo, WeekWidget.currentKey(app))
            )
        }

        // 身高大卡
        val hIds = manager.getAppWidgetIds(ComponentName(app, HeightWidget::class.java))
        if (hIds.isNotEmpty()) {
            hIds.forEach { id -> manager.updateAppWidget(id, HeightWidget.buildCard(app, repo)) }
        }
    }
}
