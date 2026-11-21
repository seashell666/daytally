package com.beike.daytally

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 只收日刻自己的桌面钩子心跳，别的应用发同 action 也进不来（token 对不上） */
class HookStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return
        if (intent.action != HookState.ACTION_ALIVE) return
        if (intent.getStringExtra("token") != HookState.TOKEN) return
        HookState.record(
            context.applicationContext,
            intent.getStringExtra("pkg"),
            intent.getBooleanExtra("hit", false)
        )
    }
}
