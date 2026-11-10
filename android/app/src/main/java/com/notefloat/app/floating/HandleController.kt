package com.notefloat.app.floating

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.notefloat.app.storage.Prefs

/**
 * The glass handle: a compact vertical pill on the left or right screen edge.
 *  - drag vertically to reposition; drag across the screen centre to switch edge
 *  - tap: expand the panel; double tap: quick capture; long press: hide
 * Position and edge are stored in Prefs so they survive restarts.
 */
class HandleController(
    private val ctx: Context,
    private val prefs: Prefs,
    private val onTap: () -> Unit,
    private val onDoubleTap: () -> Unit,
    private val onHideRequested: () -> Unit,
    private val onMoved: () -> Unit
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapMs = 280L

    private var downRawX = 0f; private var downRawY = 0f
    private var startX = 0; private var startY = 0
    private var dragging = false
    private var lastTapAt = 0L
    private var pendingTap: Runnable? = null
    private var longPress: Runnable? = null

    private fun screenW() = ctx.resources.displayMetrics.widthPixels
    private fun screenH() = ctx.resources.displayMetrics.heightPixels
    private fun thickness() = Glass.dpi(ctx, 30f)
    private fun heightPx() = Glass.dpi(ctx, prefs.handleSizeDp.toFloat())
    private fun usableTop() = Glass.dpi(ctx, 40f)
    private fun usableH() = screenH() - usableTop() - Glass.dpi(ctx, 90f) - heightPx()

    val currentBounds: IntArray get() = intArrayOf(lp?.x ?: 0, lp?.y ?: 0, thickness(), heightPx())

    fun show() {
        if (view != null) { view?.visibility = View.VISIBLE; reposition(); return }
        build()
    }

    fun hide() { view?.visibility = View.GONE }

    fun rebuild() { destroy(); build() }

    private fun build() {
        val p = Glass.palette(ctx, prefs.highContrast)
        val root = FrameLayout(ctx)
        val onLeft = prefs.edge == "LEFT"
        val r = Glass.dp(ctx, 16f)
        // flat side against the screen edge, rounded side facing inward
        root.background = Glass.rounded(p.handle, r, p.stroke, Glass.dpi(ctx, 1f)).apply {
            cornerRadii = if (onLeft) floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) else floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }
        root.alpha = prefs.handleOpacity / 100f
        root.elevation = Glass.dp(ctx, 6f)
        root.contentDescription = "NoteFloat sidebar handle. Tap to open, double tap for quick note, long press to hide."

        val icon = TextView(ctx).apply {
            text = "N"; setTextColor(p.accent); typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue_SP, 15f); gravity = Gravity.CENTER
        }
        root.addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Glass.dpi(ctx, 30f), Gravity.TOP))
        val grip = View(ctx).apply { background = Glass.rounded(p.sub, Glass.dp(ctx, 2f)) }
        root.addView(grip, FrameLayout.LayoutParams(Glass.dpi(ctx, 4f), Glass.dpi(ctx, 22f), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = Glass.dpi(ctx, 10f) })

        val params = WindowManager.LayoutParams(
            thickness(), heightPx(), Glass.overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        lp = params; view = root
        place(params)
        root.setOnTouchListener { _, e -> touch(e) }
        try { wm.addView(root, params) } catch (_: Exception) { view = null }
    }

    private fun place(p: WindowManager.LayoutParams) {
        p.width = thickness(); p.height = heightPx()
        p.x = if (prefs.edge == "LEFT") 0 else screenW() - thickness()
        p.y = usableTop() + (prefs.handleY.coerceIn(0f, 1f) * usableH()).toInt()
    }

    fun reposition() {
        val v = view ?: return; val p = lp ?: return
        // rebuild so the rounded side faces the right way after an edge switch
        place(p); v.alpha = prefs.handleOpacity / 100f
        rebuild(); onMoved()
    }

    private fun touch(e: MotionEvent): Boolean {
        val p = lp ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX; downRawY = e.rawY; startX = p.x; startY = p.y; dragging = false
                longPress = Runnable { if (!dragging) { vibrate(); onHideRequested() } }
                view?.postDelayed(longPress, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX; val dy = e.rawY - downRawY
                if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) { dragging = true; cancelLongPress() }
                if (dragging) {
                    p.x = (startX + dx).toInt().coerceIn(0, screenW() - thickness())
                    p.y = (startY + dy).toInt().coerceIn(usableTop(), usableTop() + usableH().coerceAtLeast(0))
                    view?.let { wm.updateViewLayout(it, p) }
                    onMoved()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                if (dragging) { settle(p) }
                else if (e.actionMasked == MotionEvent.ACTION_UP) tapped()
                dragging = false
            }
        }
        return true
    }

    /** After a drag: snap to the nearest edge (this is also how the user switches left/right). */
    private fun settle(p: WindowManager.LayoutParams) {
        val newEdge = if (p.x + thickness() / 2 < screenW() / 2) "LEFT" else "RIGHT"
        val changed = newEdge != prefs.edge
        prefs.edge = newEdge
        prefs.handleY = ((p.y - usableTop()).toFloat() / usableH().coerceAtLeast(1)).coerceIn(0f, 1f)
        val targetX = if (newEdge == "LEFT") 0 else screenW() - thickness()
        if (changed) { place(p); rebuild(); onMoved(); return }
        ValueAnimator.ofInt(p.x, targetX).apply {
            duration = 160; interpolator = DecelerateInterpolator()
            addUpdateListener { a -> p.x = a.animatedValue as Int; view?.let { v -> runCatching { wm.updateViewLayout(v, p) } } }
            start()
        }
        onMoved()
    }

    private fun tapped() {
        val now = System.currentTimeMillis()
        if (now - lastTapAt < doubleTapMs) {
            pendingTap?.let { view?.removeCallbacks(it) }; pendingTap = null; lastTapAt = 0; onDoubleTap()
        } else {
            lastTapAt = now
            pendingTap = Runnable { pendingTap = null; onTap() }
            view?.postDelayed(pendingTap, doubleTapMs)
        }
    }

    private fun cancelLongPress() { longPress?.let { view?.removeCallbacks(it) }; longPress = null }
    private fun vibrate() { view?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) }

    fun destroy() {
        pendingTap?.let { view?.removeCallbacks(it) }; cancelLongPress()
        view?.let { runCatching { wm.removeView(it) } }
        view = null; lp = null
    }

    private companion object { const val TypedValue_SP = android.util.TypedValue.COMPLEX_UNIT_SP }
}
