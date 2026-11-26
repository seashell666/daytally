package com.beike.daytally.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

interface ProjectsCallback { fun gotoHome(); fun gotoSettings() }

/**
 * 长按进设置：按下起计时，抬手/滑走就取消，中途不动才算数。
 * 项目页 0.7 秒，今日页底栏「项目」也是 0.7 秒。
 */
object HoldToSettings {
    const val HOLD_MS = 700L
    const val HOLD_MS_HOME = 700L
    /**
     * 长按已经触发过（已经进了设置页），这个手势抬手时的单击就不能再执行。
     * 不能按时间窗判：用户可能在设置页停留很久才松手，用时间窗会漏，表现为
     * 「松手又退回项目页」。这里改成手势级标记：触发即置位，下一次 ACTION_DOWN 才清零。
     */
    private var fired = false

    fun shouldSkipClick(): Boolean = fired

    fun install(
        v: View,
        holdMs: () -> Long = { HOLD_MS },
        onDown: (() -> Unit)? = null,
        onUp: (() -> Unit)? = null,
        onFire: () -> Unit
    ) {
        var run: Runnable? = null
        /* 必须可点击：否则 dispatchTouchEvent 收不到 ACTION_UP，长按也就永远不触发 */
        v.isClickable = true
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false
                    onDown?.invoke()
                    val r = Runnable {
                        fired = true
                        onUp?.invoke()
                        onFire()
                    }
                    run = r
                    v.postDelayed(r, holdMs())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    run?.let { v.removeCallbacks(it) }
                    run = null
                    onUp?.invoke()
                }
            }
            /* 返回 false 不抢事件：交给 View 自己的 onTouchEvent 继续排发（clickable 时它
               会返回 true，所以 UP/CANCEL 照样能收到，单击也不会被吃掉） */
            false
        }
    }
}

private fun View.installHoldToSettings(cb: ProjectsCallback) = apply {
    HoldToSettings.install(this) { cb.gotoSettings() }
}


