package com.bear.ytclassic

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.StreamInfo

class WatchActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ITEM = "item"
        /** Start the page collapsed at this drag progress (1 = mini player) and animate it open. */
        const val EXTRA_EXPAND_FROM = "expand_from"
        /** Path of a downloaded file to play offline. */
        const val EXTRA_FILE = "file"
        private const val MINI_THUMB_DP = 100f
        private const val MINI_BAR_DP = 56f
        private const val NAV_DP = 56f
    }

    private lateinit var item: VideoItem
    private val player get() = Playback.player
    private lateinit var playerView: StyledPlayerView
    private lateinit var playerBox: DragFrame
    private lateinit var errorBox: View
    private lateinit var root: View
    private lateinit var rootBg: android.graphics.drawable.Drawable
    private var progress = 0f
    private var anim: android.animation.ValueAnimator? = null
    private var expanding = false
    private var leaving = false
    private lateinit var playerError: TextView
    private lateinit var playerLoading: View
    private lateinit var btnMinimize: View
    private lateinit var list: RecyclerView
    private val header = WatchHeaderAdapter(this, object : WatchHeaderAdapter.Callbacks {
        override fun openComments() = this@WatchActivity.openComments()
        override fun moreSheet() { this@WatchActivity.moreSheet() }
        override fun share() = shareUrl(item.url)
        override fun rating() = Likes.state(item.url)
        override fun rate(value: Int) = this@WatchActivity.rate(value)
        override fun onComments(title: String?, preview: String?, avatar: String?) = ctl.updateLandComments(title, preview, avatar)
    })
    private val comments: WatchComments = WatchComments(this) { t, p, av -> header.setComments(t, p, av) }
    private lateinit var ctl: WatchControls
    private val related = VideoAdapter(true) { openVideo(it) }
    private var boundInfo: StreamInfo? = null
    private val qualities get() = Playback.qualities
    private val live get() = Playback.live
    private var inPip = false
    private val pipListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePipParams()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updatePipParams()
    }
    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(lightBars = false)
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        setContentView(R.layout.activity_watch)
        Playback.watchActive.value = true
        root = findViewById(R.id.root)
        rootBg = root.background.mutate()
        root.padForSystemBars { !isLandscape() && !inPip }
        playerView = findViewById(R.id.playerView)
        playerBox = findViewById(R.id.playerBox)
        playerError = findViewById(R.id.playerError)
        errorBox = findViewById(R.id.errorBox)
        findViewById<View>(R.id.btnPlayerRetry).setOnClickListener { Playback.retry() }
        playerLoading = findViewById(R.id.playerLoading)
        btnMinimize = findViewById(R.id.btnMinimize)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = ConcatAdapter(header, related)
        setupDrag()

        btnMinimize.setOnClickListener { minimize() }
        ctl = WatchControls(this, playerView, playerBox, object : WatchControls.Host {
            override val inPip get() = this@WatchActivity.inPip
            override val collapsed get() = progress > 0f
            override fun isLandscape() = this@WatchActivity.isLandscape()
            override fun onControlsVisibility() = updateMinimizeButton()
            override fun openComments() = this@WatchActivity.openComments()
            override fun moreSheet() { this@WatchActivity.moreSheet() }
            override fun share() = shareUrl(item.url)
            override fun toggleFullscreen() = this@WatchActivity.toggleFullscreen()
            override fun rating() = Likes.state(item.url)
            override fun rate(value: Int) = this@WatchActivity.rate(value)
        })
        ctl.setup()
        ctl.setupGestures()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLandscape()) runCatching { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } else minimize()
            }
        })
        applyLayout(resources.configuration)
        if (!handleIntent(intent)) { finish(); return }
        val from = intent.getFloatExtra(EXTRA_EXPAND_FROM, -1f)
        if (from > 0f && savedInstanceState == null && !isLandscape()) {
            expanding = true
            playerBox.alpha = 0f
            root.post { playerBox.alpha = 1f; setProgress(from); animateTo(0f) { expanding = false; setTranslucentCompat(false) } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Playback.state.collect { render(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Video from the intent, or (from the notification) the one already playing. */
    private fun handleIntent(i: Intent): Boolean {
        @Suppress("DEPRECATION")
        val v = i.getSerializableExtra(EXTRA_ITEM) as? VideoItem ?: Playback.state.value.item ?: return false
        item = v
        val file = i.getStringExtra(EXTRA_FILE)?.let { java.io.File(it) }
        if (file != null) {
            if (Playback.state.value.item?.url != v.url || !Playback.offline) {
                boundInfo = null; comments.reset()
                header.reset(); header.bindItem(v); header.setComments(null, null, null)
                related.set(emptyList())
                Playback.openFile(v, file)
            }
        } else if (Playback.state.value.item?.url == v.url) {
            // page re-created for the video already playing (e.g. opened from the mini player): show its details now
            if (boundInfo == null) Playback.state.value.let { st -> if (st.info != null) { boundInfo = st.info; header.bindInfo(st.info); related.set(st.info.relatedItems.videosOnly()) } else header.bindItem(st.item ?: v) }
        } else if (Playback.state.value.item?.url != v.url) {
            boundInfo = null
            header.reset()
            header.bindItem(v)
            related.set(emptyList())
            Playback.open(v)
        }
        list.scrollToPosition(0)
        return true
    }

    private fun render(st: Playback.State) {
        val it = st.item ?: run { if (!isFinishing) finish(); return }
        item = it
        if (st.info !== boundInfo) {
            boundInfo = st.info
            if (st.info != null) {
                header.bindInfo(st.info)
                related.set(st.info.relatedItems.videosOnly())
                related.refreshProgress()
            } else header.bindItem(it)
        }
        playerLoading.visibility = if (st.loading) View.VISIBLE else View.GONE
        errorBox.visibility = if (st.error != null) View.VISIBLE else View.GONE
        playerError.text = st.error
        stateBlocking = st.loading || st.error != null
        updateMinimizeButton()
        updatePipParams()
        st.info?.let { comments.load(it.url) }
        ctl.bindInfo(st.info)
        ctl.renderItem(it)
    }


    /** 👍 / 👎 on this phone: liked videos are listed on the คุณ tab. */
    private fun rate(value: Int) {
        val v = Playback.state.value.item?.takeIf { it.url == item.url } ?: item
        val now = Likes.toggle(v, value)
        val msg = when {
            now == Likes.LIKE -> "เพิ่มในวิดีโอที่ชอบแล้ว"
            now == Likes.DISLIKE -> "คุณไม่ชอบวิดีโอนี้"
            value == Likes.LIKE -> "นำออกจากวิดีโอที่ชอบแล้ว"
            else -> "ยกเลิกการไม่ชอบแล้ว"
        }
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        header.notifyItemChanged(0); ctl.updateRating()
    }

    private fun openComments() = comments.open(boundInfo?.url, playerBox.bottom + root.paddingTop)

    private var stateBlocking = false
    /** The ˅ button shows with the controls, and always while loading / showing an error (so the page can still be minimized). */
    private fun updateMinimizeButton() {
        btnMinimize.visibility = if ((ctl.shown || stateBlocking) && !isLandscape() && !inPip) View.VISIBLE else View.GONE
    }

    // ---- drag to minimize ----
    private fun dp(v: Float) = v * resources.displayMetrics.density
    /** Top of the mini bar on the screen below (root coordinates): measured by [MiniTarget], else home's spot above the bottom nav. */
    private fun barTop(): Float {
        val gap = MiniTarget.gapFromBottomPx
        return if (gap > 0) root.height - gap.toFloat() else root.height - root.paddingBottom - dp(MINI_BAR_DP + NAV_DP + 1f)
    }
    /** Vertical distance the video travels from its place at the top to the mini bar. */
    private fun range(): Float = (barTop() - playerBox.top).coerceAtLeast(dp(100f))

    /**
     * 0 = full watch page, 1 = only the video left, shrunk to the mini bar's 100dp thumbnail (3.0.1 style).
     * The black background and the details fade with the drag, so the previous screen shows through gradually.
     */
    private fun setProgress(p: Float) {
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
        ctl.seek.alpha = if (progress > 0f) 0f else 1f
        list.alpha = 1f - progress
        list.translationY = progress * dp(160f)
        rootBg.alpha = ((1f - progress) * 255).toInt()
    }

    private fun animateTo(target: Float, end: () -> Unit) {
        anim?.cancel()
        anim = android.animation.ValueAnimator.ofFloat(progress, target).apply {
            duration = (220 + 160 * kotlin.math.abs(target - progress)).toLong()
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    private fun canDrag() = !isLandscape() && !inPip && !leaving

    private fun setupDrag() {
        playerBox.canDrag = { canDrag() }
        playerBox.listener = object : DragFrame.Listener {
            override fun onDragStart() { anim?.cancel(); beginCollapse() }
            override fun onDrag(dy: Float) = setProgress(dy / range())
            override fun onDragEnd(velocityY: Float) {
                if (progress > 0.5f || velocityY > dp(600f)) animateTo(1f) { finishToMini() }
                else animateTo(0f) { setTranslucentCompat(false) }
            }
        }
    }

    private fun beginCollapse() {
        ctl.show(false, animate = false)
        setTranslucentCompat(true)
    }

    /** ˅ button / back: animate down into the mini player. */
    private fun minimize() {
        if (leaving) return
        if (!canDrag() || root.height == 0) { finishToMini(); return }
        beginCollapse()
        animateTo(1f) { finishToMini() }
    }

    private fun finishToMini() {
        if (leaving) return
        leaving = true
        Playback.watchActive.value = false
        if (isTaskRoot) startActivityNoTransition(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun finish() {
        super.finish()
        clearActivityTransition()
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        if (!expanding && progress == 0f) setTranslucentCompat(false)
    }

    override fun onStart() {
        super.onStart()
        playerView.player = player
        player.addListener(pipListener)
        ctl.attach()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            && !Prefs.p.getBoolean("asked_notif", false)) {
            Prefs.p.edit().putBoolean("asked_notif", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (inPip) Playback.pip.value = false
        if (isFinishing) Playback.watchActive.value = false
        ctl.release()
    }

    override fun onStop() {
        super.onStop()
        player.removeListener(pipListener)
        ctl.detach()
        playerView.player = null
    }

    /** Take the video surface back (the mini bar may have grabbed it). */
    private fun reattach() { playerView.player = null; playerView.player = player }

    override fun onResume() {
        super.onResume(); reattach(); internalNav = false; updatePipParams(); header.notifyItemChanged(0)
        // the controller's time label can stay at 00:00 after re-attaching while paused: nudge it with a no-op seek
        playerView.postDelayed({
            if (!player.isPlaying && player.playbackState == Player.STATE_READY) player.seekTo(player.currentPosition)
        }, 350)
    }

    /** Opening another screen of this app (channel, share sheet...) must not turn the video into a popup. */
    private var internalNav = false
    override fun startActivity(intent: Intent, options: Bundle?) {
        internalNav = true
        if (Build.VERSION.SDK_INT >= 31) runCatching { setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
        val opts = options ?: android.app.ActivityOptions.makeCustomAnimation(this, R.anim.no_anim, R.anim.no_anim).toBundle()
        super.startActivity(intent, opts)
        clearActivityTransition()
    }

    // ---- popup player (picture-in-picture) ----
    /** Playing or about to play (buffering counts: slow networks buffer a lot). */
    private fun wantsPlay() = player.playWhenReady && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)

    private fun pipAllowed() = Build.VERSION.SDK_INT >= 26 && Prefs.pip &&
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(pipAllowed() && !internalNav && wantsPlay()).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= 26 && pipAllowed()) runCatching { setPictureInPictureParams(pipParams()!!) }
        else if (Build.VERSION.SDK_INT >= 31) runCatching { setPictureInPictureParams(pipParams()!!) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30 && !internalNav && pipAllowed() && wantsPlay()) {
            runCatching { enterPictureInPictureMode(pipParams()!!) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        Playback.pip.value = inPip
        if (inPip) reattach() else playerView.postDelayed({ if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) reattach() }, 400)
        if (!inPip && lifecycle.currentState == Lifecycle.State.CREATED) {
            // the popup was closed with its X: stop playback and leave
            player.pause(); finish(); return
        }
        if (inPip) ctl.show(false, animate = false)
        list.visibility = if (inPip) View.GONE else View.VISIBLE
        applyLayout(newConfig)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout(newConfig)
    }

    private fun applyLayout(c: Configuration) {
        val land = c.orientation == Configuration.ORIENTATION_LANDSCAPE
        findViewById<View>(R.id.root).let { androidx.core.view.ViewCompat.requestApplyInsets(it) }
        val lp = playerBox.layoutParams
        val ph = resources.displayMetrics.widthPixels * 9 / 16
        lp.height = if (land || inPip) ViewGroup.LayoutParams.MATCH_PARENT else ph
        playerBox.layoutParams = lp
        list.visibility = if (land || inPip) View.GONE else View.VISIBLE
        (list.layoutParams as FrameLayout.LayoutParams).let { it.topMargin = ph; list.layoutParams = it }
        ctl.layout(land)
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (land) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else ctl.show(WindowInsetsCompat.Type.systemBars())
    }


    private fun toggleFullscreen() {
        runCatching {
            requestedOrientation = if (isLandscape()) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun moreSheet() = videoActionSheet(item) {
        Downloads.start(this, item, boundInfo?.takeIf { it.url == item.url || Playback.state.value.item?.url == item.url })
    }

}
