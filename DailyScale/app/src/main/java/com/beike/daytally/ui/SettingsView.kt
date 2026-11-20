package com.beike.daytally.ui

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.beike.daytally.R
import com.beike.daytally.Sound
import com.beike.daytally.util.C
import com.beike.daytally.util.Ui

interface SettingsCallback { fun leaveSettings() }

/**
 * 隐藏页：只在项目页长按 5 秒才进得来。
 * 现在只有一组开关（桌面长按接管），以后桌面相关的都放这儿。
 */
class SettingsView(
    ctx: Context,
    private val sound: Sound,
    private val cb: SettingsCallback
) : FrameLayout(ctx) {

    private val content: LinearLayout

    init {
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16f), Ui.dp(ctx, 4f), Ui.dp(ctx, 16f), Ui.dp(ctx, 16f))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        addView(content)
        render()
    }

    private fun dp(v: Float) = Ui.dp(context, v)

    fun render() {
        content.removeAllViews()
        content.addView(buildHeader())
        content.addView(buildHookSwitch())
        content.addView(buildVersion())
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
        left.addView(TextView(context).apply {
            text = "日刻 · 桌面与模块"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * Ui.S)
            setTextColor(C.INK3)
            letterSpacing = 0.04f
        })
        left.addView(TextView(context).apply {
            text = "设置"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C.INK)
            letterSpacing = -0.01f
            setPadding(0, dp(4f), 0, 0)
        })
        val back = FrameLayout(context).apply {
            background = context.getDrawable(R.drawable.bg_icon_circle)
            val s = dp(36f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener {
                sound.tap()
                cb.leaveSettings()
            }
        }
        back.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chev_left)
            setColorFilter(C.INK2, PorterDuff.Mode.SRC_IN)
            val s = dp(17f)
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.CENTER)
        })
        row.addView(left); row.addView(back)
        return row
    }

    /** 桌面长按接管开关：关掉即刻恢复原生长按，不用重启桌面。样式沿用项目页那版，一行说完 */
    private fun buildHookSwitch(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.getDrawable(R.drawable.bg_week)
            setPadding(dp(16f), dp(15f), dp(12f), dp(15f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f); bottomMargin = dp(20f) }
        }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(context).apply {
            text = "桌面长按接管"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C.INK)
        })
        col.addView(TextView(context).apply {
            text = "关掉后桌面方块恢复原生长按，可拖动、可移除"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * Ui.S)
            setTextColor(C.INK3)
            setPadding(0, dp(4f), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(android.widget.Switch(context).apply {
            isChecked = ProjectsView.hookEnabled(context)
            setOnCheckedChangeListener { _, on ->
                ProjectsView.setHookEnabled(context, on)
                ProjectsView.sendHookSwitch(context, on)
                sound.tap()
            }
        })
        return row
    }

    private fun buildVersion(): View = TextView(context).apply {
        text = "日刻 1.2.5 · 预览版"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f * Ui.S)
        setTextColor(C.INK3)
        setPadding(dp(2f), dp(8f), 0, 0)
    }
}
