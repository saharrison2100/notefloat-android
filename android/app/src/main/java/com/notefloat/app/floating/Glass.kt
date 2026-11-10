package com.notefloat.app.floating

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.WindowManager

/** Shared look for the floating windows: translucent glass with a soft edge, light and dark. */
object Glass {
    data class Palette(val panel: Int, val stroke: Int, val text: Int, val sub: Int, val accent: Int, val field: Int, val handle: Int)

    fun dark(c: Context) = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun palette(c: Context, highContrast: Boolean = false): Palette = if (dark(c))
        Palette(
            panel = Color.argb(if (highContrast) 245 else 222, 20, 24, 33), stroke = Color.argb(70, 255, 255, 255),
            text = Color.WHITE, sub = Color.argb(200, 205, 212, 226), accent = Color.rgb(124, 140, 255),
            field = Color.argb(70, 255, 255, 255), handle = Color.argb(235, 34, 40, 56))
    else Palette(
            panel = Color.argb(if (highContrast) 248 else 228, 252, 253, 255), stroke = Color.argb(90, 120, 130, 160),
            text = Color.rgb(16, 19, 26), sub = Color.rgb(84, 92, 110), accent = Color.rgb(61, 78, 200),
            field = Color.argb(90, 110, 120, 160), handle = Color.argb(240, 255, 255, 255))

    fun rounded(color: Int, radiusPx: Float, stroke: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, stroke)
    }

    fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics)
    fun dpi(c: Context, v: Float) = dp(c, v).toInt()

    @Suppress("DEPRECATION")
    fun overlayType() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

    /** Background blur behind the panel on Android 12+ where the device supports it. */
    fun applyBlur(lp: WindowManager.LayoutParams, radiusPx: Int) {
        if (Build.VERSION.SDK_INT >= 31) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.blurBehindRadius = radiusPx
        }
    }
}
