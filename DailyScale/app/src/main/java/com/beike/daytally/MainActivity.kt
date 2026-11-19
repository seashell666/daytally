package com.beike.daytally

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.graphics.PorterDuff
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.beike.daytally.ui.HomeCallback
import com.beike.daytally.ui.HomeView
import com.beike.daytally.ui.ProjectsCallback
import com.beike.daytally.ui.ProjectsView
import com.beike.daytally.ui.SettingsCallback
import com.beike.daytally.ui.SettingsView
import com.beike.daytally.ui.TrendCallback
import com.beike.daytally.ui.TrendView
import com.beike.daytally.util.C
import com.beike.daytally.util.Ui

class MainActivity : AppCompatActivity() {

    private lateinit var repo: Repo
    private lateinit var sound: Sound
    private lateinit var home: HomeView
    private lateinit var trend: TrendView
    private lateinit var projects: ProjectsView
    private lateinit var settings: SettingsView
    private lateinit var overlay: FrameLayout
    private var tabIcons: List<android.widget.ImageView> = emptyList()
    private var tabLabels: List<Pair<String, TextView>> = emptyList()
    private var current = "home"
    private var lastTabKey = ""
    private var lastTabMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repo.get(this)
        sound = Sound.get(this)

        val root = FrameLayout(this)
        setContentView(root)
        val main = layoutInflater.inflate(R.layout.activity_main, null)
        root.addView(main)
        overlay = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(overlay)

        val content = main.findViewById<FrameLayout>(R.id.content)
        home = HomeView(this, repo, sound, object : HomeCallback {
            override fun gotoProjects() { switchView("projects") }
            override fun openHeightSheet() { this@MainActivity.openHeightSheet() }
        })
        trend = TrendView(this, repo, sound, object : TrendCallback {
            override fun gotoHome() = switchView("home")
        })
        projects = ProjectsView(this, repo, sound, object : ProjectsCallback {
            override fun gotoHome() = switchView("home")
            override fun gotoSettings() = openSettings()
        })
        settings = SettingsView(this, sound, object : SettingsCallback {
            override fun leaveSettings() = switchView("projects")
        })
        content.addView(home)
        content.addView(trend)
        content.addView(projects)
        content.addView(settings)

