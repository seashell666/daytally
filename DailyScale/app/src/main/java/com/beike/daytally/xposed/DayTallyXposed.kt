package com.beike.daytally.xposed

import android.animation.ValueAnimator
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.core.animation.doOnEnd
import java.util.concurrent.atomic.AtomicInteger
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 桌面方块（4 个 1×1）的点击 / 长按手感，全部按 HTML 原型 floatText 的参数复刻。
 *
 * MIUI 桌面的触摸根本不进 AppWidgetHostView，所以只能在 dispatchTouchEvent 这一层
 * 把落在方块上的手势整条接过来：
 *   - 按下：方块缩到 0.93（复刻 CSS :active）
 *   - 抬手（<200ms）：加一组 → 数字 pop 1.15 + 浮动「+每组数」向上飘散
 *   - 按住（≥200ms）：退一组 → 浮动「−每组数」（紫色）
 *
 * 震动放在这一层做：桌面进程是前台、有 VIBRATE 权限，App 在后台会被系统禁掉震动。
 * 音效仍由 App 播放（后台不受限）。
 */
class DayTallyXposed : IXposedHookLoadPackage {

    companion object {
        private const val ACTION_ADD = "com.beike.daytally.WIDGET_ADD"
        private const val ACTION_REMOVE = "com.beike.daytally.WIDGET_REMOVE"
        private const val ACTION_PER_SYNC = "com.beike.daytally.PER_SYNC"
        /** App 内「接管桌面长按」开关：关掉即刻恢复原生长按，不用重启桌面 */
        private const val ACTION_HOOK_SWITCH = "com.beike.daytally.HOOK_SWITCH"
        private const val EXTRA_KEY = "metric_key"
        private const val TAG = "DayTally"
        private const val SELF_PKG = "com.beike.daytally"

        /** 长按阈值：0.2 秒（用户要求，原 380ms） */
        private const val LONG_PRESS_MS = 100L
        private const val SLOP = 60f

        private const val ADD_COLOR = 0xFFC9414E.toInt()   // HTML: '#C9414E'
        private const val SUB_COLOR = 0xFF6E58BE.toInt()   // HTML: '#6E58BE'

        @Volatile private var pressKey: String? = null
        @Volatile private var pressHost: View? = null
        @Volatile private var pressAt = 0L
        @Volatile private var pressDownTime = 0L
        @Volatile private var pressX = 0f
        @Volatile private var pressY = 0f
        @Volatile private var fired = false
        @Volatile private var lastLogAt = 0L

        /** 每一轮按压的唯一编号：旧计时器到点时编号对不上就作废，修掉连点误减组 */
        private val pressToken = AtomicInteger(0)
        @Volatile private var pressPending: Runnable? = null

        /** 每组多少个（给浮动数字用），由 App 广播同步过来 */
        @Volatile private var perMap: Map<String, Int> = mapOf(
            "push" to 12, "horse" to 11, "pullup" to 13, "custom" to 47
        )
        /** App 内开关播过来：不用重启桌面就能暂停/恢复接管 */
        @Volatile private var hookEnabled = true
        @Volatile private var receiverRegistered = false

        /** 当前前台 Activity。桌面进程一般有 GET_TASKS，先用 ActivityManager；
            拿不到再退回反射 ActivityThread.mActivities（多任务时桌面被 pause，只看 paused/stopped）。 */
        private fun topActivityName(root: View): String {
            runCatching {
                val am = root.context?.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                @Suppress("DEPRECATION")
                val tasks = am?.getRunningTasks(1)
                if (tasks != null && tasks.isNotEmpty()) {
                    val cn = tasks[0]?.topActivity
                    if (cn != null) return cn.className
                }
            }
            return runCatching {
                val cls = Class.forName("android.app.ActivityThread")
                val thread = cls.getMethod("currentActivityThread").invoke(null)
                val map = cls.getDeclaredField("mActivities").apply { isAccessible = true }.get(thread) as Map<*, *>
                var name = ""
                for (r in map.values) {
                    val rec = r ?: continue
                    fun boolField(f: String) = runCatching {
                        rec.javaClass.getDeclaredField(f).apply { isAccessible = true }.getBoolean(rec)
                    }.getOrDefault(false)
                    if (boolField("paused") || boolField("stopped")) continue
                    val a = runCatching {
                        rec.javaClass.getDeclaredField("activity").apply { isAccessible = true }.get(rec)
                    }.getOrNull() ?: continue
                    name = a.javaClass.name
                }
                name
            }.getOrDefault("")
        }

        private fun inRecents(v: View): Boolean {
            var p: android.view.ViewParent? = v.parent
            var depth = 0
            while (p != null && depth < 24) {
                val n = (p as? View)?.javaClass?.name ?: ""
                if (n.contains("Recents") || n.contains("recents")) return true
                p = p.parent
                depth++
            }
            return false
        }

        /** 最近任务的容器：只有当它真的在屏幕上占了一块地方才算，常驻但没显示的实例要排除 */
        private fun hasRecentsView(root: View): Boolean {
            var found = false
            val rect = android.graphics.Rect()
            fun walk(v: View, d: Int) {
                if (found || d > 12) return
                val n = v.javaClass.name
                if (v.visibility == View.VISIBLE &&
                    (n.contains("Recents", true) || n.contains("Overview", true))
                ) {
                    if (v.getGlobalVisibleRect(rect) && rect.width() > 60 && rect.height() > 60) {
                        found = true
                        return
                    }
                }
                if (v is ViewGroup) {
                    var i = 0
                    while (i < v.childCount) {
                        walk(v.getChildAt(i), d + 1)
                        i++
                    }
                }
            }
            walk(root, 0)
            return found
        }

        private fun keyOf(provider: String?): String? = when {
            provider == null -> null
            provider.endsWith("PushWidget") -> "push"
            provider.endsWith("HorseWidget") -> "horse"
            provider.endsWith("PullupWidget") -> "pullup"
            provider.endsWith("CustomWidget") -> "custom"
            else -> null
        }

        /** 反射取 appWidgetId：MIUI 的组件容器不一定叫 AppWidgetHostView，但都带这个字段 */
        private fun widgetIdOf(v: View): Int {
            (v as? AppWidgetHostView)?.let { return it.appWidgetId }
            return runCatching { XposedHelpers.getIntField(v, "mAppWidgetId") as Int }.getOrDefault(-1)
        }

        /** 这个 View 是不是承载日刻方块的容器（大卡返回 null） */
        private fun keyOfHost(v: View): String? {
            val id = widgetIdOf(v)
            if (id < 0) return null
            val ctx = v.context?.applicationContext ?: return null
            val info = runCatching { AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id) }.getOrNull() ?: return null
            val provider = info.provider?.className ?: return null
            if (!provider.startsWith("$SELF_PKG.widget.")) return null
            return keyOf(provider)
        }