class ProjectsView(
    ctx: Context, val repo: Repo, val sound: Sound, val cb: ProjectsCallback,
    /** 弹窗宿主用 App 最顶层的 overlay，不能挂在本页里，否则底栏/页面会压到弹窗上面 */
    private val overlay: FrameLayout
) : FrameLayout(ctx) {

    companion object {
        private const val PREFS_CFG = "ds_cfg"
        private const val K_HOOK = "desktop_hook"
        const val ACTION_HOOK_SWITCH = "com.beike.daytally.HOOK_SWITCH"

        fun hookEnabled(c: Context): Boolean =
            c.getSharedPreferences(PREFS_CFG, Context.MODE_PRIVATE).getBoolean(K_HOOK, true)

        fun setHookEnabled(c: Context, on: Boolean) {
            c.getSharedPreferences(PREFS_CFG, Context.MODE_PRIVATE).edit().putBoolean(K_HOOK, on).apply()
        }

        /** 把开关状态告诉桌面进程里的钩子，不用重启桌面就能生效 */
        fun sendHookSwitch(c: Context, on: Boolean) {
            runCatching {
                val i = android.content.Intent(ACTION_HOOK_SWITCH)
                i.putExtra("enabled", on)
                i.addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
                c.sendBroadcast(i)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val content: LinearLayout
    private val nf = DecimalFormat("#,###")
    private var lastTap = 0L
    private var cardClickTimer: Runnable? = null

    /** 页头副标题（按住时后半句变红），由底栏长按统一驱动，所以要留成成员 */
    private val HINT_HEAD = "所有日刻项目 · "
    private val HINT_TAIL = "长按项目进入设置"
    private var hintSub: TextView? = null

    /** 按住/松开时给副标题上色；外部（底栏 Tab 长按）也能调 */
    fun paintHint(active: Boolean) {
        val sub = hintSub ?: return
        val s = HINT_HEAD + HINT_TAIL
        val ss = android.text.SpannableString(s)
        if (active) {
            ss.setSpan(
                android.text.style.ForegroundColorSpan(C.RED),
                HINT_HEAD.length, s.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        sub.text = ss
    }

    init {
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            /* 底部不再留 16dp：5 张卡的 4 个卡间 + 与底栏的 1 个间隙，统一由卡片的
               bottomMargin 10dp 决定，5 个间隙严格等距 */
            setPadding(Ui.dp(ctx, 16f), Ui.dp(ctx, 4f), Ui.dp(ctx, 16f), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        overlay.visibility = GONE
        addView(content)
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
        /* 副标题：前半段常灰，后半段「长按项目进入设置」在按住时变红，看得见自己在长按 */
        val sub = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * Ui.S)
            setTextColor(C.INK3)
            letterSpacing = 0.04f
        }
        hintSub = sub
        paintHint(false)

        val title = TextView(context).apply {
            text = "项目"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C.INK)
            letterSpacing = -0.01f
            setPadding(0, dp(4f), 0, 0)
        }
        left.addView(sub); left.addView(title)
        /* 热区铺满整个页头行（副标题 + 大字「项目」 + 右侧返回键外围的空白），
           命中率最高；按住时副标题后半句变红。返回键是子 View，点击优先级更高，不受影响。 */
        HoldToSettings.install(
            row,
            onDown = { paintHint(true) },
            onUp = { paintHint(false) }
        ) { cb.gotoSettings() }
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
            // 5 张卡平分整页高度：weight 撑满，不再留底部一大块空
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            ).apply { bottomMargin = dp(10f) }
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
                "${nf.format(Math.round(cum))} ${m.unit}" to true
            ), hi)
            val line2 = spanMeta(listOf(
                "趋势纵轴 " to false,
                "0 ~ ${m.maxGroups} 组" to true,
                " · 目标 ${nf.format(m.goal)}" to false
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
                /* 音效要在「按下瞬间」就出来：等弹窗铺好再响，用户会以为声音是弹窗自带的延迟。
                   784Hz 短音 + 轻震，和「去记录」弹窗同一串 */
                sound.sheetOpen()
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTap < 300L) {
                    cardClickTimer?.let { handler.removeCallbacks(it) }
                    cardClickTimer = null
                    openEditSheet(m)
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
        /* 长按进清空弹窗：震动和那记 784Hz 短音同时出现（一个调用里一起触发） */
        sound.sheetOpen()
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
        /* 编辑项目/纵轴上限这些输入弹窗，键盘一高就会盖住下半截。
           这里交给 ImePad：键盘多高就上移多少，始终停在键盘上方可用区的中间。 */
        ImePad.bind(overlay, box, dp(16f))
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

    /**
     * 弹窗输入框：一律单行，右下角「完成」= 确认保存并关窗，不允许在框里换行。
     * numeric=true 时直接要数字键盘（豆包输入法会给九宫格数字盘），name 类走 26 键。
     */
    private fun field(
        hint: String,
        value: String,
        numeric: Boolean = false,
        maxLen: Int = 0
    ): EditText {
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
            isSingleLine = true
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = if (numeric) {
                android.text.InputType.TYPE_CLASS_NUMBER
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
            if (maxLen > 0) {
                filters = arrayOf<android.text.InputFilter>(
                    android.text.InputFilter.LengthFilter(maxLen)
                )
            }
        }
    }

    /** 弹层一开就把焦点和键盘一起给出去（豆包输入法按 inputType 决定出九宫格还是 26 键） */
    private fun focusAndShowKeyboard(et: EditText) {
        et.requestFocus()
        et.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
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
        /* 提示音已经在点击那一刻响过（sheetOpen），这里不再补，避免听起来「弹窗后才响」 */
        val shell = sheetShell()
        shell.addView(sheetTitle("趋势纵轴上限"))
        val et = field("1 ~ 999（组）", m.maxGroups.toString(), numeric = true, maxLen = 3)
        val valid = {
            val v = et.text.toString().trim().toIntOrNull(); v != null && v in 1..999
        }
        val commit = {
            m.maxGroups = et.text.toString().trim().toInt()
            repo.noteConfigChanged(m)
            repo.save(); render()          // 静默保存：边打字边存，不震不响
        }
        debounceSave(et, 700, valid) { commit() }
        /* 只有「完成」或点空白退出时才给一记震动+音效 */
        val exit = { if (valid()) commit(); sound.save(); closeOverlay {} }
        et.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_DONE) { exit(); true } else false
        }
        shell.addView(et)
        showOverlay(shell) { if (valid()) commit(); sound.save() }
        focusAndShowKeyboard(et)
    }

    private fun openEditSheet(m: Metric) {
        /* 同上：双击的第二下也在按下时响过了 */
        val shell = sheetShell()
        shell.addView(sheetTitle("编辑项目"))
        var unit = m.unit
        val nameEt = field("项目名（≤4 字）", m.name, maxLen = 4)
        val perEt = field("每组值 1 ~ 999", m.per.toString(), numeric = true, maxLen = 3)
        shell.addView(nameEt); shell.addView(space())
        shell.addView(perEt); shell.addView(space())

        val unitRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val opts = listOf("个", "分钟", "招")
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
            n.isNotEmpty() && n.length <= 4 && p != null && p in 1..999
        }
        val commit = {
            m.name = nameEt.text.toString().trim()
            m.per = perEt.text.toString().trim().toInt()
            m.unit = unit
            repo.noteConfigChanged(m)
            repo.save(); render()          // 静默保存
        }
        debounceSave(nameEt, 700, valid) { commit() }
        debounceSave(perEt, 700, valid) { commit() }
        val act = { if (valid()) commit(); sound.save(); closeOverlay {} }
        nameEt.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_DONE) { act(); true } else false
        }
        perEt.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_DONE) { act(); true } else false
        }
        showOverlay(shell) { if (valid()) commit(); sound.save() }
        focusAndShowKeyboard(nameEt)
    }

    /**
     * 长按项目卡片：清空重置确认弹窗。
     * 1:1 复刻 HTML 原型——居中白卡、标题「清空「XX」？」、灰色说明、一条全宽渐变「清空重置」，
     * 没有取消按钮，点弹窗外即取消。
     */
    private fun openConfirmReset(m: Metric) {
        val shell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.bg_sheet)
            setPadding(dp(21f), dp(23.5f), dp(21f), dp(21f))
        }
        shell.addView(TextView(context).apply {
            text = "清空「${m.name}」？"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C.INK)
            gravity = Gravity.CENTER
        })
        shell.addView(TextView(context).apply {
            text = "这个项目的全部组数会清零，累计和进度环归零，这个操作不能撤销。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * Ui.S)
            setTextColor(C.INK3)
            gravity = Gravity.CENTER
            /* HTML 是 line-height 1.7（=1.7 倍字高）；Android 的 mult 是相对字体行框
               (≈1.16em)，所以要用 1.45 才等价于原型那点行距。原来填 1.7 实际约 2.0em，
               行距被撑到跟「标题→正文」的间距一样大，层次就平了。 */
            setLineSpacing(0f, 1.45f)
            setPadding(0, dp(7f * Ui.S), 0, 0)
        })
        shell.addView(TextView(context).apply {
            text = "清空重置"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.bg_confirm_btn)
            setPadding(0, dp(14f * Ui.S), 0, dp(14f * Ui.S))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(19f * Ui.S) }
            setOnClickListener {
                repo.resetProject(m); sound.add(); render(); closeOverlay {}
            }
        })
        showOverlay(shell)
    }
}
