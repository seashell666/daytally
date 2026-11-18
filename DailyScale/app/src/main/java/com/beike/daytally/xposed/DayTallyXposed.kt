package com.beike.daytally.xposed

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 长按桌面方块 = 退一组（标准 AppWidget API 拦不住长按，只能走框架）。
 *
 * 做法：在桌面进程 hook View.performLongClick。如果被长按的是日刻的 1×1 计数方块，
 * 就发一条 WIDGET_REMOVE 广播给 App，并返回 true 把系统原本的「拖拽 / 移除」拦掉。
 * 没装 Vector（或没勾选本模块）时这段不会执行，其余功能一切照常。
 */
class DayTallyXposed : IXposedHookLoadPackage {

    companion object {
        private const val ACTION_REMOVE = "com.beike.daytally.WIDGET_REMOVE"
        private const val EXTRA_KEY = "metric_key"
        private const val TAG = "DayTally"

        private fun keyOf(className: String): String? = when {
            className.endsWith("PushWidget") -> "push"
            className.endsWith("HorseWidget") -> "horse"
            className.endsWith("PullupWidget") -> "pullup"
            className.endsWith("CustomWidget") -> "custom"
            else -> null
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        // 只在桌面进程里干活
        if (!pkg.contains("launcher") && !pkg.contains("home")) return
        runCatching { hookLongPress(lpparam.classLoader) }
            .onFailure { XposedBridge.log("$TAG hook failed: $it") }
    }

    private fun hookLongPress(cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            View::class.java, "performLongClick",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    val cls = v.javaClass.name
                    if (!cls.contains("AppWidgetHostView")) return

                    val ctx = v.context?.applicationContext ?: return
                    val id = runCatching {
                        XposedHelpers.getIntField(v, "mAppWidgetId")
                    }.getOrNull() ?: return

                    val mgr = AppWidgetManager.getInstance(ctx) ?: return
                    val info = mgr.getAppWidgetInfo(id) ?: return
                    val providerName = info.provider?.className ?: return
                    if (!providerName.startsWith("com.beike.daytally.widget.")) return

                    val key = keyOf(providerName) ?: return

                    // 按下反馈：整块缩一下再弹回，和 App 内长按的手感对齐
                    runCatching {
                        v.animate().cancel()
                        v.animate()
                            .scaleX(0.90f).scaleY(0.90f)
                            .setDuration(90)
                            .withEndAction {
                                runCatching {
                                    v.animate()
                                        .scaleX(1f).scaleY(1f)
                                        .setDuration(160)
                                        .start()
                                }
                            }
                            .start()
                    }

                    val i = Intent(ACTION_REMOVE).setPackage("com.beike.daytally")
                    i.putExtra(EXTRA_KEY, key)
                    ctx.sendBroadcast(i)

                    // 拦掉系统长按：不弹「移除 / 编辑」那一套
                    param.result = true
                }
            }
        )
        XposedBridge.log("$TAG long-press hook installed")
    }
}
