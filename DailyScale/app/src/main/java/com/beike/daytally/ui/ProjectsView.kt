package com.beike.daytally.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.beike.daytally.R
import com.beike.daytally.Repo
import com.beike.daytally.Sound
import com.beike.daytally.model.Metric
import com.beike.daytally.util.C
import com.beike.daytally.util.Ui
import java.text.DecimalFormat

interface ProjectsCallback { fun gotoHome() }

class ProjectsView(ctx: Context, val repo: Repo, val sound: Sound, val cb: ProjectsCallback) : FrameLayout(ctx) {

    private val handler = Handler(Looper.getMainLooper())
    private val content: LinearLayout
    private val overlay: FrameLayout
    private val nf = DecimalFormat("#,###")
    private var lastTap = 0L
    private var cardClickTimer: Runnable? = null

    init {
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16f), Ui.dp(ctx, 4f), Ui.dp(ctx, 16f), Ui.dp(ctx, 16f))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        overlay = FrameLayout(context).apply {
            visibility = GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        addView(content); addView(overlay)
        render()
    }

    private fun dp(v: Float) = Ui.dp(context, v)

    fun render() {
        content.removeAllViews()
        content.addView(buildHeader())
        repo.order.forEach { key -> content.addView(buildCard(repo.metric(key))) }
    }

    private fun buildHeader(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(2f), dp(2f), dp(10f))
        }
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sub = TextView(context).apply {
            text = "所有记录项目"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * Ui.S)
            setTextColor(C.INK3)
            letterSpacing = 0.04f
        }
        val title = TextView(context).apply {
            text = "项目"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C.INK)
            letterSpacing = -0.01f
            setPadding(0, dp(4f), 0, 0)
        }
        left.addView(sub); left.addView(title)
        val back = android.widget.FrameLayout(context).apply {
            background = context.getDrawable(R.drawable.bg_icon_circle)
            val s = dp(36f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { cb.gotoHome() }
        }
        val backIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_chev_left)
            setColorFilter(C.INK2, PorterDuff.Mode.SRC_IN)
            val s = dp(17f)
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.CENTER)
        }
        back.addView(backIcon)
        row.addView(left); row.addView(back)
        return row
    }

    private fun iconRes(key: String) = when (key) {
        "height" -> R.drawable.ic_proj_height
        "push" -> R.drawable.ic_proj_push
        "horse" -> R.drawable.ic_proj_horse
        "pullup" -> R.drawable.ic_proj_pullup
        else -> R.drawable.ic_proj_custom
    }

    /** 主色段落（紫色加粗），颜色随卡片状态变化 */
    private fun spanMeta(parts: List<Pair<String, Boolean>>, hi: Int): SpannableString {
        val sb = StringBuilder()
        parts.forEach { sb.append(it.first) }
        val ss = SpannableString(sb.toString())
        var i = 0
        parts.forEach { (t, strong) ->
            if (strong) {
                ss.setSpan(ForegroundColorSpan(hi), i, i + t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ss.setSpan(StyleSpan(Typeface.BOLD), i, i + t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            i += t.length
        }
        return ss
    }

    private fun buildCard(m: Metric): View {
        val done = repo.isDone(m)
        val editable = m.per > 0
        val base = if (done) C.GOLD_TEXT else C.INK3
        val hi = if (done) C.GOLD_TEXT else C.PURPLE_DEEP
        val nameColor = if (done) C.GOLD_TEXT else C.INK

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.getDrawable(
                if (done) R.drawable.bg_gold_v else R.drawable.bg_card_white
            )
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            // 原型 .proj-card 基线高 ≈ 85px（42 图标 + 上下 14 + 两行 meta）；
            // 用 WRAP_CONTENT + minimumHeight，字号放大后 meta 折行时卡片自己变高，不会被切
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
            minimumHeight = dp(85f)
            elevation = dp(2f).toFloat()
        }

        val icBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = context.getDrawable(if (done) R.drawable.bg_gold_ic else R.drawable.bg_proj_ic)
            val s = dp(42f)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        val icon = ImageView(context).apply {
            setImageResource(iconRes(m.key))
            setColorFilter(if (done) C.GOLD_TEXT else C.PURPLE_DEEP, PorterDuff.Mode.SRC_IN)
            val s = dp(21f)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        icBox.addView(icon)

        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12f) }
        }
        val nm = TextView(context).apply {
            text = m.name + if (done) " · 已达成" else ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(nameColor)
            includeFontPadding = false
            setLineSpacing(0f, 1.3f)
        }
        info.addView(nm)

        if (editable) {
            val cum = repo.cumTotal(m)
            val line1 = spanMeta(listOf(
                "每组 " to false,
                "${m.per} ${m.unit}" to true,
                " · 累计 " to false,
                "${nf.format(Math.round(cum))} ${m.unit}" to true,
                " · 目标 ${nf.format(m.goal)}" to false
            ), hi)
            val line2 = spanMeta(listOf(
                "趋势纵轴 " to false,
                "0 ~ ${m.maxGroups} 组" to true
            ), hi)
            info.addView(metaView(line1, dp(4f), base))
            info.addView(metaView(line2, dp(3f), base))
        } else {
            val days = m.data.size
            val line1 = spanMeta(listOf(
                "已记录 " to false,
                "$days" to true,
                " 天 · 精确到 0.1 ${m.unit}" to false
            ), hi)
            val line2 = spanMeta(listOf(
                "趋势纵轴固定 " to false,
                "172 ~ 183 厘米" to true
            ), hi)
            info.addView(metaView(line1, dp(4f), base))
            info.addView(metaView(line2, dp(3f), base))
        }

        card.addView(icBox); card.addView(info)

        if (editable) {
            val chev = ImageView(context).apply {
                setImageResource(R.drawable.ic_chev)
                setColorFilter(if (done) C.GOLD_TEXT else Color.parseColor("#B3A7C8"), PorterDuff.Mode.SRC_IN)
                val s = dp(18f)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = dp(10f) }
            }
            card.addView(chev)
        }

        if (editable) {
            // 单击延迟 250ms 弹纵轴上限，期间来双击则改开通用编辑（与原型一致）
            card.setOnClickListener {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTap < 300L) {
                    cardClickTimer?.let { handler.removeCallbacks(it) }
                    cardClickTimer = null
                    sound.tap(); openEditSheet(m)
                } else {
                    val r = Runnable { cardClickTimer = null; openMaxGroupsSheet(m) }
                    cardClickTimer = r
                    handler.postDelayed(r, 250L)
                }
                lastTap = now
            }
            card.setOnLongClickListener { onCardLong(m); true }
        }
        return card
    }

    private fun metaView(text: CharSequence, top: Int, color: Int): TextView = TextView(context).apply {
        setText(text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * Ui.S)
        setTextColor(color)
        includeFontPadding = false
        setLineSpacing(0f, 1.3f)
        setPadding(0, top, 0, 0)
    }

    // ---------- 手势 ----------
    private fun onCardLong(m: Metric) {
        sound.tap()
        openConfirmReset(m)
    }

    // ---------- 弹层骨架 ----------
    private fun showOverlay(sheet: View, onMask: () -> Unit = {}) {
        overlay.removeAllViews()
        val mask = View(context).apply {
            background = context.getDrawable(R.drawable.bg_mask)
            setOnClickListener { closeOverlay(onMask) }
        }
        val box = FrameLayout(context).apply {
            addView(sheet)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { setMargins(dp(28f), dp(28f), dp(28f), dp(28f)) }
        }
        overlay.addView(mask)
        overlay.addView(box)
        overlay.visibility = VISIBLE
    }

    private fun closeOverlay(onLeave: () -> Unit) {
        onLeave()
        overlay.visibility = GONE
        overlay.removeAllViews()
    }

    private fun sheetShell(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.bg_sheet)
            setPadding(dp(20f), dp(22f), dp(20f), dp(22f))
        }
    }

    private fun sheetTitle(t: String): TextView = TextView(context).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * Ui.S)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(C.INK)
        setPadding(0, 0, 0, dp(14f))
    }

    private fun field(hint: String, value: String): EditText {
        return EditText(context).apply {
            setText(value)
            setHint(hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * Ui.S)
            setTextColor(C.INK)
            setHintTextColor(C.INK3)
            background = context.getDrawable(R.drawable.bg_proj_ic)
            setPadding(dp(13f), dp(11f), dp(13f), dp(11f))
            gravity = Gravity.CENTER
            setSelectAllOnFocus(true)
        }
    }

    private fun debounceSave(et: EditText, ms: Long, valid: () -> Boolean, apply: () -> Unit) {
        et.addTextChangedListener(object : TextWatcher {
            private var run: Runnable? = null
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                run?.let { handler.removeCallbacks(it) }
                run = Runnable { if (valid()) apply() }
                handler.postDelayed(run!!, ms)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun space(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(12f))
    }

    private fun openMaxGroupsSheet(m: Metric) {
        val shell = sheetShell()
        shell.addView(sheetTitle("趋势纵轴上限"))
        val et = field("1 ~ 999（组）", m.maxGroups.toString())
        val valid = {
            val v = et.text.toString().trim().toIntOrNull(); v != null && v in 1..999
        }
        val commit = {
            m.maxGroups = et.text.toString().trim().toInt()
            repo.save(); sound.save(); render()
        }
        debounceSave(et, 700, valid) { commit() }
        et.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_DONE) { if (valid()) commit(); closeOverlay {}; true } else false
        }
        shell.addView(et)
        showOverlay(shell) { if (valid()) commit() }
    }

    private fun openEditSheet(m: Metric) {
        val shell = sheetShell()
        shell.addView(sheetTitle("编辑项目"))
        var unit = m.unit
        val nameEt = field("项目名（≤6 字）", m.name)
        val perEt = field("每组值 1 ~ 999", m.per.toString())
        shell.addView(nameEt); shell.addView(space())
        shell.addView(perEt); shell.addView(space())

        val unitRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val opts = listOf("个", "分钟")
        val unitBtns = mutableListOf<TextView>()
        opts.forEach { u ->
            val b = TextView(context).apply {
                text = u
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * Ui.S)
                setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
                val on = u == unit
                setBackgroundResource(if (on) R.drawable.bg_tab_pill_on else R.drawable.bg_proj_ic)
                setTextColor(if (on) Color.WHITE else C.PURPLE_DEEP)
                setOnClickListener {
                    unit = u
                    unitBtns.forEach { bb ->
                        val bo = bb.text == unit
                        bb.setBackgroundResource(if (bo) R.drawable.bg_tab_pill_on else R.drawable.bg_proj_ic)
                        bb.setTextColor(if (bo) Color.WHITE else C.PURPLE_DEEP)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(10f) }
            }
            unitBtns.add(b)
        }
        unitBtns.forEach { unitRow.addView(it) }
        shell.addView(unitRow)

        val valid = {
            val n = nameEt.text.toString().trim()
            val p = perEt.text.toString().trim().toIntOrNull()
            n.isNotEmpty() && n.length <= 6 && p != null && p in 1..999
        }
        val commit = {
            m.name = nameEt.text.toString().trim()
            m.per = perEt.text.toString().trim().toInt()
            m.unit = unit
            repo.save(); sound.save(); render()
        }
        debounceSave(nameEt, 700, valid) { commit() }
        debounceSave(perEt, 700, valid) { commit() }
        val act = { if (valid()) commit(); closeOverlay {} }
        nameEt.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_DONE) { act(); true } else false
        }
        perEt.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_DONE) { act(); true } else false
        }
        showOverlay(shell) { if (valid()) commit() }
    }

    private fun openConfirmReset(m: Metric) {
        val shell = sheetShell()
        shell.addView(sheetTitle("清空全部记录"))
        val tip = TextView(context).apply {
            text = "将清空「${m.name}」全部历史数据，此操作不可恢复。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * Ui.S)
            setTextColor(C.INK2)
            setPadding(0, 0, 0, dp(18f))
        }
        shell.addView(tip)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
        }
        val cancel = TextView(context).apply {
            text = "取消"; setTextColor(C.INK2); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * Ui.S)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            setOnClickListener { closeOverlay {} }
        }
        val ok = TextView(context).apply {
            text = "清空"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * Ui.S)
            setBackgroundResource(R.drawable.bg_pill_btn)
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            setOnClickListener {
                repo.resetProject(m); sound.save(); render(); closeOverlay {}
            }
        }
        row.addView(cancel); row.addView(ok)
        shell.addView(row)
        showOverlay(shell)
    }
}
