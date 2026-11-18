package com.beike.daytally

import android.app.Activity
import android.content.Context
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
import com.beike.daytally.util.C
import com.beike.daytally.util.Ui

/**
 * 桌面身高大卡「去记录」的落点：透明 Activity，只浮一层输入弹窗 + 键盘，
 * 不进入 App 页面。保存只有两个入口：弹窗里的「保存」按钮、输入法回车/完成，
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
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        overlay = FrameLayout(this)
        setContentView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        buildSheet()
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    private fun buildSheet() {
        val mask = View(this).apply {
            background = getDrawable(R.drawable.bg_mask)
            setOnClickListener { close() }        // 点遮罩 = 取消，不保存
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
            hint = "100 ~ 250 厘米，可带一位小数"
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
            if (a == EditorInfo.IME_ACTION_DONE) { commit(); true } else false
        }
        shell.addView(title); shell.addView(et)

        // 明确的按钮：不依赖各家输入法的动作键
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14f), 0, 0)
        }
        val cancel = TextView(this).apply {
            text = "取消"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * Ui.S)
            setTextColor(C.INK3)
            gravity = Gravity.CENTER
            setPadding(dp(18f), dp(11f), dp(18f), dp(11f))
            setOnClickListener { close() }
        }
        val save = TextView(this).apply {
            text = "保存"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bg_pill_btn)
            setPadding(dp(26f), dp(11f), dp(26f), dp(11f))
            setOnClickListener { commit() }
        }
        row.addView(cancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(save)
        shell.addView(row)

        val box = FrameLayout(this).apply { addView(shell) }
        overlay.addView(mask)
        // 弹窗贴屏幕上方，键盘占下半屏，避免被压缩重叠
        overlay.addView(
            box,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                setMargins(dp(24f), dp(150f), dp(24f), dp(24f))
            }
        )

        et.requestFocus()
        et.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun valid(): Boolean {
        val s = et.text.toString().trim().replace(',', '.')
        val v = s.toDoubleOrNull() ?: return false
        return v in 100.0..250.0
    }

    /** 保存：落盘 → 收键盘 → 关窗口，一步到位 */
    private fun commit() {
        if (finished) return
        if (!valid()) {
            et.error = "请输入 100 ~ 250 之间的身高"
            return
        }
        val v = et.text.toString().trim().toDouble()
        runCatching {
            repo.metric("height").data[repo.todayStr()] = v
            repo.save()
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
