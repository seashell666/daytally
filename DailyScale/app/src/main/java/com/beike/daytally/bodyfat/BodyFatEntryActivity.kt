package com.beike.daytally.bodyfat

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.beike.daytally.R
import com.beike.daytally.Repo
import com.beike.daytally.muscle.MusclePolicy
import com.beike.daytally.util.C
import com.beike.daytally.util.Ui
import java.util.UUID

/**
 * 体脂率 / 肌肉重量「去记录」入口。
 *
 * 正常路径：打开 OKOK 自己的 AppWidget://WeightUpScale 深链，进入官方称重流程。
 * OKOKDeclutter 适配层在测量完成时拿到 WeightEntity，通过日刻给的 PendingIntent
 * 把真实体脂率和肌肉重量回传；Receiver 校验会话后直接落库，不再要求手动确认。
 *
 * 只有真的没收到回传（没装 OKOK、模块没启用、版本变化、用户中途退出）时，
 * 才显示兜底面板，并明确标注“手动兜底”，不伪造 OKOK 结果。
 */
class BodyFatEntryActivity : Activity() {

    private lateinit var overlay: FrameLayout
    private var hasLaunched = false
    private var hasPaused = false
    private var sheetShown = false
    private var finished = false
    private var retryPending = false
    private var bfEdit: EditText? = null
    private var muscleEdit: EditText? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        overlay = FrameLayout(this)
        setContentView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun onResume() {
        super.onResume()
        if (!hasLaunched) {
            launchOkok()
            return
        }
        if (!hasPaused || sheetShown) return
        hasPaused = false
        /* 给 Receiver 一点时间：广播可能和 Activity 回前台几乎同时到。 */
        handler.postDelayed({
            if (finished || sheetShown) return@postDelayed
            val session = OkokMeasureSession.current(this)
            when (session?.resultStatus) {
                "ok", "partial" -> close()
                "failed" -> showFallbackSheet("OKOK 回了结果，但体脂率和肌肉重量都没有通过校验，本次没有写入。")
                else -> showFallbackSheet(
                    "没有收到 OKOK 的测量结果。可能是适配模块未启用、OKOK 版本变化，或这次测量中途退出了。\n" +
                        "日刻不会猜数字：可以再试一次，或在下面手动兜底。"
                )
            }
        }, 700)
    }

    override fun onPause() {
        if (hasLaunched && !sheetShown && !finished) hasPaused = true
        super.onPause()
    }

