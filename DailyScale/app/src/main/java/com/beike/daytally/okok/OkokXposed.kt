package com.beike.daytally.okok

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap

/**
 * 日刻本体里的 OKOK 适配层。
 *
 * 这一层原来是独立的 OKOKDeclutter 模块；现在整体并进日刻，用户在 Vector 里
 * 只需要给日刻勾上「系统桌面 + OKOK」两个作用域，不再安装第二个 APK。
 *
 * 只做三件事：
 * 1. 进入 OKOK 时跳过它自己的开屏广告等待（登录/账号仍走 OKOK 原流程）；
 * 2. 隐藏 OKOK 页面里已知的广告位和首页活动弹窗；
 * 3. 在 OKOK 官方称重完成时，把真实体脂率 / 肌肉重量通过日刻给的 PendingIntent 回传。
 *
 * 版本门仍然只接受 3.7.63 / 255；版本不对就完全不装 Hook，避免误伤。
 */
class OkokXposed : IXposedHookLoadPackage {

    private val resourceNames = ConcurrentHashMap<Int, String>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != OkokRules.TARGET_PACKAGE) return
        val classLoader = lpparam.classLoader ?: return

        val versionCode = readVersionCode()
        if (!OkokRules.supportsVersion(versionCode ?: -1L)) {
            log("unsupported OKOK version ${versionCode ?: "unknown"}; no hooks installed")
            return
        }

        installSplashBypass(classLoader)
        installHomeAdDialogHook()
        installViewHooks()
        installMeasurementBridge(classLoader)
        log("active for OKOK ${OkokRules.SUPPORTED_VERSION_NAME} (${OkokRules.SUPPORTED_VERSION_CODE})")
    }

    /**
     * 保留 OKOK 原来的登录/账号路由。它自己的开屏加载仍会跑，加载完成后
     * 直接走 App 原本的 doJump()，不再等广告倒计时。
     */
    private fun installSplashBypass(classLoader: ClassLoader) {
        runCatching {
            val initActivity = classLoader.loadClass("com.chipsea.btcontrol.InitActivity")
            XposedHelpers.findAndHookMethod(
                initActivity, "toLoad",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        activity.runOnUiThread {
                            runCatching {
                                val doJump = activity.javaClass
                                    .getDeclaredMethod("doJump")
                                    .apply { isAccessible = true }
                                doJump.invoke(activity)
                            }.onFailure { log("splash jump failed: ${it.message}") }
                        }
                    }
                }
            )
        }.onFailure { log("splash hook unavailable: ${it.message}") }
    }

    /**
     * OKOK 首页的 HomePopDilog 是服务端下发的活动弹窗；只拦这个类自己的 show()。
     * 不全局屏蔽 Dialog，也不碰账号、跳转或健康数据。
     */
    private fun installHomeAdDialogHook() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Dialog::class.java, "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val dialog = param.thisObject as? Dialog ?: return
                        if (OkokRules.isHomeAdDialog(dialog.javaClass.name)) {
                            log("suppressed home operation popup")
                            param.result = null
                        }
                    }
                }
            )
        }.onFailure { log("home popup hook unavailable: ${it.message}") }
    }

    private fun installViewHooks() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                View::class.java, "setVisibility", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val requested = param.args.getOrNull(0) as? Int ?: return
                        if (requested != View.GONE && shouldHide(view)) {
                            param.args[0] = View.GONE
                            collapseIfNeeded(view)
                        }
                    }
                }
            )
        }.onFailure { log("visibility hook unavailable: ${it.message}") }

        runCatching {
            XposedHelpers.findAndHookMethod(
                View::class.java, "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        if (shouldHide(view)) {
                            view.visibility = View.GONE
                            collapseIfNeeded(view)
                        }
                    }
                }
            )
        }.onFailure { log("attach hook unavailable: ${it.message}") }

        // 签到入口这类文字会晚于 attach 才 setText，挂一下 setText 再判断一次。
        // 只有文案命中规则才去查 Activity，避免影响称重数字那种高频刷新。
        runCatching {
            XposedHelpers.findAndHookMethod(
                TextView::class.java, "setText",
                CharSequence::class.java, TextView.BufferType::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? TextView ?: return
                        val text = view.text?.toString() ?: return
                        if (OkokRules.shouldHideText(text) && shouldHide(view)) {
                            view.visibility = View.GONE
                        }
                    }
                }
            )
        }.onFailure { log("text hook unavailable: ${it.message}") }
    }

    /**
     * 日刻通过官方 AppWidget://WeightUpScale 深链进入 OKOK 自己的称重流程。
     * MainActivity 收到启动 Intent 时暂存日刻的 PendingIntent；
     * BluetoothUpScaleActivity.startReportActivity(WeightEntity) 时读取结果并回传。
     */
    private fun installMeasurementBridge(classLoader: ClassLoader) {
        runCatching {
            val mainActivity = classLoader.loadClass("com.chipsea.btcontrol.MainActivity")
            XposedHelpers.findAndHookMethod(
                mainActivity, "onCreate", Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        MeasurementBridge.capture(activity, activity.intent)
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                mainActivity, "onNewIntent", Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val intent = param.args.getOrNull(0) as? Intent ?: return
                        MeasurementBridge.capture(activity, intent)
                    }
                }
            )
        }.onFailure { log("measurement launch hook unavailable: ${it.message}") }

        runCatching {
            val upScale = classLoader.loadClass(
                "com.chipsea.btcontrol.homePage.BluetoothUpScaleActivity"
            )
            val entity = classLoader.loadClass("com.chipsea.code.model.WeightEntity")
            XposedHelpers.findAndHookMethod(
                upScale, "startReportActivity", entity,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val value = param.args.getOrNull(0) ?: return
                        MeasurementBridge.deliver(activity, value)
                    }
                }
            )
        }.onFailure { log("measurement result hook unavailable: ${it.message}") }
    }

    private fun resourceNameOf(view: View): String? = runCatching {
        resourceNames[view.id] ?: view.resources
            .getResourceEntryName(view.id)
            .also { resourceNames[view.id] = it }
    }.getOrNull()

    /** 底部页签这类，光设 GONE 会留一个空槽，要从父容器里摘掉让剩下的重新平分 */
    private fun collapseIfNeeded(view: View) {
        runCatching {
            if (view.id == View.NO_ID) return@runCatching
            val activity = findActivity(view.context) ?: return@runCatching
            val resourceName = resourceNameOf(view) ?: return@runCatching
            if (!OkokRules.shouldRemoveView(resourceName, activity.javaClass.name)) return@runCatching
            view.post {
                runCatching {
                    val parent = view.parent as? ViewGroup ?: return@runCatching
                    parent.removeView(view)
                    log("collapsed $resourceName")
                }
            }
        }
    }

    private fun shouldHide(view: View): Boolean = runCatching {
        val activity = findActivity(view.context) ?: return@runCatching false
        if (view is TextView &&
            activity.javaClass.name == OkokRules.HOME_ACTIVITY &&
            OkokRules.shouldHideText(view.text?.toString() ?: "")
        ) return@runCatching true
        if (view.id == View.NO_ID) return@runCatching false
        val resourceName = resourceNameOf(view) ?: return@runCatching false
        OkokRules.shouldHideResource(resourceName, activity.javaClass.name)
    }.getOrDefault(false)

    private fun findActivity(context: Context): Activity? {
        var current: Context = context
        var depth = 0
        while (depth < 8) {
            when (val candidate = current) {
                is Activity -> return candidate
                is ContextWrapper -> {
                    val base = candidate.baseContext
                    if (base === current) return null
                    current = base
                }
                else -> return null
            }
            depth++
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun readVersionCode(): Long? = runCatching {
        val context = currentSystemContext() ?: return@runCatching null
        val packageInfo = context.packageManager.getPackageInfo(OkokRules.TARGET_PACKAGE, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
    }.onFailure { log("failed to read host version: ${it.message}") }.getOrNull()

    @SuppressLint("PrivateApi")
    private fun currentSystemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread")
            .invoke(null)
        activityThread?.javaClass?.getMethod("getSystemContext")
            ?.invoke(activityThread) as? Context
    }.getOrNull()

    private fun log(message: String) {
        XposedBridge.log("DayTallyOKOK $message")
    }
}
