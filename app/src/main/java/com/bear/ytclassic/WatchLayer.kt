package com.bear.ytclassic

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import android.view.LayoutInflater
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.lang.ref.WeakReference

/**
 * Full watch page as an overlay on the current screen. Swipe-down / back shrink it into
 * the mini bar without starting or finishing any Activity (the v1 slide / flicker cause).
 */
class WatchLayer private constructor(private val a: AppCompatActivity, private val root: View) : DefaultLifecycleObserver {
    companion object {
        private const val TAG = "watch_layer"
        private val layers = ArrayList<WeakReference<WatchLayer>>()
        private var last: WeakReference<AppCompatActivity>? = null

        fun attach(a: AppCompatActivity): WatchLayer {
            last = WeakReference(a)
            existing(a)?.let { return it }
            val content = a.findViewById<ViewGroup>(android.R.id.content)
            val root = LayoutInflater.from(a).inflate(R.layout.activity_watch, content, false)
            root.tag = TAG
            root.visibility = View.GONE
            root.isClickable = true
            root.elevation = 40 * a.resources.displayMetrics.density
            content.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return WatchLayer(a, root).also { layer ->
                layer.setup()
                layers.add(WeakReference(layer))
            }
        }

        fun of(a: AppCompatActivity): WatchLayer? = existing(a)

        fun consumeBack(a: AppCompatActivity): Boolean = existing(a)?.onBack() == true





        private fun existing(a: AppCompatActivity): WatchLayer? =
            layers.mapNotNull { it.get() }.firstOrNull { it.a === a && it.root.parent != null }

        fun lastAlive(): AppCompatActivity? {
            val host = last?.get()
            return host?.takeIf { !it.isFinishing && !it.isDestroyed }
        }

        private fun hideOthers(keep: WatchLayer) {
            layers.mapNotNull { it.get() }.filter { it !== keep }.forEach { it.hide(animate = false) }
        }
    }