        private fun hitTest(root: View, x: Float, y: Float): Pair<String, View>? {
            var found: Pair<String, View>? = null
            val loc = IntArray(2)

            fun walk(v: View) {
                if (found != null) return
                if (v.visibility == View.VISIBLE) {
                    v.getLocationOnScreen(loc)
                    if (x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height) {
                        val k = keyOfHost(v)
                        if (k != null) {
                            found = k to v
                            return
                        }
                    }
                }
                if (v is ViewGroup) {
                    var i = 0
                    while (i < v.childCount) {
                        walk(v.getChildAt(i))
                        i++
                    }
                }
            }
            walk(root)
            return found
        }

        /** 诊断：把该点下面所有带 appWidgetId 的容器及其位置打出来 */
        private fun probeHosts(root: View, x: Float, y: Float): String {
            val sb = StringBuilder()
            val loc = IntArray(2)
            fun walk(v: View, depth: Int) {
                if (depth > 14) return
                v.getLocationOnScreen(loc)
                val id = widgetIdOf(v)
                if (id >= 0) {
                    val proc = runCatching {
                        val c = v.context?.applicationContext
                        AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.provider?.className ?: "null"
                    }.getOrNull() ?: "err"
                    sb.append(v.javaClass.simpleName)
                        .append("[id=").append(id)
                        .append(",xy=").append(loc[0]).append(",").append(loc[1])
                        .append(",").append(v.width).append("x").append(v.height)
                        .append(",prov=").append(proc.substringAfterLast('.'))
                        .append(",").append(if (x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height) "HIT" else "out")
                        .append("] ")
                }
                if (v is ViewGroup) {
                    var i = 0
                    while (i < v.childCount) {
                        walk(v.getChildAt(i), depth + 1)
                        i++
                    }
                }
            }
            walk(root, 0)
            return if (sb.isEmpty()) "no-widget-view" else sb.toString()
        }

