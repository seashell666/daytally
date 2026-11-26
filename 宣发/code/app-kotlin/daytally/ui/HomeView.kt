package com.beike.daytally.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.beike.daytally.R
import com.beike.daytally.Repo
import com.beike.daytally.Sound
import com.beike.daytally.model.Metric
import com.beike.daytally.util.C
import com.beike.daytally.util.Ui

interface HomeCallback {
    fun gotoProjects()
    fun openHeightSheet()
}

class HomeView(ctx: Context, val repo: Repo, val sound: Sound, val cb: HomeCallback) :
    LinearLayout(ctx) {

    private val WK = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /** 页头左上角「今天是 X 月 X 日」，底栏「项目」Tab 按住时跟着变红 */
    private var dateLine: TextView? = null

    /** 按住/松开时给日期行上色；外部（底栏 Tab 长按）也能调 */
    fun paintDate(active: Boolean) {
        dateLine?.setTextColor(if (active) C.RED else C.INK3)
    }

    init {
        orientation = VERTICAL
        setPadding(Ui.dp(ctx, 16f), Ui.dp(ctx, 4f), Ui.dp(ctx, 16f), 0)
    }

    fun render() {
        removeAllViews()
        addView(buildHeader())
        addView(buildHeightCard())
        /* 5 个间隙等距：身高卡之后 1 个、4 张计数卡的卡间 3 个、最后一张与底栏之间 1 个，
           全部交给 weight 弹簧均分剩余空间。原来卡间只有 8dp、底部却堆了 51dp，视觉上
           自定义卡离底栏远得离谱。 */
        addView(gap())
        val rest = repo.order.drop(1)
        rest.forEachIndexed { i, key ->
            addView(buildCounter(repo.metric(key)))
            if (i < rest.size - 1) addView(gap())
        }
        addView(gap())
    }

    /** 固定间距的 5 段等距间隙：高度不再靠 weight 抢，避免最后一段被剩余空间撑大 */
    private fun gap() = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(15f))
    }

    private fun dp(v: Float) = Ui.dp(context, v)

    private fun dayLabel(i: Int) = String.format("%02d/%02d",
        repo.days[i].monthValue, repo.days[i].dayOfMonth)
    private fun weekLabel(i: Int) = WK[repo.days[i].dayOfWeek.value % 7]

    private fun buildHeader(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(2f), dp(2f), 0)
        }
        val left = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val today = repo.days[repo.todayIdx]
        val date = TextView(context).apply {
            text = "今天是 ${today.monthValue} 月 ${today.dayOfMonth} 日"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * Ui.S)
            setTextColor(C.INK3)
            letterSpacing = 0.04f
        }
        dateLine = date
        paintDate(false)
        val title = TextView(context).apply {
            text = "今天做了多少"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C.INK)
            letterSpacing = -0.01f
            setPadding(0, dp(4f), 0, 0)
        }
        left.addView(date); left.addView(title)
        val btn = android.widget.FrameLayout(context).apply {
            background = context.getDrawable(R.drawable.bg_icon_circle)
            val s = dp(36f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { cb.gotoProjects() }
        }
        val btnIcon = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_calendar)
            setColorFilter(C.INK2, android.graphics.PorterDuff.Mode.SRC_IN)
            val s = dp(17f)
            layoutParams = android.widget.FrameLayout.LayoutParams(s, s, Gravity.CENTER)
        }
        btn.addView(btnIcon)
        row.addView(left); row.addView(btn)
        return row
    }

    private fun buildHeightCard(): View {
        val h = repo.metric("height")
        val hcs = repo.seriesColors(h)
        val hv = repo.valOf(h, repo.todayIdx)
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = context.getDrawable(R.drawable.bg_height_card)
            setPadding(dp(14f), dp(12f), dp(14f), dp(10f))
            // 高:计数卡 = 148 : 98 的固定比例，高度交给 weight 自适应，保证 5 段间隙绝对等距
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                148f
            )
            setOnClickListener { cb.openHeightSheet() }
        }
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tag = TextView(context).apply {
            text = "身高"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f * Ui.S)
            setTextColor(C.FLAT)
            letterSpacing = 0.06f
        }
        val numRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(2f), 0, 0)
        }
        val num = TextView(context).apply {
            if (hv == null) {
                text = "--.-"
                setTextColor(C.INK3)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f * Ui.S)
            } else {
                text = repo.fmt(hv, 1)
                setTextColor(hcs[repo.todayIdx] ?: C.INK_NONE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f * Ui.S)
            }
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine(true)
            maxLines = 1
            letterSpacing = -0.025f
        }
        val unit = TextView(context).apply {
            text = "厘米"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * Ui.S)
            setTextColor(C.INK2)
            letterSpacing = 0.04f
            setPadding(0, 0, 0, dp(5f))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(5f)
            layoutParams = lp
        }
        numRow.addView(num); numRow.addView(unit)
        left.addView(tag); left.addView(numRow)
        val pill = TextView(context).apply {
            text = "去记录"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * Ui.S)
            setBackgroundResource(R.drawable.bg_pill_btn)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        }
        top.addView(left); top.addView(pill)
        card.addView(top)
        // 弹簧：把 7 天条带压到底部（对齐原型 .strip 的 margin-top:auto）
        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        card.addView(buildStrip(h, hcs))
        return card
    }

    private fun buildStrip(m: Metric, cs: List<Int?>): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            weightSum = 7f
            setPadding(0, dp(10f), 0, 0)
        }
        for (k in 0 until 7) {
            val (d, i, outside) = repo.stripSlot(k)
            val v = if (outside) null else repo.valOf(m, i)
            val isToday = i == repo.todayIdx
            val cell = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val w = TextView(context).apply {
                text = WK[(k + 2) % 7]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * Ui.S)
                setTextColor(if (isToday) C.RED else Color.parseColor("#9C92AC"))
                if (isToday) setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            val n = TextView(context).apply {
                if (v == null) {
                    text = "--"; setTextColor(Color.parseColor("#B7AEC6"))
                } else {
                    text = repo.fmt(v, m.dec)
                    setTextColor(cs.getOrNull(i) ?: C.INK_NONE)
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * Ui.S)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(5f), 0, 0)
            }
            val dd = TextView(context).apply {
                text = String.format("%02d/%02d", d.monthValue, d.dayOfMonth)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f * Ui.S)
                setTextColor(if (isToday) C.INK2 else Color.parseColor("#A99FB8"))
                gravity = Gravity.CENTER
                setPadding(0, dp(4f), 0, 0)
            }
            cell.addView(w); cell.addView(n); cell.addView(dd)
            row.addView(cell)
        }
        return row
    }

    private fun buildCounter(m: Metric): View {
        val cs = repo.seriesColors(m)
        val done = repo.isDone(m)
        val todayRaw = repo.todayRaw(m).toInt()
        val yRaw = repo.rawVal(m, repo.todayIdx - 1)?.toInt() ?: 0
        val v = repo.todayRaw(m)

        val card = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.getDrawable(
                if (done) R.drawable.bg_gold_v else R.drawable.bg_counter
            )
            setPadding(dp(16f), dp(11f), dp(16f), dp(11f))
            // 与身高卡同一套 weight 比例（98 / 148），卡高自适应、5 段间隙等距
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                98f
            )
            elevation = dp(2f).toFloat()
        }
        val info = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nmRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nm = TextView(context).apply {
            text = m.name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C.INK)
            /* 项目名按 4 字占位：两字项目也让右侧紫色标签从同一条线起排 */
            minEms = 4
        }
        val tag = TextView(context).apply {
            val shortUnit = if (m.unit == "分钟") "分" else m.unit
            text = "${m.per} $shortUnit / 组"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * Ui.S)
            setTextColor(C.PURPLE)
            setBackgroundResource(R.drawable.bg_tag)
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(7f) }
        }
        nmRow.addView(nm); nmRow.addView(tag)

        val valRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(6f), 0, 0)
        }
        val big = TextView(context).apply {
            text = repo.fmt(v * m.per, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cs[repo.todayIdx] ?: C.INK_NONE)
            letterSpacing = -0.02f
        }
        val vu = TextView(context).apply {
            text = m.unit
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * Ui.S)
            setTextColor(C.INK3)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(4f)
            lp.bottomMargin = dp(2f)
            layoutParams = lp
        }
        valRow.addView(big); valRow.addView(vu)

        val g = TextView(context).apply {
            text = "今天 $todayRaw 组 · 昨天 $yRaw 组"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * Ui.S)
            setTextColor(C.INK3)
            letterSpacing = 0.02f
            setPadding(0, dp(4f), 0, 0)
        }
        info.addView(nmRow); info.addView(valRow); info.addView(g)

        val btn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.bg_addbtn)
            val s = dp(60f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener {
                if (repo.isDone(m)) return@setOnClickListener
                repo.addGroup(m); sound.add(); render()
            }
            setOnLongClickListener {
                repo.removeGroup(m); sound.remove(); render(); true
            }
        }
        val plus = TextView(context).apply {
            text = "+${m.per}"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = -0.01f
        }
        val small = TextView(context).apply {
            text = "加一组"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f * Ui.S)
            alpha = 0.86f
            gravity = Gravity.CENTER
            setPadding(0, dp(3f), 0, 0)
        }
        btn.addView(plus); btn.addView(small)

        card.addView(info)
        val btnLp = LinearLayout.LayoutParams(dp(60f), dp(60f))
        btnLp.marginStart = dp(12f)
        btn.layoutParams = btnLp
        card.addView(btn)
        return card
    }
}