        buildTabs(main.findViewById(R.id.tabbar))
        /* 上次停在设置页就还回设置页；其他页面一律回今天页 */
        val lastView = getSharedPreferences("ds_cfg", MODE_PRIVATE).getString("last_view", "home")
        switchView(if (lastView == "settings") "settings" else "home")
        handleWidgetIntent(intent)
        /* 打开 App 就把桌面组件刷一遍，保证组件和 App 内数据永远一致 */
        com.beike.daytally.widget.WidgetRefresh.refreshAll(this)
        /* 顺便把「桌面长按接管」开关的当前状态同步给桌面进程的钩子 */
        com.beike.daytally.ui.ProjectsView.sendHookSwitch(this, com.beike.daytally.ui.ProjectsView.hookEnabled(this))
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleWidgetIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current == "settings") {
            switchView("projects")
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun handleWidgetIntent(intent: android.content.Intent?) {
        intent ?: return
        when (intent.getStringExtra("page")) {
            "trend" -> {
                switchView("trend")
                intent.getStringExtra("metric")?.let { trend.openMetric(it) }
            }
            "home" -> {
                switchView("home")
                /* 身高大卡的「去记录」：进来就弹身高弹窗（连输入法一起） */
                if (intent.getBooleanExtra("height_sheet", false)) openHeightSheet()
            }
        }
    }

    private fun buildTabs(tabbar: LinearLayout) {
        val items: List<Triple<String, Int, String>> = listOf(
            Triple("home", R.drawable.ic_home, "今天"),
            Triple("trend", R.drawable.ic_trend, "趋势"),
            Triple("projects", R.drawable.ic_project, "项目")
        )
        tabIcons = emptyList()
        tabLabels = emptyList()
        items.forEach { (key, icon, label) ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                tag = key
                setOnClickListener {
                    /* 长按 5 秒刚进过设置，抬手这一下不要又切回项目页 */
                    if (key == "projects" && com.beike.daytally.ui.HoldToSettings.shouldSkipClick()) return@setOnClickListener
                    sound.tabTap()      // 每按必震：单击一下、双击两下
                    val now = android.os.SystemClock.uptimeMillis()
                    val dbl = key != "home" && key == lastTabKey && now - lastTabMs < 420L
                    /* 趋势/项目双击 = 直接回今天页（原型行为），单击照常切换 */
                    if (dbl) {
                        lastTabKey = ""
                        switchView("home")
                    } else {
                        lastTabKey = key
                        lastTabMs = now
                        switchView(key)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setPadding(0, Ui.dp(this@MainActivity, 6f), 0, 0)
            }
            val iv = android.widget.ImageView(this).apply {
                setImageResource(icon)
                val s = Ui.dp(this@MainActivity, 19f)
                layoutParams = LinearLayout.LayoutParams(s, s)
            }
            val tv = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * Ui.S)
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(this@MainActivity, 3f), 0, 0)
            }
            col.addView(iv); col.addView(tv)
            /* 长按底栏「项目」5 秒 = 进隐藏设置页 */
            if (key == "projects") {
                com.beike.daytally.ui.HoldToSettings.install(col) { openSettings() }
            }
            tabbar.addView(col)
            tabIcons = tabIcons + iv
            tabLabels = tabLabels + (key to tv)
        }
    }

    /** 隐藏设置页：只能从项目页长按 5 秒进来（底栏「项目」或标题「项目」都行） */
    private fun openSettings() {
        sound.sheetOpen()
        switchView("settings")
    }

    private fun switchView(key: String) {
        current = key
        /* 记住停在哪一页：进程被杀后重新进来还能回到设置页 */
        getSharedPreferences("ds_cfg", MODE_PRIVATE).edit().putString("last_view", key).apply()
        home.visibility = if (key == "home") View.VISIBLE else View.GONE
        trend.visibility = if (key == "trend") View.VISIBLE else View.GONE
        projects.visibility = if (key == "projects") View.VISIBLE else View.GONE
        settings.visibility = if (key == "settings") View.VISIBLE else View.GONE
        tabLabels.forEachIndexed { i, (k, tv) ->
            val on = k == key
            tv.setTextColor(if (on) C.RED else C.INK3)
            tabIcons[i].setColorFilter(if (on) C.RED else C.INK3, PorterDuff.Mode.SRC_IN)
        }
        when (key) {
            "home" -> home.render()
            "trend" -> trend.render()
            "projects" -> projects.render()
            "settings" -> settings.render()
        }
    }

    // ---------- 身高记录弹层 ----------
    private fun openHeightSheet() {
        sound.sheetOpen()          // 与桌面大卡「去记录」同一手感
        val h = repo.metric("height")
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_sheet)
            setPadding(Ui.dp(context, 20f), Ui.dp(context, 22f),
                Ui.dp(context, 20f), Ui.dp(context, 22f))
        }
        val title = TextView(this).apply {
            text = "记录身高"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * Ui.S)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(C.INK)
            setPadding(0, 0, 0, Ui.dp(this@MainActivity, 14f))
        }
        val cur = repo.rawVal(h, repo.todayIdx)
        val et = EditText(this).apply {
            setText(cur?.let { repo.fmt(it, 1) } ?: "")
            hint = "100 ~ 250 厘米，可带一位小数"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * Ui.S)
            setTextColor(C.INK)
            setHintTextColor(C.INK3)
            background = getDrawable(R.drawable.bg_proj_ic)
            setPadding(Ui.dp(this@MainActivity, 13f), Ui.dp(this@MainActivity, 11f),
                Ui.dp(this@MainActivity, 13f), Ui.dp(this@MainActivity, 11f))
            gravity = Gravity.CENTER
            setSelectAllOnFocus(true)
            /* 单行 + 右下角「完成」= 存盘关窗，框里不允许换行 */
            isSingleLine = true
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val valid = {
            val s = et.text.toString().trim()
            val v = s.toDoubleOrNull()
            s.matches(Regex("""\d{1,3}(\.\d)?""")) && v != null && v in 100.0..250.0
        }
        val commit = {
            h.data[repo.todayStr()] = et.text.toString().trim().toDouble()
            repo.save(); sound.save(); home.render()
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        })
        et.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_DONE) { if (valid()) commit(); closeHeight(); true } else false
        }
        shell.addView(title); shell.addView(et)

        val mask = View(this).apply {
            background = getDrawable(R.drawable.bg_mask)
            setOnClickListener { if (valid()) commit(); closeHeight() }
        }
        val box = FrameLayout(this).apply {
            addView(shell)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { setMargins(Ui.dp(this@MainActivity, 28f), Ui.dp(this@MainActivity, 28f),
                Ui.dp(this@MainActivity, 28f), Ui.dp(this@MainActivity, 28f)) }
        }
        overlay.removeAllViews()
        overlay.addView(mask); overlay.addView(box)
        overlay.visibility = View.VISIBLE
        /* 弹窗一出来就聚焦 + 起键盘（豆包输入法会按 inputType 直接出九宫格数字盘） */
        et.requestFocus()
        et.post {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeHeight() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(overlay.windowToken, 0)
        overlay.visibility = View.GONE
        overlay.removeAllViews()
    }
}
