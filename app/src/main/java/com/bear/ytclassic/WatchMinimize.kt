package com.bear.ytclassic

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView

/**
 * Drag-to-minimize on the watch page: the video shrinks onto the mini bar while the
 * black background and details fade, so the previous screen shows through (3.0.1 style).
 * Behavior is unchanged; this used to live in WatchActivity.
 */
class WatchMinimize(
    private val root: View,
    private val rootBg: Drawable,
    private val playerBox: DragFrame,
    private val list: RecyclerView,
    private val density: Float,
    private val hideControls: () -> Unit,
    private val setSeekAlpha: (Float) -> Unit,
    private val isLandscape: () -> Boolean,
    private val inPip: () -> Boolean,
    private val onFinished: () -> Unit,
) {
    companion object {
        private const val MINI_THUMB_DP = 100f
        private const val MINI_BAR_DP = 56f
        private const val NAV_DP = 56f
    }

    var progress = 0f
        private set
    var expanding = false
    var leaving = false
    private var anim: ValueAnimator? = null

    fun canDrag() = !isLandscape() && !inPip() && !leaving

    fun setup() {
        playerBox.canDrag = { canDrag() }
        playerBox.listener = object : DragFrame.Listener {
            override fun onDragStart() { anim?.cancel(); beginCollapse() }
            override fun onDrag(dy: Float) = setProgress(dy / range())
            override fun onDragEnd(velocityY: Float) {
                if (progress > 0.5f || velocityY > dp(600f)) animateTo(1f) { onFinished() }
                else animateTo(0f) { activity()?.setTranslucentCompat(false) }
            }
        }
    }

    /** ˅ button / back: animate down into the mini player. */
    fun minimize() {
        if (leaving) return
        if (!canDrag() || root.height == 0) { onFinished(); return }
        beginCollapse()
        animateTo(1f) { onFinished() }
    }

    /** Start collapsed at [from] (1 = mini bar) and grow to the full watch page. */
    fun expandFrom(from: Float, end: () -> Unit) {
        expanding = true
        playerBox.alpha = 0f
        root.post {
            playerBox.alpha = 1f
            setProgress(from)
            animateTo(0f) { expanding = false; end() }
        }
    }

    private fun dp(v: Float) = v * density
    private fun activity() = root.context as? Activity

    /** Top of the mini bar on the screen below (root coordinates): measured by [MiniTarget], else home's spot above the bottom nav. */
    private fun barTop(): Float {
        val gap = MiniTarget.gapFromBottomPx
        return if (gap > 0) root.height - gap.toFloat() else root.height - root.paddingBottom - dp(MINI_BAR_DP + NAV_DP + 1f)
    }

    /** Vertical distance the video travels from its place at the top to the mini bar. */
    private fun range(): Float = (barTop() - playerBox.top).coerceAtLeast(dp(100f))

    /**
     * 0 = full watch page, 1 = only the video left, shrunk to the mini bar's 100dp thumbnail.
     * The black background and the details fade with the drag.
     */
    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        val w = playerBox.width.toFloat().coerceAtLeast(1f)
        val sc = 1f - progress * (1f - dp(MINI_THUMB_DP) / w)
        playerBox.pivotX = 0f; playerBox.pivotY = 0f
        playerBox.scaleX = sc; playerBox.scaleY = sc
        playerBox.translationX = 0f
        playerBox.translationY = progress * range()
        // clip only while shrinking: at rest the scrubber knob may overhang the video's bottom edge
        playerBox.clipToOutline = progress > 0f
        playerBox.invalidateOutline()
        setSeekAlpha(if (progress > 0f) 0f else 1f)
        list.alpha = 1f - progress
        list.translationY = progress * dp(160f)
        rootBg.alpha = ((1f - progress) * 255).toInt()
    }

    fun animateTo(target: Float, end: () -> Unit) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(progress, target).apply {
            duration = (220 + 160 * kotlin.math.abs(target - progress)).toLong()
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    private fun beginCollapse() {
        hideControls()
        activity()?.setTranslucentCompat(true)
    }
}
