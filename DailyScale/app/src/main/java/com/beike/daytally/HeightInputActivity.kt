package com.beike.daytally

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.beike.daytally.bodyfat.BodyFatEntryActivity

/**
 * 旧版身高输入入口的兼容壳。
 *
 * 新版本不再手动记录身高：已经把 4×2 大卡改成肌肉重量，并统一走 OKOK 正常测量。
 * 旧桌面组件升级前留下的 PendingIntent 仍可能指向这里，所以只做一次转发，
 * 避免用户点旧组件时又看到已经废弃的“记录身高”弹窗。
 */
class HeightInputActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forward()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        forward()
    }

    private fun forward() {
        startActivity(
            Intent(this, BodyFatEntryActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        finish()
    }
}
