package com.beike.daytally.xposed

import android.animation.ValueAnimator
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.RemoteViews
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
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
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
        private const val LONG_PRESS_MS = 150L
        private const val SLOP = 60f

        private const val ADD_COLOR = 0xFFC9414E.toInt()   // HTML: '#C9414E'
        private const val SUB_COLOR = 0xFF6E58BE.toInt()   // HTML: '#6E58BE'

        @Volatile private var pressKey: String? = null
        @Volatile private var pressHost: View? = null
        /** 实际接管事件的那一层 View（可能等于 pressHost，也可能是外层容器） */
        @Volatile private var pressLayer: View? = null
        @Volatile private var pressAt = 0L
        @Volatile private var pressDownTime = 0L
        @Volatile private var pressX = 0f
        @Volatile private var pressY = 0f
        @Volatile private var fired = false
        @Volatile private var lastLogAt = 0L
        @Volatile private var lastScan = 0L
        @Volatile private var lastEvt = 0L
        @Volatile private var lastHit = 0L

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
            /* MIUI 桌面在 AppWidgetHostView 外面又套了一层 LauncherWidgetView，
               真正的 host 存在 mAppWidgetHostView 字段里。 */
            runCatching {
                val host = XposedHelpers.getObjectField(v, "mAppWidgetHostView") as? AppWidgetHostView
                if (host != null) return host.appWidgetId
            }
            runCatching {
                val host = XposedHelpers.getObjectField(v, "mHostView") as? AppWidgetHostView
                if (host != null) return host.appWidgetId
            }
            return runCatching { XposedHelpers.getIntField(v, "mAppWidgetId") }.getOrDefault(-1)
        }

        /** View → key 缓存：keyOfHost 要跨进程查 AppWidgetInfo，放在触摸主线程必须缓存 */
        private val hostKeyCache = java.util.WeakHashMap<View, String>()

        /** 这个 View 是不是承载日刻方块的容器（大卡返回 null） */
        /** 从 AppWidgetHost 创建时抓到的所有方块容器（含 appWidgetId）。
            MIUI 桌面的 AppWidgetHostView 压根不在 DecorView 子树里（实测 rootKids=1、
            DragContainerLayout kids=0），所以只能从「创建那一刻」登记，点按的时候按坐标反查。 */
        private val hostViews = java.util.WeakHashMap<View, Int>()

        private fun keyOfHost(v: View): String? {
            hostKeyCache[v]?.let { return it }
            val id = widgetIdOf(v)
            if (id < 0) return null
            val ctx = v.context?.applicationContext ?: return null
            val info = runCatching { AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id) }.getOrNull() ?: return null
            val provider = info.provider?.className ?: return null
            if (!provider.startsWith("$SELF_PKG.widget.")) return null
            val k = keyOf(provider) ?: return null
            hostKeyCache[v] = k
            return k
        }

        /** 按 appWidgetId 反查方块 key（不依赖 View 在不在树里） */
        private fun keyOfHostId(ctx: Context, id: Int): String? {
            if (id < 0) return null
            val info = runCatching { AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id) }.getOrNull() ?: return null
            val provider = info.provider?.className ?: return null
            if (!provider.startsWith("$SELF_PKG.widget.")) return null
            return keyOf(provider)
        }

        /**
         * 按屏幕坐标反查已登记的方块容器。
         *
         * 历史坑：早前把 `getLocationOnScreen` 的 y 当成「中心 + 半高」，用 loc.y - h/2 当中心，
         * 命中区被整体抬高一整格（267px）。用户点上排「俯卧撑」，最近邻算出来的是下排
         * 「引体向上」→ 加组加错项目。真机复测（2026-11-20）：loc.y = 1383 时方块可见顶边
         * ≈1390，loc.y 就是**左上角**，中心应为 loc.y + h/2。
         *
         * 判定顺序：先看手指是否落在真实矩形里（落在里面最可信），都不在才退回
         * 最近邻 + 210px 阈值（容手指点偏一点）。
         */
        private fun hitHost(ctx: Context, sx: Float, sy: Float): Pair<String, View>? {
            if (hostViews.isEmpty()) return null
            val loc = IntArray(2)
            var inside: Pair<String, View>? = null
            var insideD = Float.MAX_VALUE
            var near: Pair<String, View>? = null
            var nearD = 210f
            val dbg = StringBuilder()
            for ((hv, id) in hostViews.entries.toList()) {
                val h = hv ?: continue
                if (h.visibility != View.VISIBLE) continue
                val k = keyOfHostId(ctx, id) ?: continue
                h.getLocationOnScreen(loc)
                val w = h.width.toFloat()
                val gh = h.height.toFloat()
                if (w <= 0f || gh <= 0f) continue
                val l = loc[0].toFloat()
                val t = loc[1].toFloat()
                val r = l + w
                val b = t + gh
                val isInside = sx >= l && sx <= r && sy >= t && sy <= b
                val cx = l + w / 2f
                val cy = t + gh / 2f
                val d = Math.hypot((sx - cx).toDouble(), (sy - cy).toDouble()).toFloat()
                dbg.append("$k@${cx.toInt()},${cy.toInt()}${if (isInside) " in" else ""} d=${d.toInt()};")
                if (isInside) {
                    if (d < insideD) { insideD = d; inside = k to h }
                } else if (d < nearD) {
                    nearD = d; near = k to h
                }
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastScan > 300) {
                lastScan = now
                log("SCAN raw=${sx.toInt()},${sy.toInt()} hit=${(inside ?: near)?.first} $dbg")
            }
            return inside ?: near
        }

        /** 命中点最上层的 View（局部坐标版，从后往前下钻 = 后添加的在上层） */
        private fun deepestAtLocal(root: View, sx: Float, sy: Float): View? {
            val rl = IntArray(2)
            root.getLocationOnScreen(rl)
            var res: View? = null

            fun walk(v: View, x: Float, y: Float): Boolean {
                if (v.visibility != View.VISIBLE) return false
                if (x < 0f || y < 0f || x >= v.width.toFloat() || y >= v.height.toFloat()) return false
                var childHit = false
                if (v is ViewGroup) {
                    var i = v.childCount - 1
                    while (i >= 0) {
                        val c = v.getChildAt(i)
                        if (walk(c, x + v.scrollX - c.left, y + v.scrollY - c.top)) {
                            childHit = true
                            break
                        }
                        i--
                    }
                }
                if (!childHit) {
                    res = v
                    return true
                }
                return true
            }
            walk(root, sx - rl[0], sy - rl[1])
            return res
        }

        /** 一次性把所有方块容器的屏幕几何打出来（含逐级 scale 累积），用于校正命中 */
        private fun scanDebug() {
            val loc = IntArray(2)
            val sb = StringBuilder()
            for ((hv, id) in hostViews.entries.toList()) {
                val h = hv ?: continue
                val c = h.context?.applicationContext ?: continue
                val k = keyOfHostId(c, id) ?: continue
                h.getLocationOnScreen(loc)
                var sxx = 1f
                var syy = 1f
                var p: View? = h
                var d = 0
                while (p != null && d < 8) {
                    sxx *= p.scaleX
                    syy *= p.scaleY
                    p = p.parent as? View
                    d++
                }
                sb.append("$k@${loc[0]},${loc[1]} ${h.width}x${h.height} sc=${sxx}x${syy} vis=${h.visibility};")
            }
            log("GEO $sb")
        }

        /** 反射拿当前进程所有窗口的 root（文件夹、多任务往往是独立窗口，不在桌面 DecorView 里） */
        private fun allRootViews(): List<View> {
            val out = ArrayList<View>()
            runCatching {
                val c = Class.forName("android.view.WindowManagerGlobal")
                val m = c.getDeclaredMethod("getInstance").apply { isAccessible = true }
                val inst = m.invoke(null)
                val f = c.getDeclaredField("mViews").apply { isAccessible = true }
                val views = f.get(inst) as? Array<*>
                views?.forEach { if (it is View) out.add(it) }
            }
            return out
        }

        /** 文件夹 / 多任务 / 任务卡盖在桌面上时为 true —— 按类名判定，完全不依赖坐标 */
        private fun overlayVisible(root: View?): Boolean {
            if (root != null && findOverlay(root, 0)) {
                log("OVERLAY in-root")
                return true
            }
            for (r in allRootViews()) {
                if (r === root || r.visibility != View.VISIBLE) continue
                if (findOverlay(r, 0)) {
                    log("OVERLAY in-win ${r.javaClass.simpleName}")
                    return true
                }
            }
            return false
        }

        private fun findOverlay(v: View, d: Int): Boolean {
            if (d > 26 || v.visibility != View.VISIBLE) return false
            val nm = v.javaClass.simpleName
            if ((nm == "Folder" || nm.contains("Recents") || nm.contains("MultiTask") || nm.contains("TaskView"))
                && v.width > 300 && v.height > 300
            ) {
                log("OVERLAY hit $nm ${v.width}x${v.height}")
                return true
            }
            if (v is ViewGroup) {
                var i = 0
                while (i < v.childCount) {
                    if (findOverlay(v.getChildAt(i), d + 1)) return true
                    i++
                }
            }
            return false
        }

        /** 局部坐标命中测试：完全避开 MIUI 桌面 getLocationOnScreen 偏移的坑 */
        private fun hitTest(root: View, sx: Float, sy: Float): Pair<String, View>? {
            val rl = IntArray(2)
            root.getLocationOnScreen(rl)
            var found: Pair<String, View>? = null
            var wcount = 0

            fun walk(v: View, x: Float, y: Float) {
                if (found != null) return
                if (v.visibility != View.VISIBLE) return
                if (x < 0f || y < 0f || x >= v.width.toFloat() || y >= v.height.toFloat()) return
                val k = keyOfHost(v)
                val nm = v.javaClass.simpleName
                if (nm.contains("Widget") || nm.contains("Host")) {
                    if (wcount < 10) {
                        wcount++
                        log("WALK $nm id=${widgetIdOf(v)} key=${k ?: "-"} wh=${v.width}x${v.height} at=${x.toInt()},${y.toInt()}")
                    }
                }
                if (k != null) {
                    found = k to v
                    return
                }
                if (v is ViewGroup) {
                    var i = 0
                    while (i < v.childCount) {
                        val c = v.getChildAt(i)
                        walk(c, x + v.scrollX - c.left - v.translationX, y + v.scrollY - c.top - v.translationY)
                        i++
                    }
                }
            }
            walk(root, sx - rl[0], sy - rl[1])
            return found
        }

        /**
         * 命中点上「最上层、最深」的可见 View。
         * MIUI 桌面把文件夹、多任务这些浮层都加在同一个窗口的视图树里（后添加的在上层），
         * 所以从后往前下钻就能找出此刻真正盖在这个坐标上的那个 View。
         */
        private fun deepestAt(v: View, x: Float, y: Float, depth: Int = 0): View? {
            if (depth > 30 || v.visibility != View.VISIBLE) return null
            if (v.width <= 0 || v.height <= 0) return null
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            if (x < loc[0] || x >= loc[0] + v.width || y < loc[1] || y >= loc[1] + v.height) return null
            if (v is ViewGroup) {
                var i = v.childCount - 1
                while (i >= 0) {
                    deepestAt(v.getChildAt(i), x, y, depth + 1)?.let { return it }
                    i--
                }
            }
            return v
        }

        /** child 是否在 ancestor 的子树里（含自身） */
        private fun isUnder(child: View, ancestor: View): Boolean {
            var p: View? = child
            var d = 0
            while (p != null && d < 40) {
                if (p === ancestor) return true
                p = p.parent as? View
                d++
            }
            return false
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

            fun progress(v: Float) {
                t = v
                /* 只失效文字所在的小矩形：铺满整屏的 View 若每帧全屏重绘，
                   桌面上会明显掉帧，看起来就"僵"。 */
                val (sc0, dy0, _) = keyframe(t)
                val halfW = textW * sc0 / 2f + stroke.strokeWidth + 4f
                val halfH = textH * sc0 / 2f + stroke.strokeWidth + 4f
                invalidate(
                    (anchorX - halfW).toInt(),
                    (anchorY + dy0 - halfH).toInt(),
                    (anchorX + halfW).toInt(),
                    (anchorY + dy0 + halfH).toInt()
                )
            }

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
            /* 必须挂到窗口最外层（DecorView），这样画布坐标才等于屏幕坐标；
               挂到 DragContainerLayout 这类内层容器，它的 top 不为 0，字会整体上移。 */
            val parent = ((root.rootView ?: root)) as? ViewGroup ?: return
            val ctx = parent.context ?: return
            val v = FloatNumView(ctx).apply {
                this.text = text
                this.fillColor = color
            }
            val sizePx = 21f * 2.625f           // HTML 18px × 1.1755 ≈ 21dp
            v.measureText(sizePx)
            /* 直接用屏幕坐标：DecorView 级浮层，parent 原点就是屏幕原点。
               不要减 parent.getLocationOnScreen —— 实测桌面窗口这个值不是 0，
               会把字拉到大卡上。 */
            v.anchorX = sx
            /* 方块顶 = 手指 y 往上推 200px（方块高 ~267，等于落在方块上沿略上方） */
            v.anchorY = sy - 200f
            log("FLOAT anchor=${v.anchorX.toInt()},${v.anchorY.toInt()} hh=${host.height} ph=${parent.height} pt=${parent.top}")
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

        private fun handleWidgetEvent(param: MethodHookParam) {
            val v = param.thisObject as? View ?: return
            val ev = param.args.getOrNull(0) as? MotionEvent ?: return
            handleWidgetTouch(v, ev, param)
        }

        /**
         * 直接挂在方块容器自己的 dispatchTouchEvent 上。
         * 这是唯一正确的接法：手指没落在方块上，事件根本到不了这里，
         * 所以文件夹、多任务、弹窗、别的应用怎么点都绝不会误触。
         */
        private fun handleWidgetTouch(v: View, ev: MotionEvent, param: MethodHookParam) {
            val key = keyOfHost(v) ?: return
            val ctx = v.context?.applicationContext ?: return
            val root = v.rootView ?: v

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ensureReceiver(ctx)
                    if (!hookEnabled) return
                    val token = pressToken.incrementAndGet()
                    pressKey = key
                    pressHost = v
                    pressLayer = v
                    pressAt = SystemClock.uptimeMillis()
                    pressDownTime = ev.downTime
                    pressX = ev.rawX
                    pressY = ev.rawY
                    fired = false
                    param.result = true
                    squash(v)

                    val task = Runnable {
                        if (token != pressToken.get() || pressKey != key) return@Runnable
                        fired = true
                        val c = v.context?.applicationContext ?: ctx
                        send(c, ACTION_REMOVE, key)
                        vibrate(v, 20)                       // HTML: haptic(20)
                        val per = perMap[key] ?: 0
                        if (per > 0) floatText(root, v, "−$per", SUB_COLOR, pressX, pressY)
                        log("LONG key=$key")
                    }
                    pressPending = task
                    v.postDelayed(task, LONG_PRESS_MS)
                    log("HIT key=$key src=widget")
                }

                MotionEvent.ACTION_MOVE -> {
                    if (pressLayer !== v) return
                    if (pressKey == null) return
                    if (Math.abs(ev.rawX - pressX) > SLOP || Math.abs(ev.rawY - pressY) > SLOP) {
                        pressPending?.let { pressHost?.removeCallbacks(it) }
                        pressPending = null
                        runCatching { pressHost?.let { restore(it) } }
                        pressKey = null
                        pressHost = null
                        pressLayer = null
                    }
                    param.result = true
                }

                MotionEvent.ACTION_UP -> {
                    if (pressLayer !== v) return
                    val k = pressKey ?: return
                    val host = pressHost ?: v
                    pressPending?.let { pressHost?.removeCallbacks(it) }
                    pressPending = null
                    if (!fired) {
                        send(ctx, ACTION_ADD, k)
                        vibrate(host, 12)                       // HTML: haptic(12)
                        popNumber(host)
                        val per = perMap[k] ?: 0
                        if (per > 0) floatText(root, host, "+$per", ADD_COLOR, pressX, pressY)
                        log("TAP key=$k held=${SystemClock.uptimeMillis() - pressAt}")
                    }
                    runCatching { restore(host) }
                    pressKey = null
                    pressHost = null
                    pressLayer = null
                    param.result = true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (pressLayer !== v) return
                    if (pressKey == null) return
                    pressPending?.let { pressHost?.removeCallbacks(it) }
                    pressPending = null
                    runCatching { pressHost?.let { restore(it) } }
                    pressKey = null
                    pressHost = null
                    pressLayer = null
                    param.result = true
                }
            }
        }

        /** 挂 MIUI / 标准两种方块容器的 dispatchTouchEvent */
        private fun hookWidgetTouch(cl: ClassLoader) {
            /* 首选：OnLongClickAgent —— 手指落在方块上时 MIUI 必定走这里，天然精确 */
            runCatching {
                val c = Class.forName("com.miui.home.launcher.OnLongClickAgent", false, cl)
                XposedHelpers.findAndHookMethod(
                    c, "onDispatchTouchEvent", MotionEvent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            runCatching {
                                val agent = param.thisObject ?: return@runCatching
                                val ev = param.args.getOrNull(0) as? MotionEvent ?: return@runCatching
                                val cv = XposedHelpers.getObjectField(agent, "mClientView") as? View
                                    ?: return@runCatching
                                if (keyOfHost(cv) == null) return@runCatching
                                handleWidgetTouch(cv, ev, param)
                            }.onFailure { log("agentEvent err: ${it.message}") }
                        }
                    }
                )
                log("OnLongClickAgent hook ok")
            }.onFailure { log("OnLongClickAgent hook fail: ${it.message}") }

            val names = listOf(
                "com.miui.home.launcher.LauncherAppWidgetHostView",
                "com.miui.home.launcher.LauncherWidgetView"
            )
            for (cn in names) {
                runCatching {
                    val c = Class.forName(cn, false, cl)
                    XposedHelpers.findAndHookMethod(
                        c, "dispatchTouchEvent", MotionEvent::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                runCatching { handleWidgetEvent(param) }
                                    .onFailure { log("widgetEvent err: ${it.message}") }
                            }
                        }
                    )
                    log("widget touch hook: $cn")
                }.onFailure { log("widget touch hook fail $cn: ${it.message}") }
            }
            runCatching {
                XposedHelpers.findAndHookMethod(
                    AppWidgetHostView::class.java, "dispatchTouchEvent", MotionEvent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            runCatching { handleWidgetEvent(param) }
                                .onFailure { log("widgetEvent err: ${it.message}") }
                        }
                    }
                )
                log("AppWidgetHostView touch hook ok")
            }.onFailure { log("AppWidgetHostView touch hook fail: ${it.message}") }
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

                        val __now = SystemClock.uptimeMillis()
                        if (ev.actionMasked == MotionEvent.ACTION_DOWN && __now - lastEvt > 2000) {
                            lastEvt = __now
                            log("EVT cls=${v.javaClass.simpleName} en=$hookEnabled hosts=${hostViews.size}")
                        }

                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                /* 同一手势会沿视图树逐层触发本钩子，共享同一个 downTime；
                                   第一层（必然是 DecorView）已经接管，后面每层都要把事件吃掉，
                                   否则事件会继续流到方块容器，触发 RemoteViews 自己的点击。 */
                                val sameGesture = pressKey != null && ev.downTime == pressDownTime
                                if (sameGesture) {
                                    param.result = true
                                    return
                                }
                                if (pressKey != null) {
                                    runCatching {
                                        pressPending?.let { pressHost?.removeCallbacks(it) }
                                        pressPending = null
                                        pressHost?.let { restore(it) }
                                        pressKey = null
                                        pressHost = null
                                        pressLayer = null
                                    }
                                }
                                ensureReceiver(ctx)
                                if (!hookEnabled) return
                                /* 不再用 hasWindowFocus() 当门禁：MIUI 桌面上它在文件夹/多任务态下
                                   依旧报 true（实测不可靠），遮挡交给 deepestAtLocal 判定。 */

                                /* ★★ 误触修复 ★★
                                   路径一：如果事件这一层本身就是方块容器，直接接管（最理想，
                                           被文件夹/多任务盖住时容器收不到事件，天然免疫）。
                                   路径二：MIUI 的组件容器不一定接得到 dispatchTouchEvent，所以留兜底——
                                           在 DecorView 层用 hitTest 命中方块，再做「遮挡检测」：
                                           命中点最上层最深的那个 View 必须属于该方块容器，否则说明
                                           上面盖着文件夹/多任务浮层，不接管。 */
                                /* ★★ 误触修复 ★★
                                   DecorView 往下，事件每经过一层就看这一层：
                                   命中点上方盖着的最上层子 View 是不是方块这一支的。
                                   - 是（Workspace → CellLayout → 方块容器）→ 正常接管
                                   - 不是（文件夹浮层 / 多任务卡片盖在上面）→ 立即放过，事件
                                     原样交给它们，绝不会再出现「点文件夹里的应用结果加了组」
                                   DecorView 只有一个全屏子，看不出区别，所以这一层直接放过。 */
                                if (v === root) return
                                val now0 = SystemClock.uptimeMillis()
                                if (now0 - lastHit > 300) {
                                    lastHit = now0
                                    log("DOWN layer=${v.javaClass.simpleName} raw=${ev.rawX.toInt()},${ev.rawY.toInt()}")
                                }

                                if (overlayVisible(root)) {
                                    log("OVERLAY skip raw=${ev.rawX.toInt()},${ev.rawY.toInt()}")
                                    return
                                }
                                var selfKey: String? = keyOfHost(v)
                                var host: View = v
                                if (selfKey == null) {
                                    /* 方块容器不在桌面 DecorView 子树里（实测确认），
                                       只能用登记表 + 屏幕坐标反查 */
                                    val hit = hitHost(v.context?.applicationContext ?: return, ev.rawX, ev.rawY)
                                        ?: return
                                    selfKey = hit.first
                                    host = hit.second
                                }
                                val key = selfKey ?: return

                                val token = pressToken.incrementAndGet()
                                pressKey = key
                                pressHost = host
                                pressLayer = v
                                pressAt = SystemClock.uptimeMillis()
                                pressDownTime = ev.downTime
                                pressX = ev.rawX
                                pressY = ev.rawY
                                fired = false
                                param.result = true
                                squash(host)

                                val task = Runnable {
                                    if (token != pressToken.get() || pressKey != key) return@Runnable
                                    fired = true
                                    val c = host.context?.applicationContext ?: ctx
                                    send(c, ACTION_REMOVE, key)
                                    vibrate(host, 20)                       // HTML: haptic(20)
                                    val per = perMap[key] ?: 0
                                    if (per > 0) floatText(root, host, "−$per", SUB_COLOR, pressX, pressY)
                                    log("LONG key=$key")
                                }
                                pressPending = task
                                host.postDelayed(task, LONG_PRESS_MS)

                                log("HIT key=$key layer=${v.javaClass.simpleName} host=${host.javaClass.simpleName}")
                            }

                            MotionEvent.ACTION_MOVE -> {
                                if (pressLayer !== v) return
                                if (pressKey == null) return
                                if (Math.abs(ev.rawX - pressX) > SLOP || Math.abs(ev.rawY - pressY) > SLOP) {
                                    pressPending?.let { pressHost?.removeCallbacks(it) }
                                    pressPending = null
                                    pressHost?.let { restore(it) }
                                    pressKey = null
                                    pressHost = null
                                    pressLayer = null
                                }
                                param.result = true
                            }

                            MotionEvent.ACTION_UP -> {
                                if (pressLayer !== v) return
                                val key = pressKey ?: return
                                val host = pressHost ?: v
                                pressPending?.let { pressHost?.removeCallbacks(it) }
                                pressPending = null
                                if (!fired) {
                                    send(ctx, ACTION_ADD, key)
                                    vibrate(host, 12)                       // HTML: haptic(12)
                                    popNumber(host)
                                    val per = perMap[key] ?: 0
                                    if (per > 0) floatText(root, host, "+$per", ADD_COLOR, pressX, pressY)
                                    log("TAP key=$key held=${SystemClock.uptimeMillis() - pressAt}")
                                }
                                restore(host)
                                pressKey = null
                                pressHost = null
                                pressLayer = null
                                param.result = true
                            }

                            MotionEvent.ACTION_CANCEL -> {
                                if (pressLayer !== v) return
                                if (pressKey == null) return
                                pressPending?.let { pressHost?.removeCallbacks(it) }
                                pressPending = null
                                pressHost?.let { restore(it) }
                                pressKey = null
                                pressHost = null
                                pressLayer = null
                                param.result = true
                            }
                        }
                    }
                }
            )
        }.onFailure { log("hook failed: $it") }

        hookWidgetTouch(lpparam.classLoader)

        /* MIUI 桌面的 AppWidgetHostView 不在 DecorView 子树里（实测 rootKids=1、
           DragContainerLayout kids=0），所以只能在「创建那一刻」把容器登记进 hostViews，
           点按的时候按窗口坐标反查。 */
        runCatching {
            XposedHelpers.findAndHookMethod(
                AppWidgetHost::class.java, "createView",
                Context::class.java,
                Int::class.javaPrimitiveType,
                AppWidgetProviderInfo::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.result as? View ?: return
                        val id = (param.args.getOrNull(1) as? Int) ?: -1
                        if (id >= 0) {
                            hostViews[view] = id
                            log("REG id=$id cls=${view.javaClass.simpleName}")
                        }
                    }
                }
            )
            log("createView hook installed")
        }.onFailure { log("createView hook failed: ${it.message}") }

        /* createView 只在新建时调一次，桌面启动时 widget 早已创建好，
           所以再兜一层 updateAppWidget——每次数据刷新都会经过这里。 */
        runCatching {
            XposedHelpers.findAndHookMethod(
                AppWidgetHostView::class.java, "updateAppWidget",
                RemoteViews::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? AppWidgetHostView ?: return
                        val id = runCatching { view.appWidgetId }.getOrNull() ?: -1
                        if (id >= 0 && !hostViews.containsKey(view)) {
                            hostViews[view] = id
                            log("REG2 id=$id cls=${view.javaClass.simpleName}")
                        }
                    }
                }
            )
            log("updateAppWidget hook installed")
        }.onFailure { log("updateAppWidget hook failed: ${it.message}") }

        log("long-press hook installed (${LONG_PRESS_MS}ms, float text, haptics)")
    }
}
