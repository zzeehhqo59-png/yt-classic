package com.bear.ytclassic

import android.animation.ValueAnimator
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.load
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * The 3.0.1-style mini player bar (layout `view_mini_bar`), usable on any screen that includes it:
 * tap or swipe up to open the watch page, swipe sideways to close, play/pause and close buttons, 2dp progress line.
 */
class MiniPlayerBar private constructor(private val a: AppCompatActivity) : DefaultLifecycleObserver {
    private val bar: DragFrame = a.findViewById(R.id.miniBar)
    private val player: StyledPlayerView = a.findViewById(R.id.miniPlayer)
    private val progress: ProgressBar = a.findViewById(R.id.miniProgress)
    private val play: ImageButton = a.findViewById(R.id.miniPlay)
    private val density = a.resources.displayMetrics.density
    private var anim: ValueAnimator? = null

    private val ticker = object : Runnable {
        override fun run() {
            val p = Playback.player
            if (p.duration > 0) progress.progress = (p.currentPosition * 1000 / p.duration).toInt()
            progress.postDelayed(this, TICK_MS)
        }
    }
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayIcon()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updatePlayIcon()
    }

    companion object {
        private const val TICK_MS = 500L
        /** Share of the way up past which a released swipe opens the watch page (below it the bar snaps back). */
        private const val OPEN_AT = 0.5f

        /** Wire the bar of [a]'s layout to the shared player. Call from onCreate after setContentView. */
        fun attach(a: AppCompatActivity): MiniPlayerBar = MiniPlayerBar(a).also { it.setup() }
    }

    private fun allowed() = !Playback.pip.value && !Playback.watchActive.value && Playback.state.value.item != null

    private fun setup() {
        bar.setOnClickListener { Playback.state.value.item?.let { a.openVideo(it, expandFrom = 1f) } }
        play.setOnClickListener {
            val p = Playback.player
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.playWhenReady = !p.playWhenReady
        }
        a.findViewById<View>(R.id.miniClose).setOnClickListener { Playback.close() }
        setupDrag()
        a.lifecycle.addObserver(this)
        a.lifecycleScope.launch {
            a.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(Playback.state, Playback.pip, Playback.watchActive) { st, pip, watch -> Triple(st, pip, watch) }.collect { (st, pip, watch) ->
                    val item = st.item
                    if (pip || watch || item == null) { bar.visibility = View.GONE; player.player = null }
                    else {
                        anim?.cancel(); reset()
                        if (player.player == null) player.player = Playback.player
                        bar.visibility = View.VISIBLE
                        bar.post { MiniTarget.remember(bar) }
                    }
                    if (item != null) {
                        bar.contentDescription = item.title
                        a.findViewById<TextView>(R.id.miniTitle).text = item.title
                        a.findViewById<TextView>(R.id.miniChannel).text = st.error?.lineSequence()?.firstOrNull() ?: item.channel
                        val thumb = a.findViewById<ImageView>(R.id.miniThumb)
                        if (thumb.tag != item.thumb) { thumb.tag = item.thumb; thumb.load(item.thumb) }
                    }
                }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (allowed()) player.player = Playback.player
        Playback.player.addListener(listener)
        updatePlayIcon()
        progress.removeCallbacks(ticker); progress.post(ticker)
    }

    override fun onResume(owner: LifecycleOwner) {
        // take the video surface back from the watch page that just closed
        if (allowed()) { player.player = null; player.player = Playback.player }
        bar.post { MiniTarget.remember(bar) }
    }

    override fun onStop(owner: LifecycleOwner) {
        progress.removeCallbacks(ticker)
        Playback.player.removeListener(listener)
        player.player = null
    }

    private fun updatePlayIcon() { play.setImageResource(if (Playback.player.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play) }

    private fun reset() { bar.translationX = 0f; bar.translationY = 0f; bar.alpha = 1f }

    private fun animate(from: Float, to: Float, update: (Float) -> Unit, end: () -> Unit = {}) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(from, to).apply {
            duration = 220
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { update(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(an: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(an: android.animation.Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    /** Swipe up follows the finger and opens the watch page past half way (it continues from there); sideways closes. */
    private fun setupDrag() {
        bar.direction = -1
        bar.horizontal = true
        fun range() = bar.top.toFloat().coerceAtLeast(200 * density)
        fun lift(t: Float) { bar.translationY = t; bar.alpha = 1f - 0.4f * (-t / range()) }
        bar.listener = object : DragFrame.Listener {
            override fun onDragStart() { anim?.cancel() }
            override fun onDrag(dy: Float) = lift(dy.coerceIn(-range(), 0f))
            override fun onDragEnd(velocityY: Float) {
                val f = -bar.translationY / range()
                val item = Playback.state.value.item
                if (item != null && (f > OPEN_AT || velocityY < -600 * density)) a.openVideo(item, expandFrom = (1f - f).coerceIn(0.02f, 1f))
                else animate(bar.translationY, 0f, { lift(it) })
            }
            override fun onDragX(dx: Float) {
                anim?.cancel()
                bar.translationX = dx
                bar.alpha = (1f - abs(dx) / (bar.width * 1.1f)).coerceIn(0.1f, 1f)
            }
            override fun onDragXEnd(velocityX: Float) {
                val dx = bar.translationX
                val w = bar.width.toFloat()
                if (abs(dx) > w * 0.35f || abs(velocityX) > 800 * density) {
                    val s = if (dx != 0f) sign(dx) else sign(velocityX)
                    animate(dx, s * w, { bar.translationX = it; bar.alpha = (1f - abs(it) / (w * 1.1f)).coerceIn(0f, 1f) }) { Playback.close() }
                } else animate(dx, 0f, { bar.translationX = it; bar.alpha = (1f - abs(it) / (w * 1.1f)).coerceIn(0.1f, 1f) })
            }
        }
    }
}

/**
 * Where the mini bar sits on the screen below the watch page, so the shrinking video lands on it.
 * Stored as the distance from the bar's top to the bottom of the screen (home: above the bottom nav; other screens: at the bottom).
 */
object MiniTarget {
    @Volatile var gapFromBottomPx = -1
        private set

    /** Works while the bar is hidden too: its slot is the bottom of its parent minus whatever sits below it (home's nav). */
    fun remember(bar: View) {
        val parent = bar.parent as? android.view.ViewGroup ?: return
        if (parent.height == 0) return
        val idx = parent.indexOfChild(bar)
        var below = 0
        for (i in idx + 1 until parent.childCount) parent.getChildAt(i).takeIf { it.visibility != View.GONE }?.let { below += it.height }
        val loc = IntArray(2); parent.getLocationOnScreen(loc)
        val slotBottom = loc[1] + parent.height - parent.paddingBottom - below
        val barH = bar.layoutParams.height.takeIf { it > 0 } ?: return
        val screenH = bar.rootView.height
        if (screenH > 0) gapFromBottomPx = screenH - (slotBottom - barH)
    }
}
