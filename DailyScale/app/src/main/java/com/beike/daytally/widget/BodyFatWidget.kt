package com.beike.daytally.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.beike.daytally.R
import com.beike.daytally.bodyfat.BodyFatEntryActivity
import com.beike.daytally.bodyfat.BodyFatPolicy
import com.beike.daytally.bodyfat.BodyFatStore
import com.beike.daytally.util.C
import java.time.LocalDate

/**
 * 体脂率 4×2 大卡：版式、尺寸、点击区域复用身高卡，只把单位换成 %。
 * 数据完全来自 BodyFatStore，不读 height，也不进计数项目。
 */
class BodyFatWidget : AppWidgetProvider() {

    companion object {
        private val WK = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        private val W_IDS = intArrayOf(R.id.hw_0, R.id.hw_1, R.id.hw_2, R.id.hw_3, R.id.hw_4, R.id.hw_5, R.id.hw_6)
        private val N_IDS = intArrayOf(R.id.hn_0, R.id.hn_1, R.id.hn_2, R.id.hn_3, R.id.hn_4, R.id.hn_5, R.id.hn_6)
        private val D_IDS = intArrayOf(R.id.hd_0, R.id.hd_1, R.id.hd_2, R.id.hd_3, R.id.hd_4, R.id.hd_5, R.id.hd_6)

        fun buildCard(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_body_fat)
            val records = BodyFatStore.all(context)
            val latest = records.lastOrNull()

            views.setTextViewText(R.id.wh_unit, BodyFatPolicy.UNIT)
            if (latest == null) {
                views.setTextViewText(R.id.wh_val, "--.-")
                views.setTextColor(R.id.wh_val, C.INK3)
                views.setTextViewText(R.id.wh_sub, "今天还没记录")
            } else {
                views.setTextViewText(R.id.wh_val, BodyFatPolicy.format(latest.value))
                val previous = records.dropLast(1).lastOrNull()
                views.setTextColor(
                    R.id.wh_val,
                    when {
                        previous == null -> C.INK_NONE
                        latest.value > previous.value + 1e-9 -> C.INK_UP
                        latest.value < previous.value - 1e-9 -> C.INK_DN
                        else -> C.INK_NONE
                    }
                )
                val today = LocalDate.now()
                val whenText = if (BodyFatPolicy.localDate(latest.measuredAt) == today) {
                    "今天 " + BodyFatPolicy.timeLabel(latest.measuredAt)
                } else {
                    BodyFatPolicy.dateTimeLabel(latest.measuredAt)
                }
                views.setTextViewText(
                    R.id.wh_sub,
                    "$whenText · ${BodyFatPolicy.sourceLabel(latest.source)}"
                )
            }
            views.setTextColor(R.id.wh_sub, C.INK3)

            val slots = BodyFatStore.strip(context)
            for (k in 0 until 7) {
                val slot = slots[k]
                views.setTextViewText(W_IDS[k], WK[(k + 2) % 7])
                views.setTextColor(
                    W_IDS[k],
                    if (slot.date == LocalDate.now()) C.RED else 0xFF9C92AC.toInt()
                )
                val r = slot.record
                if (r == null) {
                    views.setTextViewText(N_IDS[k], "--")
                    views.setTextColor(N_IDS[k], 0xFFB7AEC6.toInt())
                } else {
                    views.setTextViewText(N_IDS[k], BodyFatPolicy.format(r.value))
                    val prev = records.lastOrNull {
                        BodyFatPolicy.localDate(it.measuredAt) < slot.date
                    }
                    views.setTextColor(
                        N_IDS[k],
                        when {
                            prev == null -> C.INK_NONE
                            r.value > prev.value + 1e-9 -> C.INK_UP
                            r.value < prev.value - 1e-9 -> C.INK_DN
                            else -> C.INK_NONE
                        }
                    )
                }
                views.setTextViewText(
                    D_IDS[k],
                    String.format("%02d/%02d", slot.date.monthValue, slot.date.dayOfMonth)
                )
                views.setTextColor(D_IDS[k], 0xFFA99FB8.toInt())
            }

            val entryIntent = Intent(context, BodyFatEntryActivity::class.java).apply {
                setPackage(context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val rootPi = PendingIntent.getActivity(
                context, 3200, entryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val btnPi = PendingIntent.getActivity(
                context, 3201, entryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.wh_root, rootPi)
            views.setOnClickPendingIntent(R.id.wh_btn, btnPi)
            return views
        }

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val cn = ComponentName(context, BodyFatWidget::class.java)
            manager.getAppWidgetIds(cn).forEach { id ->
                manager.updateAppWidget(id, buildCard(context))
            }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildCard(context)) }
    }
}
