package com.beike.daytally.util

import android.graphics.Color

/** 原型色板，全部来自定稿 HTML */
object C {
    private fun hex(h: String) = Color.parseColor(h)

    const val INK = 0xFF2B2338.toInt()
    const val INK2 = 0xFF6A6079.toInt()
    const val INK3 = 0xFFA296B2.toInt()
    const val PAPER = 0xFFFBF8FF.toInt()
    const val RED = 0xFFD2505C.toInt()
    const val RED_DK = 0xFFB43644.toInt()
    const val PURPLE = 0xFF8B76D9.toInt()
    const val PURPLE_DEEP = 0xFF6E58BE.toInt()
    const val GREEN = 0xFF2E9A6B.toInt()
    const val FLAT = 0xFF8E85A0.toInt()
    const val GOLD_TEXT = 0xFF937CDF.toInt()

    val INK_UP = hex("#D2505C")
    val INK_DN = hex("#2E9A6B")
    val INK_NONE = hex("#A99FB8")

    /** 计数档位背景 0~7+ */
    val BG_STEPS = intArrayOf(
        hex("#F4EEFE"), hex("#FCEBED"), hex("#F8CDD3"), hex("#F0A6AF"),
        hex("#E27788"), hex("#CC4A5D"), hex("#B02B40"), hex("#94172C")
    )

    /** 计数档位前景 0~7+（1 档跟 0 档同色，见 fgIndex） */
    val FG_WHITE = intArrayOf(
        hex("#8E85A0"),
        0xB3FFFFFFL.toInt(), 0xC7FFFFFFL.toInt(), 0xD9FFFFFFL.toInt(),
        0xE6FFFFFFL.toInt(), 0xF2FFFFFFL.toInt(), 0xFAFFFFFFL.toInt(),
        hex("#FFFFFF")
    )

    val GOLD = hex("#F7C325")
    val GOLD_DEEP = hex("#E0A10C")
    val GOLD_HI = hex("#FFDE73")
}
