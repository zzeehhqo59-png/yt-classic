package com.bear.ytclassic

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Player container that steals a vertical drag from any child (player, error text, spinner)
 * and reports it, so swipe-to-minimize works whatever the player is showing.
 * [direction] = +1 for a downward drag (watch page), -1 for upward (mini player).
 */
class DragFrame @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : FrameLayout(c, a) {
    interface Listener {
        fun onDragStart()
        fun onDrag(dy: Float)
        fun onDragEnd(velocityY: Float)
    }
    var listener: Listener? = null
    var canDrag: () -> Boolean = { true }
    var direction = 1
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    private fun track(ev: MotionEvent) {
        val t = tracker ?: VelocityTracker.obtain().also { tracker = it }
        val copy = MotionEvent.obtain(ev); copy.setLocation(ev.rawX, ev.rawY); t.addMovement(copy); copy.recycle()
    }

    private fun maybeStart(ev: MotionEvent): Boolean {
        if (dragging || listener == null || !canDrag()) return dragging
        val dy = (ev.rawY - downY) * direction
        val dx = ev.rawX - downX
        if (dy > slop * 1.5f && dy > abs(dx) * 1.3f) {
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
            downY = ev.rawY
            listener?.onDragStart()
        }
        return dragging
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; dragging = false; tracker?.clear(); track(ev) }
            MotionEvent.ACTION_MOVE -> { track(ev); return maybeStart(ev) }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; dragging = false; tracker?.clear(); track(ev); return true }
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                if (maybeStart(ev)) listener?.onDrag(ev.rawY - downY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                track(ev)
                if (dragging) {
                    // emulators / busy phones batch moves: apply the final finger position before deciding
                    if (ev.actionMasked == MotionEvent.ACTION_UP) listener?.onDrag(ev.rawY - downY)
                    tracker?.computeCurrentVelocity(1000)
                    dragging = false
                    listener?.onDragEnd(if (ev.actionMasked == MotionEvent.ACTION_CANCEL) 0f else tracker?.yVelocity ?: 0f)
                } else if (ev.actionMasked == MotionEvent.ACTION_UP && abs(ev.rawY - downY) < slop && abs(ev.rawX - downX) < slop) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Lets the screen below show through while the watch page is dragged (public API on Android 11+, hidden but allowed before). */
fun Activity.setTranslucentCompat(translucent: Boolean) {
    try {
        if (Build.VERSION.SDK_INT >= 30) { setTranslucent(translucent); return }
        if (!translucent) {
            Activity::class.java.getDeclaredMethod("convertFromTranslucent").apply { isAccessible = true }.invoke(this)
        } else {
            val l = Class.forName("android.app.Activity\$TranslucentConversionListener")
            Activity::class.java.getDeclaredMethod("convertToTranslucent", l, ActivityOptions::class.java)
                .apply { isAccessible = true }.invoke(this, null, null)
        }
    } catch (_: Throwable) { }
}
