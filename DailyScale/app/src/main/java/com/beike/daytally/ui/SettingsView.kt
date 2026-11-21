package com.beike.daytally.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.beike.daytally.HookState
import com.beike.daytally.R
import com.beike.daytally.Sound
import com.beike.daytally.sync.SyncConfig
import com.beike.daytally.sync.SyncManager
import com.beike.daytally.sync.SyncPushService
import com.beike.daytally.sync.SyncStore
import com.beike.daytally.util.C
import com.beike.daytally.util.Ui

interface SettingsCallback { fun leaveSettings() }

/**
 * 隐藏页：只在项目页长按 0.7 秒才进得来。
 * 桌面长按接管 + 同步服务都在这里；同步服务卡点开填自己的 IP、端口和访问令牌。
 */
class SettingsView(
    ctx: Context,
    private val sound: Sound,
    /** 弹窗挂到 App 最顶层 overlay，和项目页弹窗同一套，不会被底栏压住 */
    private val overlay: FrameLayout,
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
        content.addView(buildSyncCard())
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

    /** 设置页统一卡片外壳：和「桌面长按接管」同一套底色、圆角、内边距和高度 */
    private fun cardRow(top: Float, bottom: Float): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = context.getDrawable(R.drawable.bg_week)
        setPadding(dp(16f), dp(15f), dp(12f), dp(15f))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }
    }

    /** 桌面长按接管开关：关掉即刻恢复原生长按，不用重启桌面。 */
    private fun buildHookSwitch(): View {
        val row = cardRow(12f, 12f)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val info = HookState.read(context)
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(context).apply {
            text = "桌面长按接管"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C.INK)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(context).apply {
            text = when {
                !info.seen -> "未接上"
                !info.afterBoot -> "该复测了"
                !info.fresh -> "该复测了"
                else -> "已接上"
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                when {
                    !info.seen || !info.afterBoot -> C.RED
                    !info.fresh -> C.GOLD_DEEP
                    else -> C.GREEN
                }
            )
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            background = context.getDrawable(R.drawable.bg_proj_ic)
        })
        col.addView(titleRow)
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

    /** 同步服务：点整张卡打开设置弹窗；状态和地址都直接写在卡上 */
    private fun buildSyncCard(): View {
        val row = cardRow(0f, 20f)
        val ep = SyncConfig.endpoint(context)
        val last = SyncStore.lastResult(context)
        val (label, color) = when {
            ep == null -> "未设置" to C.RED
            last.ok == true -> "已连接" to C.GREEN
            last.ok == false -> "连不上" to C.RED
            else -> "待连接" to C.GOLD_DEEP
        }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(context).apply {
            text = "同步服务"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C.INK)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            background = context.getDrawable(R.drawable.bg_proj_ic)
        })
        col.addView(titleRow)
        col.addView(TextView(context).apply {
            text = if (ep == null) {
                "点一下填 IP、端口和访问令牌"
            } else {
                "${ep.host}:${ep.port} · 点一下修改"
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * Ui.S)
            setTextColor(C.INK3)
            setPadding(0, dp(4f), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chev)
            setColorFilter(Color.parseColor("#B3A7C8"), PorterDuff.Mode.SRC_IN)
            val s = dp(18f)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = dp(10f) }
        })
        row.isClickable = true
        row.setOnClickListener {
            sound.sheetOpen()
            openSyncSheet()
        }
        return row
    }

    private fun buildVersion(): View = TextView(context).apply {
        text = "日刻 1.2.12 · 预览版"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f * Ui.S)
        setTextColor(C.INK3)
        setPadding(dp(2f), dp(8f), 0, 0)
    }

    // ---------- 同步服务弹窗 ----------

    private fun openSyncSheet() {
        val ep = SyncConfig.endpoint(context)
        val last = SyncStore.lastResult(context)
        val shell = sheetShell()
        shell.addView(sheetTitle("同步服务"))
        shell.addView(TextView(context).apply {
            text = "填你自己部署的日刻后端地址。公开版也能连，不绑定任何人的服务器。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * Ui.S)
            setTextColor(C.INK3)
            setLineSpacing(0f, 1.35f)
            setPadding(0, 0, 0, dp(12f))
        })

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val hostEt = field("IP / 主机", ep?.host ?: "", maxLen = 64)
        val portEt = field("端口", ep?.port?.toString() ?: "8787", numeric = true, maxLen = 5)
        hostEt.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(10f) }
        portEt.layoutParams = LinearLayout.LayoutParams(dp(96f), LinearLayout.LayoutParams.WRAP_CONTENT)
        row.addView(hostEt); row.addView(portEt)
        shell.addView(row)
        shell.addView(space())

        val tokenEt = field(
            "访问令牌，后端设了才填",
            ep?.token ?: "",
            password = true,
            maxLen = 128
        )
        shell.addView(tokenEt)

        val err = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * Ui.S)
            setTextColor(C.RED)
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(8f), 0, 0)
            visibility = View.GONE
        }
        if (last.ok == false && !last.error.isNullOrBlank()) {
            err.text = "上次连接失败：${last.error.take(120)}"
            err.visibility = View.VISIBLE
        }
        shell.addView(err)

        val save = TextView(context).apply {
            text = "保存并连接"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.bg_confirm_btn)
            setPadding(0, dp(14f * Ui.S), 0, dp(14f * Ui.S))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16f * Ui.S) }
            setOnClickListener {
                val host = SyncConfig.cleanHost(hostEt.text.toString())
                val port = portEt.text.toString().trim().toIntOrNull()
                if (host.isBlank()) {
                    sound.error()
                    err.text = "IP / 主机不能为空"
                    err.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                if (port == null || port !in 1..65535) {
                    sound.error()
                    err.text = "端口要填 1 ~ 65535"
                    err.visibility = View.VISIBLE
                    return@setOnClickListener
                }

                val old = SyncConfig.endpoint(context)
                val token = tokenEt.text.toString().trim()
                SyncConfig.save(context, host, port, token)
                val now = SyncConfig.endpoint(context)
                val changed = old == null || now == null ||
                    old.host != now.host || old.port != now.port
                if (changed) {
                    SyncStore.resetForServerChange(context)
                } else {
                    SyncStore.clearLastResult(context)
                }

                sound.save()
                closeSheet()
                render()
                SyncPushService.restart(context)
                SyncManager.request(context, "config")
            }
        }
        shell.addView(save)

        showSheet(shell)
        focusAndShowKeyboard(hostEt)
    }

    private fun showSheet(sheet: View) {
        overlay.removeAllViews()
        val mask = View(context).apply {
            background = context.getDrawable(R.drawable.bg_mask)
            setOnClickListener { closeSheet() }
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
        overlay.visibility = View.VISIBLE
        ImePad.bind(overlay, box, dp(16f))
    }

    private fun closeSheet() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(overlay.windowToken, 0)
        overlay.visibility = View.GONE
        overlay.removeAllViews()
    }

    private fun sheetShell(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = context.getDrawable(R.drawable.bg_sheet)
        setPadding(dp(20f), dp(22f), dp(20f), dp(22f))
    }

    private fun sheetTitle(t: String): TextView = TextView(context).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * Ui.S)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(C.INK)
        setPadding(0, 0, 0, dp(14f))
    }

    private fun field(
        hint: String,
        value: String,
        numeric: Boolean = false,
        password: Boolean = false,
        maxLen: Int = 0
    ): EditText = EditText(context).apply {
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
        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (password) transformationMethod = PasswordTransformationMethod.getInstance()
        if (maxLen > 0) {
            filters = arrayOf<android.text.InputFilter>(
                android.text.InputFilter.LengthFilter(maxLen)
            )
        }
    }

    private fun focusAndShowKeyboard(et: EditText) {
        et.requestFocus()
        et.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun space(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(12f))
    }
}
