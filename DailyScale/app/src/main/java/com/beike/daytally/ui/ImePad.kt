package com.beike.daytally.ui

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 弹窗跟键盘让位。
 *
 * 默认把弹窗放在整屏中间；只有弹窗底边会被键盘盖住时，才往上抬“刚好避开键盘”的距离，
 * 并在弹窗和键盘之间留一点空隙。不做“键盘上方可用区居中”，否则短键盘时弹窗也会
 * 悬浮到上半屏。
 *
 * 两种窗口都要兼容：
 * - 透明 Activity / ADJUST_NOTHING：窗口没被压缩，ime 高度 > 0，按需上移；
 * - 普通 Activity / ADJUST_RESIZE：窗口已经缩到键盘上方，ime 高度是 0，弹窗原地居中即可。
 *
 * 只改 translationY，不动宽高和边距，所以键盘收起后弹窗会回到原位。
 */
object ImePad {

    fun bind(overlay: View, box: View, bottomGapPx: Int = 0) {
        var imeBottom = 0
        /* 没有键盘时的窗口高度。用于判断“窗口已经被系统压缩”，避免和 ime 位移叠加。 */
        var fullHeight = 0

        fun apply() {
            val h = overlay.height
            val boxH = box.height
            if (h <= 0 || boxH <= 0) return

            val lp = box.layoutParams as? ViewGroup.MarginLayoutParams
            val topMargin = lp?.topMargin ?: 0
            val bottomMargin = lp?.bottomMargin ?: 0

            /* 先记下无键盘时的高度；键盘弹出后窗口会变矮，但 fullHeight 保持原值。 */
            if (imeBottom <= 0 && h > fullHeight) fullHeight = h
            if (fullHeight <= 0) fullHeight = h

            val baseTop = topMargin + ((h - bottomMargin) - topMargin - boxH) / 2f

            /* 窗口已经被压缩：可见高度 + ime = 原窗口高度，这时弹窗已经自动在键盘上方了。
               只有窗口没被压缩（两者相加超过原高度）才需要主动上移。 */
            val resizedByIme = imeBottom > 0 && h + imeBottom <= fullHeight + 1

            /* 键盘上方“可落脚”的底边：只按键盘实际高度算，不再重复扣弹窗自身的
               bottomMargin（那是没键盘时贴着屏幕底边的设计边距，键盘弹起时不该再算一次），
               否则弹窗会被多推高一大截。 */
            val safeBottom = if (resizedByIme) {
                h - bottomMargin
            } else {
                h - imeBottom - bottomGapPx
            }

            /* 关键：不动就够用时，保持整屏居中；只有弹窗底边会碰到键盘时，才往上抬
               “刚好让出键盘 + 间距”的这一点。 */
            var shift = 0f
            if (!resizedByIme && imeBottom > 0) {
                val boxBottom = baseTop + boxH
                if (boxBottom > safeBottom) shift = safeBottom - boxBottom
                /* 弹窗比可用区还高时，至少保证标题不被顶出屏幕；极端情况只能压住底部。 */
                if (baseTop + shift < topMargin) shift = topMargin - baseTop
            }
            box.translationY = shift
        }

        ViewCompat.setOnApplyWindowInsetsListener(overlay) { _, insets ->
            /* ime 的 bottom 包含导航栏高度；导航栏本身已经被键盘盖住，先扣掉，
               否则弹窗和键盘之间会多出一条导航栏那么高的空隙。 */
            val imeRaw = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            imeBottom = (imeRaw - nav).coerceAtLeast(0)
            apply()
            insets
        }
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }

        overlay.post {
            ViewCompat.requestApplyInsets(overlay)
            apply()
        }
    }
}
