package com.beike.daytally

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.beike.daytally.widget.BodyFatWidget
import com.beike.daytally.widget.HeightWidget
import com.beike.daytally.widget.WeekWidget

/**
 * 开发用预览：把两个 4×2 大卡的 RemoteViews 按真机格子尺寸（940×692px）渲染出来，
 * 用来核对桌面实际效果，不依赖 launcher。入口隐藏，不在桌面显示。
 */
class WidgetPreviewActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = Repo.get(this)
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = ColorDrawable(Color.parseColor("#EFE7FA"))
            setPadding(0, px(20), 0, 0)
        }

        val week = WeekWidget.buildCard(this, repo, WeekWidget.currentKey(this))
            .apply(this, null)
        week.layoutParams = LinearLayout.LayoutParams(px(358), px(264))
        root.addView(week)

        val height = HeightWidget.buildCard(this, repo)
            .apply(this, null)
        height.layoutParams = LinearLayout.LayoutParams(px(358), px(264)).also {
            it.topMargin = px(14)
        }
        root.addView(height)

        val bodyFat = BodyFatWidget.buildCard(this)
            .apply(this, null)
        bodyFat.layoutParams = LinearLayout.LayoutParams(px(358), px(264)).also {
            it.topMargin = px(14)
        }
        root.addView(bodyFat)

        val wrap = FrameLayout(this).apply { addView(root) }
        setContentView(wrap)
    }
}
