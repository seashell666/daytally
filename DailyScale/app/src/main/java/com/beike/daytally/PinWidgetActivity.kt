package com.beike.daytally

import android.app.Activity
import android.appwidget.AppWidgetManager as WidgetManager
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.beike.daytally.widget.HeightWidget

/**
 * 只给日刻自己用的“请求固定桌面组件”入口。
 *
 * 不是普通用户能点到的页面：exported=false，透明、无标题、启动后立即结束。
 * 它只调用系统标准 requestPinAppWidget，让桌面弹“要不要添加这个组件”，
 * 由用户点头后由桌面自己分配 appWidgetId，绝不直接改桌面数据库。
 */
class PinWidgetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(WidgetManager::class.java)
            if (manager != null && manager.isRequestPinAppWidgetSupported) {
                val provider = ComponentName(this, HeightWidget::class.java)
                manager.requestPinAppWidget(provider, null, null)
            }
        }
        // 系统确认框需要宿主 Activity 还在前台；给它 12 秒操作窗口，再自动退出。
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 12_000)
    }
}
