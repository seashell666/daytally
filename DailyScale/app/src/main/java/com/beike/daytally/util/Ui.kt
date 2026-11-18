package com.beike.daytally.util

import android.content.Context

object Ui {
    /**
     * 原型设计稿的屏幕逻辑宽度是 350px（.device 368px 减去两侧各 9px 边框内衬）。
     * 本机是 411dp 宽（1080px / density 2.625），所以要 1.175 倍等比放大，
     * 元素/字号/间距视觉比例才与原型一致；直接 1px=1dp 会让卡片显得扁、字显得小。
     */
    const val S = 1.175f

    /** 布局尺寸：原型 px → 本机 dp → 物理像素（含等比缩放） */
    fun dp(c: Context, v: Float): Int = Math.round(v * S * c.resources.displayMetrics.density)

    /** 字号：返回“已按比例放大”的 sp 值，仍需 setTextSize(COMPLEX_UNIT_SP, …) */
    fun sp(v: Float): Float = v * S

    /** 桌面组件专用：不参与原型等比缩放（格子尺寸由 launcher 决定） */
    fun wdp(c: Context, v: Float): Int = Math.round(v * c.resources.displayMetrics.density)
}
