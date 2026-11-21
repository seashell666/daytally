package com.beike.daytally

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.beike.daytally.ui.ImePad
import com.beike.daytally.util.C
import com.beike.daytally.util.Ui

/**
 * 桌面身高大卡「去记录」的落点：透明 Activity，只浮一层输入弹窗 + 键盘，不进入 App 页面。
 * 弹窗内不再有按钮。保存入口只有两个：输入法回车/完成、点弹窗以外的任何位置；
 * 两者都会落盘 + 收键盘 + 关窗口，不存在「存了但窗还开着」的中间态。
 */
class HeightInputActivity : Activity() {

    private lateinit var repo: Repo
    private lateinit var sound: Sound
    private lateinit var et: EditText
    private lateinit var overlay: FrameLayout
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repo.get(this)
        sound = Sound.get(this)
        /* 透明窗口要自己收 IME inset：不铺满系统栏的话，Android 会把键盘高度吃掉，
           App 只能看到“窗口没变”，也就没法把弹窗让到键盘上方。 */
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )

        overlay = FrameLayout(this)
        setContentView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        buildSheet()
        /* 弹窗出现的这一刻给一次反馈：桌面大卡与今日页共用这一个落点，手感天然一致 */
        sound.sheetOpen()
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    private fun buildSheet() {
        val mask = View(this).apply {
            background = getDrawable(R.drawable.bg_mask)
            // 点弹窗以外任意位置：有合法值就保存，没值就当取消
            setOnClickListener { if (valid()) commit() else close() }
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_sheet)
            setPadding(dp(20f), dp(22f), dp(20f), dp(18f))
        }
        val title = TextView(this).apply {
            text = "记录身高"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C.INK)
            setPadding(0, 0, 0, dp(14f))
        }
        et = EditText(this).apply {
            val today = repo.rawVal(repo.metric("height"), repo.todayIdx)
            setText(today?.let { repo.fmt(it, 1) } ?: "")
            hint = "0 ~ 227 厘米"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f * Ui.S)
            setTextColor(C.INK)
            setHintTextColor(C.INK3)
            background = getDrawable(R.drawable.bg_proj_ic)
            setPadding(dp(13f), dp(12f), dp(13f), dp(12f))
            gravity = Gravity.CENTER
            setSelectAllOnFocus(true)
            isSingleLine = true
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        et.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_DONE) {
                if (valid()) commit() else close()
                true
            } else false
        }
        shell.addView(title); shell.addView(et)

        val box = FrameLayout(this).apply { addView(shell) }
        overlay.addView(mask)
        /* 用 ADJUST_NOTHING 保住整屏窗口，再由 ImePad 按系统报的键盘高度把弹窗
           移到“键盘上方可用区”的中间。不再写死居中，也不再依赖具体输入法多高。 */
        overlay.addView(
            box,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                setMargins(dp(24f), 0, dp(24f), 0)
            }
        )
        ImePad.bind(overlay, box, dp(16f))

        et.requestFocus()
        et.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun valid(): Boolean {
        val s = et.text.toString().trim().replace(',', '.')
        val v = s.toDoubleOrNull() ?: return false
        return v in 0.0..227.0
    }

    /** 保存：落盘 → 收键盘 → 关窗口，一步到位 */
    private fun commit() {
        if (finished) return
        if (!valid()) {
            et.error = "请输入 0 ~ 227 之间的身高"
            return
        }
        val v = et.text.toString().trim().toDouble()
        runCatching {
            repo.setHeight(v)
            sound.save()
        }
        close()
    }

    private fun close() {
        if (finished) return
        finished = true
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(et.windowToken, 0)
        finish()
    }

    override fun onBackPressed() {
        close()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
