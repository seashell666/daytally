package com.beike.daytally.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.beike.daytally.util.Ui

/**
 * 桌面方块组件的进度环（只画环，数字由布局里的 TextView 负责）。
 * 位图按 160dp 绘制后由 ImageView fitCenter 等比缩放到实际格子里，
 * 这样 launcher 给多大都能撑满，不会被写死的 dp 尺寸卡住。
 */
object RingBitmap {

    private const val DENSITY_BASE = 160f

    fun draw(
        sizePx: Int,
        progress: Float,
        fgColor: Int,
        trackColor: Int
    ): Bitmap {
        val s = sizePx.coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val cx = s / 2f
        val cy = s / 2f
        // 留 1px 给抗锯齿，环外沿贴满画布
        val outerR = s * 0.5f - 1f
        val stroke = s * 0.055f

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = trackColor
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawCircle(cx, cy, outerR - stroke / 2f, track)

        val p = progress.coerceIn(0f, 1f)
        if (p > 0.004f) {
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                color = fgColor
                strokeCap = Paint.Cap.ROUND
            }
            val rr = outerR - stroke / 2f
            val rect = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
            canvas.drawArc(rect, -90f, 360f * p, false, arc)
        }
        return bmp
    }

    /** 位图基准尺寸：160dp 见方，够大，缩放不糊。 */
    fun defaultSize(ctx: Context): Int = Ui.wdp(ctx, DENSITY_BASE)
}