        private fun chainOf(root: View, x: Float, y: Float): String {
            var best: View? = null
            val loc = IntArray(2)

            fun walk(v: View) {
                if (v.visibility != View.VISIBLE) return
                if (v.width <= 0 || v.height <= 0) return
                v.getLocationOnScreen(loc)
                if (x < loc[0] || x > loc[0] + v.width || y < loc[1] || y > loc[1] + v.height) return
                best = v
                if (v is ViewGroup) {
                    var i = 0
                    while (i < v.childCount) {
                        walk(v.getChildAt(i))
                        i++
                    }
                }
            }
            walk(root)

            val sb = StringBuilder()
            var p: View? = best
            var n = 0
            while (p != null && n < 12) {
                sb.append(p.javaClass.name).append(" <- ")
                p = p.parent as? View
                n++
            }
            return sb.toString()
        }

        // ---------- 手感 ----------

        /** 按下：整块缩到 0.93（对齐 CSS .tile:active） */
        private fun squash(v: View) {
            runCatching {
                v.animate().cancel()
                v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(120).start()
            }
        }

        /** 抬手：弹回 1，带一点回弹（对齐 --spring） */
        private fun restore(v: View) {
            runCatching {
                v.animate().cancel()
                v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(PathInterpolator(0.34f, 1.56f, 0.64f, 1f))
                    .start()
            }
        }

        private fun vibrate(v: View, ms: Int) {
            runCatching {
                val ctx = v.context?.applicationContext ?: return
                val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
                if (!vib.hasVibrator()) return
                vib.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }

        /** 数字 pop：1 → 1.15 → 1（对齐 @keyframes pop，.38s） */
        private fun popNumber(host: View) {
            runCatching {
                val ctx = host.context?.applicationContext ?: return
                val res = ctx.packageManager.getResourcesForApplication(SELF_PKG)
                val id = res.getIdentifier("widget_cnt", "id", SELF_PKG)
                if (id == 0) return
                val tv = host.findViewById<TextView>(id) ?: return
                tv.animate().cancel()
                tv.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120)
                    .setInterpolator(PathInterpolator(0.34f, 1.56f, 0.64f, 1f))
                    .withEndAction {
                        tv.animate().scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(PathInterpolator(0.34f, 1.56f, 0.64f, 1f))
                            .start()
                    }.start()
            }
        }

