package com.beike.daytally.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.beike.daytally.Repo
import com.beike.daytally.model.Metric
import com.beike.daytally.util.C

/**
 * 趋势折线图：整幅按原型 SVG 的 viewBox 320×200 等比缩放，
 * 与 HTML 里 preserveAspectRatio="xMidYMid meet" 的表现完全一致。
 * 单色折线 + 三段面积渐变 + 虚线网格 + 纵轴刻度 + 仅末点双层圆点。
 */
class ChartView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var repo: Repo? = null
    var metric: Metric? = null

    private val VW = 320f
    private val VH = 200f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var scale = 1f

    init {
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeCap = Paint.Cap.ROUND
        linePaint.strokeJoin = Paint.Join.ROUND

        dashPaint.color = Color.parseColor("#217860AA")
        solidPaint.color = Color.parseColor("#297860AA")
        textPaint.color = Color.parseColor("#A296B2")
        tickPaint.color = Color.parseColor("#A296B2")
    }

    fun show(m: Metric, repo: Repo) {
        this.metric = m; this.repo = repo; invalidate()
    }

    private fun axis(): Pair<Double, Double> {
        val m = metric!!
        return if (m.per == 0) 47.0 to 72.0 else 0.0 to m.maxGroups.toDouble()
    }

    private fun ticks(lo: Double, hi: Double): List<Double> {
        val m = metric!!
        return if (m.per == 0) listOf(47.0, 53.0, 59.0, 65.0, 72.0)
        else (0..4).map { lo + (hi - lo) * it / 4 }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = Math.round(w * VH / VW)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val repo = repo ?: return
        val m = metric ?: return
        scale = width / VW

        val x0 = 30f * scale
        val x1 = 308f * scale
        val y0 = 18f * scale
        val y1 = 148f * scale
        val plotH = y1 - y0
        val (lo, hi) = axis()

        linePaint.strokeWidth = 2.3f * scale
        dashPaint.strokeWidth = 1f
        solidPaint.strokeWidth = 1f
        dashPaint.pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(3f * scale, 3f * scale), 0f
        )
        textPaint.textSize = 9f * scale
        tickPaint.textSize = 8.5f * scale

        fun x(i: Int) = x0 + (x1 - x0) * i / (Repo.N - 1)
        fun y(v: Double) = (y1 - (v - lo) / (hi - lo) * (y1 - y0)).toFloat()

        // 纵轴刻度 + 横向网格线（4 条虚线 + 1 条实线底，对齐原型 ylines）
        ticks(lo, hi).forEach { t ->
            val yy = y(t)
            canvas.drawLine(x0, yy, x1, yy, if (t == lo) solidPaint else dashPaint)
            val label = if (m.per == 0) String.format("%.0f", t) else round1(t)
            tickPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(label, x0 - 5f * scale, yy + 3f * scale, tickPaint)
        }

        // 数据点（原始值：肌肉=公斤，计数=组数）
        val pts = mutableListOf<FloatArray>()
        for (i in 0 until Repo.N) {
            val v = repo.rawVal(m, i) ?: continue
            pts.add(floatArrayOf(x(i), y(v)))
        }

        val lineColor = if (m.per == 0) C.PURPLE else C.RED
        val r = Color.red(lineColor); val g = Color.green(lineColor); val b = Color.blue(lineColor)
        linePaint.color = lineColor

        if (pts.size >= 2) {
            val smooth = smoothPath(pts, scale)
            val area = Path(smooth)
            area.lineTo(pts.last()[0], y1)
            area.lineTo(pts.first()[0], y1)
            area.close()
            areaPaint.shader = LinearGradient(
                0f, y0, 0f, y1,
                intArrayOf(
                    Color.argb((255 * .28f).toInt(), r, g, b),
                    Color.argb((255 * .07f).toInt(), r, g, b),
                    Color.argb(0, r, g, b)
                ),
                floatArrayOf(0f, .7f, 1f),
                Shader.TileMode.CLAMP
            )
            areaPaint.style = Paint.Style.FILL
            canvas.drawPath(area, areaPaint)
            canvas.drawPath(smooth, linePaint)

            // 末点：halo + 实心（原型 r=8 opacity .14 / r=4.2）
            val lx = pts.last()[0]; val ly = pts.last()[1]
            halo.color = Color.argb((255 * .14f).toInt(), r, g, b)
            canvas.drawCircle(lx, ly, 8f * scale, halo)
            dot.color = lineColor
            canvas.drawCircle(lx, ly, 4.2f * scale, dot)
        }

        // 横轴日期（原型 y = H - 6，font-size 9）
        textPaint.textAlign = Paint.Align.CENTER
        listOf(0, 5, 10, 15, 20, 25, Repo.N - 1).forEach { i ->
            val d = repo.days[i]
            val label = String.format("%02d/%02d", d.monthValue, d.dayOfMonth)
            canvas.drawText(label, x(i), height - 6f * scale, textPaint)
        }
    }

    private fun round1(v: Double): String {
        val rr = Math.round(v * 10) / 10.0
        return if (rr == rr.toLong().toDouble()) rr.toLong().toString() else rr.toString()
    }

    private fun smoothPath(pts: List<FloatArray>, s: Float): Path {
        val p = Path()
        p.moveTo(pts[0][0], pts[0][1])
        val t = 0.16f
        for (i in 0 until pts.size - 1) {
            val p0 = pts.getOrElse(i - 1) { pts[i] }
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts.getOrElse(i + 2) { p2 }
            val c1x = p1[0] + (p2[0] - p0[0]) * t
            val c1y = p1[1] + (p2[1] - p0[1]) * t
            val c2x = p2[0] - (p3[0] - p1[0]) * t
            val c2y = p2[1] - (p3[1] - p1[1]) * t
            p.cubicTo(c1x, c1y, c2x, c2y, p2[0], p2[1])
        }
        return p
    }
}