    private lateinit var item: VideoItem
    private val player get() = Playback.player
    private val playerView: StyledPlayerView = root.findViewById(R.id.playerView)
    private val playerBox: DragFrame = root.findViewById(R.id.playerBox)
    private val errorBox: View = root.findViewById(R.id.errorBox)
    private val rootBg = root.background.mutate()
    private val playerError: TextView = root.findViewById(R.id.playerError)
    private val playerLoading: View = root.findViewById(R.id.playerLoading)
    private val btnMinimize: View = root.findViewById(R.id.btnMinimize)
    private val list: RecyclerView = root.findViewById(R.id.list)
    private val header: WatchHeaderAdapter = WatchHeaderAdapter(a, WatchHeaderBridge(
        onMore = { moreSheet() },
        onComments = { openComments() },
        onShare = { a.shareUrl(item.url) },
        onSave = { a.saveToPlaylist(item) },
        currentRating = { Likes.state(item.url) },
        onRate = { rate(it) },
        onCommentsChanged = { t, p, av -> if (::ctl.isInitialized) ctl.updateLandComments(t, p, av) },
        currentChapter = { if (::ctl.isInitialized) ctl.chapters.currentTitle() else null },
        onChapters = { if (::ctl.isInitialized) ctl.chapters.showSheet() },
        onSeek = { ms -> Playback.player.seekTo(ms); if (::ctl.isInitialized) ctl.updateTime() },
    ))
    private val comments: WatchComments = WatchComments(a) { t, p, av -> header.setComments(t, p, av) }
    private lateinit var ctl: WatchControls
    private lateinit var shrink: WatchMinimize
    private val related = VideoAdapter(true) { open(it, null, -1f) }
    private var boundInfo: StreamInfo? = null
    private var inPip = false
    private var internalNav = false
    private var stateBlocking = false
    private var expanded = false
    private var autoplayBound: Autoplay.Offer? = null
    private val pipListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePipParams()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updatePipParams()
    }
    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { onBack() }
    }
    private fun isLandscape() = a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private fun isExpanded() = expanded && root.visibility == View.VISIBLE

    private fun setup() {
        if (Build.VERSION.SDK_INT >= 28) a.window.attributes = a.window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        root.padForSystemBars { !isLandscape() && !inPip }
        list.layoutManager = LinearLayoutManager(a)
        list.adapter = ConcatAdapter(header, related)
        root.findViewById<View>(R.id.btnPlayerRetry).setOnClickListener { Playback.retry() }
        shrink = WatchMinimize(
            root, rootBg, playerBox, list, a.resources.displayMetrics.density,
            hideControls = { ctl.show(false, animate = false) },
            setSeekAlpha = { ctl.seek.alpha = it },
            isLandscape = { isLandscape() },
            inPip = { inPip },
            onFinished = { hide(animate = false) },
        )
        shrink.setup()
        btnMinimize.setOnClickListener { shrink.minimize() }
        ctl = WatchControls(a, root, playerView, playerBox, object : WatchControls.Host {
            override val inPip get() = this@WatchLayer.inPip
            override val collapsed get() = shrink.progress > 0f
            override fun isLandscape() = this@WatchLayer.isLandscape()
            override fun onControlsVisibility() = updateMinimizeButton()
            override fun openComments() = this@WatchLayer.openComments()
            override fun moreSheet() { this@WatchLayer.moreSheet() }
            override fun share() = a.shareUrl(item.url)
            override fun toggleFullscreen() = this@WatchLayer.toggleFullscreen()
            override fun rating() = Likes.state(item.url)
            override fun rate(value: Int) = this@WatchLayer.rate(value)
        })
        ctl.setup()
        ctl.chapters.onChange = { header.chapterChanged() }
        ctl.setupGestures()
        a.onBackPressedDispatcher.addCallback(a, backCb)
        a.lifecycle.addObserver(this)
        setupAutoplayCard()
        a.lifecycleScope.launch {
            a.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Playback.state.collect { render(it) }
            }
        }
        applyLayout(a.resources.configuration)
    }

    fun open(item: VideoItem?, file: File?, expandFrom: Float) {
        val opened = WatchOpen.planOpen(item, file) ?: return
        hideOthers(this)
        val v = opened.first
        val kind = opened.second
        this.item = v
        when (kind) {
            WatchViewModel.OpenKind.NewFile, WatchViewModel.OpenKind.NewStream -> {
                boundInfo = null; comments.reset()
                header.reset(); header.bindItem(v); header.setComments(null, null, null)
                related.set(emptyList())
            }
            WatchViewModel.OpenKind.Reuse -> {
                if (boundInfo == null) Playback.state.value.let { st ->
                    if (st.info != null) { boundInfo = st.info; header.bindInfo(st.info); related.set(st.info.relatedItems.videosOnly()) }
                    else header.bindItem(st.item ?: v)
                }
            }
            WatchViewModel.OpenKind.Unchanged -> {}
        }
        WatchOpen.applyOpen(v, file, kind)
        list.scrollToPosition(0)
        show(expandFrom)
    }

    private fun show(expandFrom: Float) {
        expanded = true
        backCb.isEnabled = true
        Playback.watchActive.value = true
        root.visibility = View.VISIBLE
        root.bringToFront()
        shrink.leaving = false
        reattach()
        applyLayout(a.resources.configuration)
        if (expandFrom > 0f && !isLandscape()) shrink.expandFrom(expandFrom) {} else shrink.setProgress(0f)
        updatePipParams()
    }

    private fun hide(animate: Boolean) {
        if (animate && isExpanded() && !isLandscape() && !inPip) { shrink.minimize(); return }
        dismissCommentsSheet()
        shrink.leaving = false
        shrink.setProgress(0f)
        expanded = false
        backCb.isEnabled = false
        root.visibility = View.GONE
        playerView.player = null
        if (!inPip) Playback.watchActive.value = false
        runCatching { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        updatePipParams()
    }

    fun onBack(): Boolean {
        if (!isExpanded()) return false
        if (isLandscape()) { runCatching { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }; return true }
        shrink.minimize()
        return true
    }

    private fun render(st: Playback.State) {
        if (!isExpanded() && !inPip) return
        val it = st.item ?: run { hide(animate = false); return }
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

    private fun rate(value: Int) {
        android.widget.Toast.makeText(a, WatchViewModel().rate(item, value), android.widget.Toast.LENGTH_SHORT).show()
        header.notifyItemChanged(0); ctl.updateRating()
    }

    private fun openComments() = comments.open(boundInfo?.url, playerBox.bottom + root.paddingTop)
    private fun moreSheet() = a.videoActionSheet(item) { Downloads.start(a, item, Playback.state.value.info?.takeIf { it.url == item.url }) }
    private fun updateMinimizeButton() {
        btnMinimize.visibility = if ((ctl.shown || stateBlocking) && !isLandscape() && !inPip) View.VISIBLE else View.GONE
    }

    private fun toggleFullscreen() {
        runCatching { a.requestedOrientation = if (isLandscape()) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
    }

    private fun wantsPlay() = player.playWhenReady && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
    private fun pipAllowed() = Build.VERSION.SDK_INT >= 26 && Prefs.pip &&
        a.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(pipAllowed() && isExpanded() && !internalNav && wantsPlay()).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun updatePipParams() { pipParams()?.let { params -> runCatching { a.setPictureInPictureParams(params) } } }

    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT in 26..30 && isExpanded() && !internalNav && pipAllowed() && wantsPlay()) {
            pipParams()?.let { params -> runCatching { a.enterPictureInPictureMode(params) } }
        }
    }

    fun onPipChanged(inMode: Boolean, config: Configuration) {
        inPip = inMode
        Playback.pip.value = inPip
        if (inPip) reattach() else playerView.postDelayed({ if (a.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) reattach() }, 400)
        if (!inPip && a.lifecycle.currentState == Lifecycle.State.CREATED) {
            player.pause(); hide(animate = false); return
        }
        if (inPip) { ctl.show(false, animate = false); root.findViewById<View>(R.id.autoplayCard).visibility = View.GONE }
        list.visibility = if (inPip) View.GONE else View.VISIBLE
        applyLayout(config)
    }

    fun onConfig(c: Configuration) { if (isExpanded() || inPip) applyLayout(c) }

    fun onLeavingForOtherScreen() {
        internalNav = true
        if (!inPip) Playback.watchActive.value = false
        if (Build.VERSION.SDK_INT >= 31) runCatching { a.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
    }

    private fun applyLayout(c: Configuration) {
        val land = c.orientation == Configuration.ORIENTATION_LANDSCAPE
        androidx.core.view.ViewCompat.requestApplyInsets(root)
        val lp = playerBox.layoutParams
        val ph = a.resources.displayMetrics.widthPixels * 9 / 16
        lp.height = if (land || inPip) ViewGroup.LayoutParams.MATCH_PARENT else ph
        playerBox.layoutParams = lp
        list.visibility = if (land || inPip) View.GONE else View.VISIBLE
        (list.layoutParams as FrameLayout.LayoutParams).let { it.topMargin = ph; list.layoutParams = it }
        ctl.layout(land)
        val bars = WindowCompat.getInsetsController(a.window, a.window.decorView)
        if (land && isExpanded()) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else if (!inPip) bars.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun reattach() { playerView.player = null; playerView.player = player }

    override fun onStart(owner: LifecycleOwner) {
        if (!isExpanded() && !inPip) return
        playerView.player = player
        player.addListener(pipListener)
        ctl.attach()
        if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            && !Prefs.p.getBoolean("asked_notif", false)) {
            Prefs.p.edit().putBoolean("asked_notif", true).apply()
            a.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        last = WeakReference(a)
        if (!isExpanded() && !inPip) return
        if (!inPip) Playback.watchActive.value = true
        reattach(); internalNav = false; updatePipParams(); header.notifyItemChanged(0)
        playerView.postDelayed({
            if (!player.isPlaying && player.playbackState == Player.STATE_READY) player.seekTo(player.currentPosition)
        }, 350)
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!isExpanded() && !inPip) return
        player.removeListener(pipListener)
        ctl.detach()
        playerView.player = null
        if (!a.isFinishing && !inPip && !Playback.pip.value) Playback.watchActive.value = false
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (inPip) Playback.pip.value = false
        if (a.isFinishing && isExpanded()) { expanded = false; Playback.watchActive.value = false }
        if (::ctl.isInitialized) ctl.release()
        layers.removeAll { it.get() == null || it.get() === this }
    }

    private val autoplayTick = object : Runnable {
        override fun run() {
            val card = root.findViewById<View>(R.id.autoplayCard)
            val offer = Playback.autoplayOffer.value
            if (offer == null || inPip || !isExpanded()) { card.visibility = View.GONE; return }
            val sec = ((offer.remainingMs(System.currentTimeMillis()) + 999) / 1000).toInt().coerceAtLeast(1)
            root.findViewById<TextView>(R.id.autoplayLabel).text = "เล่นต่อไปใน $sec วินาที"
            card.postDelayed(this, 200)
        }
    }

    private fun setupAutoplayCard() {
        val card = root.findViewById<View>(R.id.autoplayCard)
        card.setOnClickListener { Playback.confirmAutoplay() }
        root.findViewById<View>(R.id.autoplayCancel).setOnClickListener { Playback.cancelAutoplay() }
        a.lifecycleScope.launch {
            a.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Playback.autoplayOffer.collect { offer ->
                    card.removeCallbacks(autoplayTick)
                    autoplayBound = offer
                    if (offer == null || inPip || !isExpanded()) { card.visibility = View.GONE; return@collect }
                    root.findViewById<TextView>(R.id.autoplayTitle).text = offer.next.title
                    val thumb = root.findViewById<android.widget.ImageView>(R.id.autoplayThumb)
                    if (thumb.tag != offer.next.thumb) { thumb.tag = offer.next.thumb; thumb.load(offer.next.thumb) }
                    card.visibility = View.VISIBLE
                    autoplayTick.run()
                }
            }
        }
    }
}