        /**
         * 浮动数字：自绘 + 白色描边，四个方块走同一套代码，保证样式完全一致。
         * 动效复刻 HTML floatText：0% scale .65/op0 → 22% scale 1.1/上移 95%/op1
         * → 100% scale 1/上移 200%/op0，总 760ms，分段缓动 cubic-bezier(.22,.61,.36,1)。
         */
        private class FloatNumView(ctx: Context) : View(ctx) {
            var text: String = ""
            var fillColor: Int = Color.RED
            var anchorX = 0f
            var anchorY = 0f
            private var textW = 0f
            private var textH = 0f
            private var ascent = 0f
            private val ease = PathInterpolator(0.22f, 0.61f, 0.36f, 1f)
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                typeface = Typeface.DEFAULT_BOLD
            }
            private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                typeface = Typeface.DEFAULT_BOLD
                color = Color.WHITE
                strokeWidth = 2f * 2.625f      // 2px @ HTML 基准换算到真机 px
                strokeJoin = Paint.Join.ROUND
            }
            private var t = 0f
            private var running = false

            fun measureText(sizePx: Float) {
                fill.textSize = sizePx
                stroke.textSize = sizePx
                fill.color = fillColor
                textW = fill.measureText(text)
                val fm = fill.fontMetrics
                ascent = fm.ascent
                textH = fm.descent - fm.ascent
            }

            fun progress(v: Float) { t = v; invalidate() }

            override fun onDraw(canvas: Canvas) {
                if (text.isEmpty() || textH <= 0f) return
                val (sc, dy, op) = keyframe(t)
                canvas.save()
                canvas.translate(anchorX, anchorY + dy)
                canvas.scale(sc, sc)
                val a = (op.coerceIn(0f, 1f) * 255f).toInt()
                stroke.alpha = a
                fill.alpha = a
                val x = -textW / 2f
                val baseline = -ascent - textH / 2f     // 文字垂直中心对齐 (0,0)
                canvas.drawText(text, x, baseline, stroke)
                canvas.drawText(text, x, baseline, fill)
                canvas.restore()
            }

            /** t∈[0,1] → (scale, 上移距离(以文字高为基准), 透明度) */
            private fun keyframe(t: Float): Triple<Float, Float, Float> {
                return if (t <= 0.22f) {
                    val u = ease.getInterpolation((t / 0.22f).coerceIn(0f, 1f))
                    Triple(lerp(0.65f, 1.1f, u), lerp(0f, -0.95f, u) * textH, u)
                } else {
                    val u = ease.getInterpolation(((t - 0.22f) / 0.78f).coerceIn(0f, 1f))
                    Triple(lerp(1.1f, 1.0f, u), lerp(-0.95f, -2.0f, u) * textH, 1f - u)
                }
            }

            private fun lerp(a: Float, b: Float, u: Float) = a + (b - a) * u

            fun start() {
                if (running) return
                running = true
                val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 760L
                    interpolator = android.view.animation.LinearInterpolator()
                    addUpdateListener { progress(it.animatedValue as Float) }
                }
                anim.doOnEnd {
                    runCatching { (parent as? ViewGroup)?.removeView(this@FloatNumView) }
                }
                anim.start()
            }
        }

        /**
         * 浮动数字的锚点直接用「手指按下的屏幕坐标」。
         * 不用 host.getLocationOnScreen：MIUI 桌面在页面切换/缩放时这个 API 的 y 会算错
         * （实测 x 正确、y 少 400~500px，导致字飘到大卡上）。
         */
        private fun floatText(root: View, host: View, text: String, color: Int, sx: Float, sy: Float) {
            val ctx = root.context ?: return
            val parent = root as? ViewGroup ?: return
            val v = FloatNumView(ctx).apply {
                this.text = text
                this.fillColor = color
            }
            val sizePx = 21f * 2.625f           // HTML 18px × 1.1755 ≈ 21dp
            v.measureText(sizePx)
            val rl = IntArray(2)
            runCatching { parent.getLocationOnScreen(rl) }
            v.anchorX = sx - rl[0]
            /* 方块顶 = 手指 y 往上推半个方块高；HTML 起始点是元素顶 + 4px */
            v.anchorY = sy - host.height / 2f - rl[1] + 4f * 2.625f
            /* 铺满整屏：绘制坐标直接用屏幕坐标，父容器的 clipChildren 才不会把飘出去的字裁掉 */
            v.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            v.setWillNotDraw(false)
            v.isClickable = false
            v.isFocusable = false
            v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            runCatching {
                parent.addView(v)
                parent.bringChildToFront(v)
            }
            v.start()
        }

        private fun Int.dp(): Float = this * 2.625f

        private fun providerOf(key: String): String = when (key) {
            "push" -> "$SELF_PKG.widget.PushWidget"
            "horse" -> "$SELF_PKG.widget.HorseWidget"
            "pullup" -> "$SELF_PKG.widget.PullupWidget"
            else -> "$SELF_PKG.widget.CustomWidget"
        }

        private fun send(ctx: Context, action: String, key: String) {
            runCatching {
                val i = Intent(action)
                i.component = ComponentName(SELF_PKG, providerOf(key))
                i.putExtra(EXTRA_KEY, key)
                i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                ctx.sendBroadcast(i)
            }.onFailure { log("send failed: $it") }
        }

        /** 收 App 广播过来的「每组多少个」，供浮动数字显示 */
        private fun ensureReceiver(ctx: Context) {
            if (receiverRegistered) return
            synchronized(this) {
                if (receiverRegistered) return
                runCatching {
                    val r = object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, intent: Intent?) {
                            intent ?: return
                            if (intent.action == ACTION_HOOK_SWITCH) {
                                hookEnabled = intent.getBooleanExtra("enabled", true)
                                log("hook enabled = $hookEnabled")
                                return
                            }
                            if (intent.action != ACTION_PER_SYNC) return
                            val next = HashMap<String, Int>()
                            for (k in arrayOf("push", "horse", "pullup", "custom")) {
                                val v = intent.getIntExtra("per_$k", -1)
                                if (v > 0) next[k] = v
                            }
                            if (next.isNotEmpty()) perMap = perMap + next
                        }
                    }
                    val f = IntentFilter(ACTION_PER_SYNC).apply { addAction(ACTION_HOOK_SWITCH) }
                    try {
                        ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
                    } catch (t: Throwable) {
                        ctx.registerReceiver(r, f)
                    }
                    receiverRegistered = true
                }
            }
        }

        private fun log(msg: String) {
            XposedBridge.log("$TAG $msg")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (!pkg.contains("launcher") && !pkg.contains("home")) return
        runCatching {
            XposedHelpers.findAndHookMethod(
                View::class.java, "dispatchTouchEvent", MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        val ev = param.args.getOrNull(0) as? MotionEvent ?: return
                        val ctx = v.context?.applicationContext ?: return
                        val root = v.rootView ?: v
                        ensureReceiver(ctx)

                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                /* DOWN 会沿着视图树逐层触发本钩子。同一手势的每一层共享同一个
                                   downTime，用它区分「同一手势的另一层」和「新手势」：
                                   前者直接吃掉，后者无条件重置状态——这样即使某次 UP 丢失，
                                   钩子也不会被一个残留的 pressKey 永久卡死。 */
                                val sameGesture = pressKey != null && ev.downTime == pressDownTime
                                if (sameGesture) {
                                    param.result = true
                                    return
                                }
                                if (pressKey != null) {
                                    pressPending?.let { pressHost?.removeCallbacks(it) }
                                    pressPending = null
                                    pressHost?.let { restore(it) }
                                    pressKey = null
                                    pressHost = null
                                }
                                val hit = hitTest(root, ev.rawX, ev.rawY)
                                if (hit == null) {
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastLogAt > 400) {
                                        lastLogAt = now
                                        log("MISS cls=${v.javaClass.name} xy=${ev.rawX.toInt()},${ev.rawY.toInt()}")
                                    }
                                    return
                                }
                                /* 只有在桌面主界面才接管。注意：MIUI 13 的多任务是 RecentsAnimation，
                                   不产生新 Activity，顶层层级仍是 Launcher，所以这里只做能可靠判断的两项，
                                   宁可漏判也不误伤长按。 */
                                if (!hookEnabled) return
                                /* 多任务/编辑模式会让桌面窗口失焦，这条比类名判断可靠 */
                                if (!root.hasWindowFocus()) return
                                val top = topActivityName(root)
                                if (inRecents(hit.second) ||
                                    top.contains("Recents", true) || top.contains("Overview", true)
                                ) {
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastLogAt > 300) {
                                        lastLogAt = now
                                        log("SKIP recents top=$top")
                                    }
                                    return
                                }
                                if (top.isNotEmpty() &&
                                    !top.contains("Launcher", true) &&
                                    !top.contains("Workspace", true)
                                ) return
                                val token = pressToken.incrementAndGet()
                                pressKey = hit.first
                                pressHost = hit.second
                                pressAt = SystemClock.uptimeMillis()
                                pressDownTime = ev.downTime
                                pressX = ev.rawX
                                pressY = ev.rawY
                                fired = false
                                param.result = true
                                squash(hit.second)

                                val key = hit.first
                                val host = hit.second
                                val task = Runnable {
                                    if (token != pressToken.get() || pressKey != key) return@Runnable
                                    fired = true
                                    val c = host.context?.applicationContext ?: ctx
                                    send(c, ACTION_REMOVE, key)
                                    vibrate(host, 20)                       // HTML: haptic(20)
                                    val per = perMap[key] ?: 0
                                    if (per > 0) floatText(root, host, "−$per", SUB_COLOR, pressX, pressY)
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastLogAt > 300) {
                                        lastLogAt = now
                                        log("LONG key=$key")
                                    }
                                }
                                pressPending = task
                                host.postDelayed(task, LONG_PRESS_MS)
                            }

                            MotionEvent.ACTION_MOVE -> {
                                if (pressKey == null) return
                                if (Math.abs(ev.rawX - pressX) > SLOP || Math.abs(ev.rawY - pressY) > SLOP) {
                                    pressPending?.let { pressHost?.removeCallbacks(it) }
                                    pressPending = null
                                    pressHost?.let { restore(it) }
                                    pressKey = null
                                }
                                param.result = true
                            }

                            MotionEvent.ACTION_UP -> {
                                val key = pressKey ?: return
                                pressPending?.let { pressHost?.removeCallbacks(it) }
                                pressPending = null
                                val host = pressHost
                                if (!fired && host != null) {
                                    send(ctx, ACTION_ADD, key)
                                    vibrate(host, 12)                       // HTML: haptic(12)
                                    popNumber(host)
                                    val per = perMap[key] ?: 0
                                    if (per > 0) floatText(root, host, "+$per", ADD_COLOR, pressX, pressY)
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastLogAt > 300) {
                                        lastLogAt = now
                                        log("TAP key=$key held=${SystemClock.uptimeMillis() - pressAt}")
                                    }
                                }
                                host?.let { restore(it) }
                                pressKey = null
                                pressHost = null
                                param.result = true
                            }

                            MotionEvent.ACTION_CANCEL -> {
                                if (pressKey == null) return
                                pressPending?.let { pressHost?.removeCallbacks(it) }
                                pressPending = null
                                pressHost?.let { restore(it) }
                                pressKey = null
                                pressHost = null
                                param.result = true
                            }
                        }
                    }
                }
            )
        }.onFailure { log("hook failed: $it") }
        log("long-press hook installed (200ms, float text, haptics)")
    }
}
