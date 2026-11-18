package com.beike.daytally.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.beike.daytally.R
import com.beike.daytally.Repo
import com.beike.daytally.Sound
import com.beike.daytally.model.Metric
import com.beike.daytally.util.C
import com.beike.daytally.util.Ui

interface TrendCallback { fun gotoHome() }

class TrendView(ctx: Context, val repo: Repo, val sound: Sound, val cb: TrendCallback) :
    LinearLayout(ctx) {

    private var pos = 1
    private lateinit var segRow: LinearLayout
    private lateinit var stats: LinearLayout
    private lateinit var chart: ChartView
    private lateinit var detail: LinearLayout
    private var downX = 0f
    private var downY = 0f

    init {
        orientation = VERTICAL
        build()
    }

    private fun dp(v: Float) = Ui.dp(context, v)
    private fun metric() = repo.metric(repo.order[pos])

    /** 外部（桌面组件）跳转：切到指定项目 */
    fun openMetric(key: String) {
        val i = repo.order.indexOf(key)
        if (i >= 0) { pos = i; render() }
    }

    private fun build() {
        addView(buildHeader())
        segRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = context.getDrawable(R.drawable.bg_seg)
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val segWrap = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(11f), dp(18f), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(segRow)
        }
        addView(segWrap)

        val sv = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isVerticalScrollBarEnabled = false
        }
        val inner = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, 0, 0, dp(16f))
        }
        stats = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(18f), dp(14f), dp(18f), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        inner.addView(stats)
        chart = ChartView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(8f), dp(12f), dp(8f), 0) }
        }
        inner.addView(chart)
        detail = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(6f), dp(18f), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        inner.addView(detail)
        sv.addView(inner)
        addView(sv)
        render()
    }

    private fun buildHeader(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18f), dp(2f), dp(18f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val back = android.widget.FrameLayout(context).apply {
            background = context.getDrawable(R.drawable.bg_icon_circle)
            val s = dp(36f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { cb.gotoHome() }
        }
        val backIcon = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_chev_left)
            setColorFilter(C.INK2, android.graphics.PorterDuff.Mode.SRC_IN)
            val s = dp(17f)
            layoutParams = android.widget.FrameLayout.LayoutParams(s, s, Gravity.CENTER)
        }
        back.addView(backIcon)
        val title = TextView(context).apply {
            text = "趋势"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C.INK)
            gravity = Gravity.CENTER
            letterSpacing = -0.01f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(10f); marginEnd = dp(10f) }
        }
        val spacer = View(context).apply {
            val s = dp(36f)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        row.addView(back); row.addView(title); row.addView(spacer)
        return row
    }

    fun render() {
        val m = metric()
        // seg：整体一条，选中白底深字
        segRow.removeAllViews()
        repo.order.forEachIndexed { i, key ->
            val mm = repo.metric(key)
            val on = i == pos
            val b = TextView(context).apply {
                text = mm.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * Ui.S)
                gravity = Gravity.CENTER
                setPadding(0, dp(7f), 0, dp(7f))
                setTextColor(if (on) C.INK else C.INK2)
                if (on) {
                    setTypeface(typeface, Typeface.BOLD)
                    setBackgroundResource(R.drawable.bg_seg_on)
                    elevation = dp(2f).toFloat()
                } else {
                    setBackgroundColor(Color.TRANSPARENT)
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { if (i > 0) marginStart = dp(2f) }
                setOnClickListener { pos = i; sound.tap(); render() }
            }
            segRow.addView(b)
        }
        renderStats(m)
        chart.show(m, repo)
        renderDetail(m)
    }

    private fun statBlock(label: String, value: String, unit: String, color: Int, showLineBefore: Boolean): View {
        val wrap = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 三栏都占一条 1px 位（第一栏用透明色），否则第一栏比后两栏宽、内容会偏左
        wrap.addView(View(context).apply {
            setBackgroundColor(
                if (showLineBefore) Color.parseColor("#217860AA") else Color.TRANSPARENT
            )
            layoutParams = LinearLayout.LayoutParams(dp(1f), dp(34f))
        })
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val rowV = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val v = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
            letterSpacing = -0.02f
        }
        val u = TextView(context).apply {
            text = unit
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * Ui.S)
            setTextColor(C.INK3)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(2f); lp.bottomMargin = dp(2f)
            layoutParams = lp
        }
        rowV.addView(v); rowV.addView(u)
        val cap = TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * Ui.S)
            setTextColor(C.INK3)
            letterSpacing = 0.02f
            setPadding(0, dp(5f), 0, 0)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        col.addView(rowV); col.addView(cap)
        wrap.addView(col)
        return wrap
    }

    private fun renderStats(m: Metric) {
        stats.removeAllViews()
        // 原型 trendUnit：身高=厘米，计数项目一律「组」（不是每组单位）
        val trendUnit = if (m.per == 0) "厘米" else "组"
        var maxV: Double? = null; var maxD = ""
        var minV: Double? = null; var minD = ""
        for (i in 0 until Repo.N) {
            val v = repo.rawVal(m, i) ?: continue
            val d = repo.days[i]
            val ds = String.format("%02d/%02d", d.monthValue, d.dayOfMonth)
            if (maxV == null || v > maxV) { maxV = v; maxD = ds }
            if (minV == null || v < minV) { minV = v; minD = ds }
        }
        // 7 天变化：今天（无则回退最近非空）对比 7 天前
        var todayI = repo.todayIdx
        if (repo.rawVal(m, todayI) == null) todayI = repo.prevIndex(m, repo.todayIdx)
        val baseVal = if (todayI >= 0) (repo.rawVal(m, todayI - 7) ?: 0.0) else 0.0
        val chg = if (todayI >= 0) (repo.rawVal(m, todayI) ?: 0.0) - baseVal else 0.0
        val cs = repo.seriesColors(m)
        val diffColor = when {
            chg > 1e-9 -> C.INK_UP
            chg < -1e-9 -> C.INK_DN
            else -> cs.getOrNull(todayI) ?: C.INK_NONE
        }
        stats.addView(statBlock(maxD.ifEmpty { "--" }.let { "最高 · $it" },
            maxV?.let { repo.fmt(it, m.dec) } ?: "--", trendUnit, C.INK, false))
        stats.addView(statBlock("7 天变化",
            (if (chg > 0) "+" else "") + repo.fmt(chg, m.dec), trendUnit, diffColor, true))
        stats.addView(statBlock(minD.ifEmpty { "--" }.let { "最低 · $it" },
            minV?.let { repo.fmt(it, m.dec) } ?: "--", trendUnit, C.INK, true))
    }

    private fun renderDetail(m: Metric) {
        detail.removeAllViews()
        val cs = repo.seriesColors(m)
        val unit = if (m.per == 0) "厘米" else "组"
        for (k in repo.todayIdx downTo 0) {
            val raw = repo.rawVal(m, k)
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9f), 0, dp(9f))
            }
            val dt = TextView(context).apply {
                text = String.format("%02d/%02d", repo.days[k].monthValue, repo.days[k].dayOfMonth)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * Ui.S)
                setTextColor(C.INK3)
                layoutParams = LinearLayout.LayoutParams(dp(46f), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            row.addView(dt)
            if (raw == null) {
                val vv = TextView(context).apply {
                    text = "--"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * Ui.S)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#B7AEC6"))
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginStart = dp(10f) }
                }
                val dl = TextView(context).apply {
                    text = "未记录"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * Ui.S)
                    setTextColor(Color.parseColor("#B7AEC6"))
                }
                row.addView(vv); row.addView(dl)
            } else {
                val vv = TextView(context).apply {
                    text = repo.fmt(raw, m.dec)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * Ui.S)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (k == repo.todayIdx) C.INK else Color.parseColor("#4C4459"))
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginStart = dp(10f) }
                }
                val pi = repo.prevIndex(m, k)
                var d = "—"
                if (pi >= 0) {
                    val dv = raw - (repo.rawVal(m, pi) ?: 0.0)
                    d = (if (dv > 0) "+" else "") + repo.fmt(dv, m.dec)
                }
                val dl = TextView(context).apply {
                    text = "$d $unit"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * Ui.S)
                    setTextColor(cs.getOrNull(k) ?: C.INK_NONE)
                }
                row.addView(vv); row.addView(dl)
            }
            val line = View(context).apply {
                setBackgroundColor(Color.parseColor("#147860AA"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)
                )
            }
            detail.addView(row); detail.addView(line)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(ev.x - downX) > dp(18f) &&
                    Math.abs(ev.x - downX) > Math.abs(ev.y - downY)) return true
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                if (Math.abs(dx) > dp(45f)) {
                    if (dx < 0 && pos < repo.order.size - 1) { pos++; sound.tap(); render() }
                    else if (dx > 0 && pos > 0) { pos--; sound.tap(); render() }
                }
            }
        }
        return true
    }
}