    /** 桌面组件再次点击时复用 singleInstance，必须重新起一次测量会话。 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (finished) return
        resetSheet()
        sheetShown = false
        retryPending = false
        hasLaunched = false
        hasPaused = false
        launchOkok()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun launchOkok() {
        hasLaunched = true
        hasPaused = false
        val sessionId = UUID.randomUUID().toString()
        OkokMeasureSession.begin(this, sessionId)

        val resultIntent = Intent(this, BodyFatResultReceiver::class.java).apply {
            action = BodyFatBridgeContract.ACTION_RESULT
        }
        /* 必须 mutable：OKOK 适配层要在 send() 时填入本次测量结果。
           目标组件是日刻本应用的显式 Receiver，且模块会校验 creatorPackage，
           不会把这个 PendingIntent 暴露给第三方。 */
        val resultPending = PendingIntent.getBroadcast(
            this,
            sessionId.hashCode() and 0x7fffffff,
            resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val launch = Intent(Intent.ACTION_VIEW).apply {
            setClassName(
                BodyFatBridgeContract.OKOK_PACKAGE,
                BodyFatBridgeContract.OKOK_MAIN_ACTIVITY
            )
            data = Uri.parse(BodyFatBridgeContract.OKOK_UP_SCALE_URL)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(BodyFatBridgeContract.EXTRA_RESULT_PENDING_INTENT, resultPending)
            putExtra(BodyFatBridgeContract.EXTRA_SESSION_ID, sessionId)
        }
        runCatching { startActivity(launch) }
            .onSuccess {
                Toast.makeText(
                    this,
                    "已打开 OKOK 称重页；测完会自动记入日刻",
                    Toast.LENGTH_LONG
                ).show()
            }
            .onFailure {
                hasLaunched = false
                showFallbackSheet("手机上没有找到可用的 OKOK，无法自动测量。可以在下面手动兜底，或先安装/更新 OKOK。")
            }
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    private fun resetSheet() {
        overlay.removeAllViews()
        bfEdit = null
        muscleEdit = null
    }

    private fun showFallbackSheet(message: String) {
        if (sheetShown || finished) return
        sheetShown = true
        retryPending = false

        val mask = View(this).apply {
            background = getDrawable(R.drawable.bg_mask)
            setOnClickListener { close() }
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_sheet)
            setPadding(dp(20f), dp(22f), dp(20f), dp(18f))
            setOnClickListener { }
        }
        val title = TextView(this).apply {
            text = "这次没有自动记录"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * Ui.S)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C.INK)
        }
        val hintView = TextView(this).apply {
            text = message
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f * Ui.S)
            setTextColor(C.INK3)
            setPadding(0, dp(6f), 0, dp(12f))
            setLineSpacing(0f, 1.3f)
        }

        bfEdit = numberInput("体脂率（可留空）")
        muscleEdit = numberInput("肌肉重量（可留空）")
        val bfRow = inputRow(bfEdit!!, "%", dp(10f))
        val muscleRow = inputRow(muscleEdit!!, "公斤", dp(8f))

        val note = TextView(this).apply {
            text = "手动兜底会标记为“手动记录”，不会冒充 OKOK 测量结果。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f * Ui.S)
            setTextColor(C.INK3)
            setPadding(0, dp(8f), 0, 0)
            setLineSpacing(0f, 1.25f)
        }

        val retry = TextView(this).apply {
            text = "再试 OKOK"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * Ui.S)
            setTextColor(C.INK2)
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = getDrawable(R.drawable.bg_proj_ic)
            setOnClickListener {
                if (retryPending) return@setOnClickListener
                retryPending = true
                sheetShown = false
                hasLaunched = false
                OkokMeasureSession.clear(this@BodyFatEntryActivity)
                resetSheet()
                launchOkok()
            }
        }
        val save = TextView(this).apply {
            text = "保存手动记录"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * Ui.S)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = getDrawable(R.drawable.bg_pill_btn)
            setOnClickListener { commitManual() }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14f), 0, 0)
            addView(retry, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8f)
            })
            addView(save, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        }

        shell.addView(title)
        shell.addView(hintView)
        shell.addView(bfRow)
        shell.addView(muscleRow)
        shell.addView(note)
        shell.addView(actions)

        overlay.removeAllViews()
        overlay.addView(mask, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        overlay.addView(
            shell,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { setMargins(dp(24f), 0, dp(24f), 0) }
        )
    }

    private fun numberInput(hintText: String): EditText = EditText(this).apply {
        setHint(hintText)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * Ui.S)
        setTextColor(C.INK)
        setHintTextColor(C.INK3)
        background = getDrawable(R.drawable.bg_proj_ic)
        setPadding(dp(13f), dp(10f), dp(13f), dp(10f))
        gravity = Gravity.CENTER
        setSelectAllOnFocus(true)
        isSingleLine = true
    }

    private fun inputRow(edit: EditText, unitText: String, top: Int): LinearLayout {
        val unit = TextView(this).apply {
            text = unitText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * Ui.S)
            setTextColor(C.INK3)
            setPadding(dp(8f), 0, 0, 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, top, 0, 0)
            addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(unit, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun commitManual() {
        if (finished) return
        val bfRaw = bfEdit?.text?.toString().orEmpty().trim().replace(',', '.')
        val muscleRaw = muscleEdit?.text?.toString().orEmpty().trim().replace(',', '.')
        if (bfRaw.isBlank() && muscleRaw.isBlank()) {
            bfEdit?.error = "至少填一项；两项都不填就取消"
            return
        }
        val bodyFat = bfRaw.toDoubleOrNull()
        val muscle = muscleRaw.toDoubleOrNull()
        if (bfRaw.isNotBlank() && (bodyFat == null || !BodyFatPolicy.isValidValue(bodyFat))) {
            bfEdit?.error = "体脂率请输入 1.0 ~ 75.0"
            return
        }
        if (muscleRaw.isNotBlank() && (muscle == null || !MusclePolicy.isValidValue(muscle))) {
            muscleEdit?.error = "肌肉重量请输入 20.0 ~ 120.0 公斤"
            return
        }

        var savedBodyFat = false
        var savedMuscle = false
        if (bodyFat != null) {
            savedBodyFat = runCatching {
                BodyFatStore.add(this, bodyFat, BodyFatPolicy.SOURCE_MANUAL)
            }.isSuccess
        }
        if (muscle != null) {
            savedMuscle = runCatching { Repo.get(this).setMuscle(muscle) }.getOrDefault(false)
        }
        if (!savedBodyFat && !savedMuscle) {
            Toast.makeText(this, "保存失败，请再试一次", Toast.LENGTH_SHORT).show()
            return
        }
        val parts = buildList {
            if (savedBodyFat && bodyFat != null) add("体脂 ${BodyFatPolicy.format(bodyFat)}%")
            if (savedMuscle && muscle != null) add("肌肉 ${MusclePolicy.format(muscle)} 公斤")
        }
        Toast.makeText(this, "已手动记录：" + parts.joinToString(" · "), Toast.LENGTH_LONG).show()
        close()
    }

    private fun close() {
        if (finished) return
        finished = true
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onBackPressed() {
        close()
    }
}
