package com.beike.daytally.xposed

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 桌面长按退组。
 *
 * 为什么 MIUI 上一直不生效：桌面全程用自己的一层容器收触摸，事件根本不会走到
 * AppWidgetHostView 上，长按菜单也不是通过 View.performLongClick 弹的（实测日志里
 * 只有 launcher 自己的 DragContainerLayout，且没有任何 performLongClick 调用）。
 *
 * 现在的做法：只接管 4 个 1×1 方块的触摸，其余武汉一律不碰。
 *   - 在根 View（DecorView）的 dispatchTouchEvent 里判断按下的点是否落在日刻方块上；
 *   - 落在方块上就把这条手势整个吃掉，桌面因此完全收不到，长按菜单再也不会弹；
 *   - 自己计时：按住不足 380ms 抬手 = 短按加一组；按住超过 380ms = 退一组 + 缩放回弹。
 *
 * 大卡（身高卡 / 计数卡）不参与接管，它们原来的点击行为保持不变。
 * 没装 Vector 或没勾选本模块时不执行任何逻辑，其余功能照常。
 */
class DayTallyXposed : IXposedHookLoadPackage {

    companion object {
        private const val ACTION_ADD = "com.beike.daytally.WIDGET_ADD"
        private const val ACTION_REMOVE = "com.beike.daytally.WIDGET_REMOVE"
        private const val EXTRA_KEY = "metric_key"
        private const val TAG = "DayTally"
        private const val LONG_PRESS_MS = 380L
        private const val SLOP = 60f

        @Volatile private var pressKey: String? = null
        @Volatile private var pressHost: View? = null
        @Volatile private var pressAt = 0L
        @Volatile private var pressX = 0f
        @Volatile private var pressY = 0f
        @Volatile private var fired = false
        @Volatile private var lastLogAt = 0L

        private fun keyOf(provider: String?): String? = when {
            provider == null -> null
            provider.endsWith("PushWidget") -> "push"
            provider.endsWith("HorseWidget") -> "horse"
            provider.endsWith("PullupWidget") -> "pullup"
            provider.endsWith("CustomWidget") -> "custom"
            else -> null
        }

        /** 这个 host 视图承载的是哪个日刻方块（大卡返回 null） */
        private fun keyOfHost(v: View): String? {
            val ctx = v.context?.applicationContext ?: return null
            val id: Int = (v as? AppWidgetHostView)?.appWidgetId
                ?: (runCatching { XposedHelpers.getIntField(v, "mAppWidgetId") }.getOrNull() as? Int)
                ?: return null
            val info = runCatching { AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id) }.getOrNull() ?: return null
            val provider = info.provider?.className ?: return null
            if (!provider.startsWith("com.beike.daytally.widget.")) return null
            return keyOf(provider)
        }

        /** 从根 View 往下找，命中的点是哪个日刻方块 */
        private fun hitTest(root: View, x: Float, y: Float): Pair<String, View>? {
            var found: Pair<String, View>? = null
            val loc = IntArray(2)

            fun walk(v: View) {
                if (found != null) return
                if (v.visibility == View.VISIBLE && v.javaClass.name.contains("AppWidgetHostView")) {
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

        /** 按下反馈：整块缩一下再弹回 */
        private fun bump(v: View) {
            runCatching {
                v.animate().cancel()
                v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(90).withEndAction {
                    runCatching { v.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }
                }.start()
            }
        }

        /** 诊断：打印坐标命中的最深 View 及其祖先链，用来认清桌面里组件容器到底是什么类 */
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

        private fun providerOf(key: String): String = when (key) {
            "push" -> "com.beike.daytally.widget.PushWidget"
            "horse" -> "com.beike.daytally.widget.HorseWidget"
            "pullup" -> "com.beike.daytally.widget.PullupWidget"
            else -> "com.beike.daytally.widget.CustomWidget"
        }

        /** 显式发给对应组件的 receiver：隐式广播 MIUI 收不到，必须指定 component */
        private fun send(ctx: android.content.Context, action: String, key: String) {
            runCatching {
                val i = Intent(action)
                i.component = android.content.ComponentName("com.beike.daytally", providerOf(key))
                i.putExtra(EXTRA_KEY, key)
                i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                ctx.sendBroadcast(i)
            }.onFailure { log("send failed: $it") }
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

                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                // 同一个手势会经过多层 View，已经在处理就跳过
                                if (pressKey != null) {
                                    param.result = true
                                    return
                                }
                                val hit = hitTest(root, ev.rawX, ev.rawY)
                                if (hit == null) {
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastLogAt > 400) {
                                        lastLogAt = now
                                        log("MISS cls=${v.javaClass.name} xy=${ev.rawX.toInt()},${ev.rawY.toInt()} chain=${chainOf(root, ev.rawX, ev.rawY)}")
                                    }
                                    return
                                }
                                pressKey = hit.first
                                pressHost = hit.second
                                pressAt = SystemClock.uptimeMillis()
                                pressX = ev.rawX
                                pressY = ev.rawY
                                fired = false
                                param.result = true           // 吃掉：桌面完全收不到这次手势
                                val host = hit.second
                                val key = hit.first
                                v.postDelayed({
                                    if (fired || pressKey != key) return@postDelayed
                                    fired = true
                                    val c = host.context?.applicationContext ?: ctx
                                    send(c, ACTION_REMOVE, key)
                                    bump(host)
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastLogAt > 300) {
                                        lastLogAt = now
                                        log("LONG key=$key")
                                    }
                                }, LONG_PRESS_MS)
                            }

                            MotionEvent.ACTION_MOVE -> {
                                if (pressKey == null) return
                                if (Math.abs(ev.rawX - pressX) > SLOP || Math.abs(ev.rawY - pressY) > SLOP) {
                                    pressKey = null                 // 划走了，放弃这次
                                }
                                param.result = true
                            }

                            MotionEvent.ACTION_UP -> {
                                val key = pressKey ?: return
                                val held = SystemClock.uptimeMillis() - pressAt
                                if (!fired && held < LONG_PRESS_MS + 120) {
                                    send(ctx, ACTION_ADD, key)
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastLogAt > 300) {
                                        lastLogAt = now
                                        log("TAP key=$key held=$held")
                                    }
                                }
                                pressKey = null
                                param.result = true
                            }

                            MotionEvent.ACTION_CANCEL -> {
                                if (pressKey == null) return
                                pressKey = null
                                param.result = true
                            }
                        }
                    }
                }
            )
        }.onFailure { log("hook failed: $it") }
        log("long-press hook installed")
    }
}
