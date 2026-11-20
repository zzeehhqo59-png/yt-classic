package com.bear.ytclassic

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.Rational
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import coil3.load
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
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
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

class WatchActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ITEM = "item"
        /** Start the page collapsed at this drag progress (1 = mini player) and animate it open. */
        const val EXTRA_EXPAND_FROM = "expand_from"
        /** Path of a downloaded file to play offline. */
        const val EXTRA_FILE = "file"
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
    private var comments: org.schabi.newpipe.extractor.comments.CommentsInfo? = null
    private var commentsFor: String? = null
    private lateinit var playerError: TextView
    private lateinit var playerLoading: View
    private lateinit var btnMinimize: View
    private lateinit var list: RecyclerView
    private val header = HeaderAdapter()
    private val related = VideoAdapter(true) { openVideo(it) }
    private var boundInfo: StreamInfo? = null
    private val qualities get() = Playback.qualities
    private val live get() = Playback.live
    private lateinit var seekLeft: TextView
    private lateinit var seekRight: TextView
    private var seekSide = 0
    private var seekAccum = 0
    private var lastSeekAt = 0L
    private var inPip = false
    private val hideSeek = Runnable {
        seekLeft.animate().alpha(0f).setDuration(250).start()
        seekRight.animate().alpha(0f).setDuration(250).start()
        seekAccum = 0; seekSide = 0
    }
    // ---- overlay controls ----
    private lateinit var controls: View
    private lateinit var seek: YtSeekBar
    private lateinit var btnPlay: ImageButton
    private lateinit var btnPrev: View
    private lateinit var btnNext: View
    private lateinit var btnCC: ImageButton
    private lateinit var btnFull: ImageButton
    private lateinit var btnAutoplay: AutoplaySwitch
    private lateinit var timePill: TextView
    private lateinit var previewBox: View
    private lateinit var previewImg: ImageView
    private lateinit var previewPill: TextView
    private lateinit var centerRow: View
    private var storyboard: Storyboard? = null
    private var segments: List<org.schabi.newpipe.extractor.stream.StreamSegment> = emptyList()
    private val hideCtl = Runnable { showControls(false) }
    private val ticker = object : Runnable {
        override fun run() { updateTime(); playerView.postDelayed(this, 500) }
    }
    private val ctlListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { updatePlayButton(); scheduleHide() }
        override fun onPlaybackStateChanged(state: Int) { updatePlayButton(); updateTime() }
        override fun onTracksChanged(tracks: com.google.android.exoplayer2.Tracks) = updateCC()
    }
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
        seekLeft = findViewById(R.id.seekLeft)
        seekRight = findViewById(R.id.seekRight)
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
        setupControls()
        setupGestures()
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
                boundInfo = null; commentsFor = null
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
        st.info?.let { loadComments(it.url) }
        if (st.info !== sbFor) { sbFor = st.info; storyboard?.release(); storyboard = Storyboard.of(st.info); segments = st.info?.streamSegments.orEmpty().sortedBy { s -> s.startTimeSeconds } }
        findViewById<TextView>(R.id.landTitle).text = it.title
        findViewById<TextView>(R.id.landChannel).text = it.channel
        val nxt = Playback.nextItem()
        btnNext.alpha = if (nxt != null) 1f else 0.4f
        findViewById<View>(R.id.landNext).visibility = if (nxt != null) View.VISIBLE else View.INVISIBLE
        nxt?.thumb?.let { u -> findViewById<ImageView>(R.id.landNextThumb).load(u) }
        updatePlayButton(); updateTime()
    }

    private fun loadComments(url: String) {
        if (commentsFor == url) return
        commentsFor = url; comments = null
        header.setComments(null, null, null)
        lifecycleScope.launch {
            val ci = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                org.schabi.newpipe.extractor.comments.CommentsInfo.getInfo(org.schabi.newpipe.extractor.ServiceList.YouTube, url)
            } }.getOrNull()
            if (commentsFor != url) return@launch
            comments = ci
            when {
                ci == null -> header.setComments("ความคิดเห็น", "โหลดความคิดเห็นไม่ได้ แตะเพื่อลองอีกครั้ง", null)
                ci.isLiveChat -> header.setComments(null, null, null)
                ci.isCommentsDisabled -> header.setComments("ความคิดเห็น", "ปิดการแสดงความคิดเห็นอยู่", null)
                else -> {
                    val first = ci.relatedItems.firstOrNull()
                    val n = if (ci.commentsCount >= 0) " " + Fmt.shortNum(ci.commentsCount.toLong()) else ""
                    header.setComments("ความคิดเห็น$n", first?.let { commentPlain(it) } ?: "ยังไม่มีความคิดเห็น", first?.uploaderAvatars?.pick(48))
                }
            }
        }
    }

    private fun openComments() {
        val ci = comments
        if (ci == null) { commentsFor = null; boundInfo?.let { loadComments(it.url) }; return }
        showCommentsSheet(this, ci, playerBox.bottom + root.paddingTop)
    }

    private var sbFor: StreamInfo? = null
    private var controllerShown = false
    private var stateBlocking = false
    /** The ˅ button shows with the controls, and always while loading / showing an error (so the page can still be minimized). */
    private fun updateMinimizeButton() {
        btnMinimize.visibility = if ((controllerShown || stateBlocking) && !isLandscape() && !inPip) View.VISIBLE else View.GONE
    }

    // ---- drag to minimize ----
    private fun dp(v: Float) = v * resources.displayMetrics.density
    /** Floating card rect (in root coordinates) the video shrinks into: bottom-right, above the home screen's bottom nav. */
    private fun cardW() = MiniGeom.width(this, playerBox.width.coerceAtLeast(1)).toFloat()
    private fun cardLeft() = playerBox.width - MiniGeom.marginEnd(this) - cardW()
    private fun cardTop() = root.height - root.paddingBottom - MiniGeom.marginBottom(this) - MiniGeom.height(this, playerBox.width.coerceAtLeast(1)).toFloat()
    /** Vertical distance the video travels from its place at the top to the card. */
    private fun range(): Float = (cardTop() - playerBox.top).coerceAtLeast(dp(100f))
    private val playerOutline by lazy { RoundOutline(0f).also { playerBox.outlineProvider = it } }

    /** 0 = full watch page, 1 = only the video left, shrunk into the bottom-right card; details fade, the screen below shows through. */
    private fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        val w = playerBox.width.toFloat().coerceAtLeast(1f)
        val sc = 1f - progress * (1f - cardW() / w)
        playerBox.pivotX = 0f; playerBox.pivotY = 0f
        playerBox.scaleX = sc; playerBox.scaleY = sc
        playerBox.translationX = progress * cardLeft()
        playerBox.translationY = progress * range()
        playerOutline.radius = MiniGeom.radius(this) * progress / sc
        // clip only while shrinking: at rest the scrubber knob may overhang the video's bottom edge
        playerBox.clipToOutline = progress > 0f
        playerBox.invalidateOutline()
        seek.alpha = if (progress > 0f) 0f else 1f
        list.alpha = (1f - progress * 1.8f).coerceIn(0f, 1f)
        list.translationY = progress * dp(120f)
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
        showControls(false, animate = false)
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
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        if (!expanding && progress == 0f) setTranslucentCompat(false)
    }

    override fun onStart() {
        super.onStart()
        playerView.player = player
        player.addListener(pipListener)
        player.addListener(ctlListener)
        playerView.removeCallbacks(ticker); playerView.post(ticker)
        updatePlayButton(); updateCC()
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
        storyboard?.release()
    }

    override fun onStop() {
        super.onStop()
        player.removeListener(pipListener)
        player.removeListener(ctlListener)
        playerView.removeCallbacks(ticker)
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
        super.startActivity(intent, options)
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
        if (inPip) showControls(false, animate = false)
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
        layoutControls(land)
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (land) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else ctl.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun pickQuality() {
        if (qualities.isEmpty()) { Toast.makeText(this, "ยังไม่มีตัวเลือกคุณภาพ", Toast.LENGTH_SHORT).show(); return }
        val labels = qualities.map { it.label }.reversed()
        choiceSheet(getString(R.string.choose_quality), labels, qualities.size - 1 - Playback.current) { which ->
            val idx = qualities.size - 1 - which
            if (idx != Playback.current) Playback.play(idx, player.currentPosition)
        }
    }

    // ---- YouTube-style overlay controls ----
    private val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    private fun speedLabel(f: Float) = if (f == 1f) "ปกติ" else (if (f % 1f == 0f) "${f.toInt()}" else "$f") + "x"

    private fun setupControls() {
        controls = findViewById(R.id.controls)
        seek = findViewById(R.id.seek)
        btnPlay = findViewById(R.id.btnPlay)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnCC = findViewById(R.id.btnCC)
        btnFull = findViewById(R.id.btnFull)
        btnAutoplay = findViewById(R.id.btnAutoplay)
        timePill = findViewById(R.id.timePill)
        previewBox = findViewById(R.id.previewBox)
        previewImg = findViewById(R.id.previewImg)
        previewPill = findViewById(R.id.previewPill)
        centerRow = findViewById(R.id.centerRow)
        btnPlay.setOnClickListener {
            when {
                player.playbackState == Player.STATE_ENDED -> { player.seekTo(0); player.play() }
                player.playWhenReady -> player.pause()
                else -> { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
            }
            updatePlayButton(); scheduleHide()
        }
        btnPrev.setOnClickListener { Playback.prev(); scheduleHide() }
        btnNext.setOnClickListener {
            if (Playback.nextItem() == null) Toast.makeText(this, "ไม่มีวิดีโอถัดไป", Toast.LENGTH_SHORT).show() else Playback.next()
        }
        btnAutoplay.checked = Prefs.autoplay
        btnAutoplay.setOnClickListener {
            Prefs.autoplay = !Prefs.autoplay; btnAutoplay.checked = Prefs.autoplay
            Toast.makeText(this, if (Prefs.autoplay) "เปิดเล่นอัตโนมัติแล้ว" else "ปิดเล่นอัตโนมัติแล้ว", Toast.LENGTH_SHORT).show(); scheduleHide()
        }
        btnCC.setOnClickListener { toggleCC(); scheduleHide() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { settingsSheet() }
        btnFull.setOnClickListener { toggleFullscreen() }
        findViewById<View>(R.id.btnBack).setOnClickListener { toggleFullscreen() }
        // landscape bottom row
        findViewById<View>(R.id.landComments).setOnClickListener { openComments() }
        val soon = View.OnClickListener { Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show() }
        findViewById<View>(R.id.landLike).setOnClickListener(soon)
        findViewById<View>(R.id.landDislike).setOnClickListener(soon)
        findViewById<View>(R.id.landShare).setOnClickListener { shareUrl(item.url) }
        findViewById<View>(R.id.landMore).setOnClickListener { moreSheet() }
        findViewById<View>(R.id.landNext).setOnClickListener { Playback.next() }
        seek.listener = object : YtSeekBar.Listener {
            override fun onScrubStart(pos: Long) {
                playerView.removeCallbacks(hideCtl)
                centerRow.animate().alpha(0f).setDuration(120).start()
                findViewById<View>(R.id.bottomBar).alpha = 0f
                previewBox.visibility = View.VISIBLE
            }
            override fun onScrubMove(pos: Long, x: Float) = showPreview(pos, x)
            override fun onScrubStop(pos: Long, cancelled: Boolean) {
                if (!cancelled) player.seekTo(pos)
                previewBox.visibility = View.GONE
                findViewById<View>(R.id.bottomBar).alpha = 1f
                centerRow.animate().alpha(1f).setDuration(120).start()
                updateTime(); scheduleHide()
            }
        }
        controls.visibility = View.GONE
        seek.showKnob = false; seek.isEnabled = false
    }

    private var lastScrubX = 0f
    /** Storyboard frame + "mm:ss  chapter" pill above the knob while scrubbing. */
    private fun showPreview(pos: Long, x: Float) {
        lastScrubX = x
        val chapter = segments.lastOrNull { it.startTimeSeconds * 1000L <= pos }?.title?.takeIf { it.isNotBlank() }
        previewPill.text = if (chapter != null) "${fmtTime(pos)}  $chapter" else fmtTime(pos)
        timePill.text = "${fmtTime(pos)} / ${fmtTime(player.duration)}"
        val sb = storyboard
        if (sb == null) previewImg.visibility = View.GONE
        else {
            previewImg.visibility = View.VISIBLE
            sb.frame(pos) { if (seek.scrubbing) showPreview(pos, lastScrubX) }?.let { previewImg.setImageBitmap(it) }
        }
        val w = (if (previewBox.width > 0) previewBox.width else previewImg.layoutParams.width).toFloat()
        val m = dp(8f)
        previewBox.translationX = (x - w / 2).coerceIn(m, (playerBox.width - w - m).coerceAtLeast(m))
    }

    private fun layoutControls(land: Boolean) {
        val g = if (land) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnBack).visibility = g
        findViewById<View>(R.id.titleBox).visibility = g
        findViewById<View>(R.id.topSpacer).visibility = if (land) View.GONE else View.VISIBLE
        findViewById<View>(R.id.landRow).visibility = g
        btnFull.setImageResource(if (land) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        btnFull.contentDescription = if (land) "ออกจากโหมดเต็มหน้าจอ" else "เต็มหน้าจอ"
        val rowH = dp(80f).toInt()
        (seek.layoutParams as FrameLayout.LayoutParams).let { it.bottomMargin = if (land) rowH else 0; seek.layoutParams = it }
        val pad = if (land) dp(16f).toInt() else 0
        seek.setPadding(pad, 0, pad, 0)
        // portrait: the bar line sits on the video's bottom edge, the knob may overhang it
        seek.lineFromBottom = if (land) dp(14f) else dp(10f)
        seek.translationY = if (land) 0f else dp(9f)
        (findViewById<View>(R.id.bottomBar).layoutParams as FrameLayout.LayoutParams).let {
            it.bottomMargin = if (land) rowH + dp(26f).toInt() else dp(14f).toInt(); findViewById<View>(R.id.bottomBar).layoutParams = it
        }
        val pw = dp(if (land) 200f else 144f).toInt()
        previewImg.layoutParams = previewImg.layoutParams.apply { width = pw; height = pw * 9 / 16 }
        (previewBox.layoutParams as FrameLayout.LayoutParams).let { it.bottomMargin = if (land) rowH + dp(44f).toInt() else dp(18f).toInt(); previewBox.layoutParams = it }
        seek.visibility = if (inPip || (land && !controllerShown)) View.GONE else View.VISIBLE
    }

    private fun showControls(show0: Boolean, animate: Boolean = true) {
        val show = show0 && !inPip && progress == 0f
        controllerShown = show
        controls.animate().cancel()
        if (show) {
            controls.visibility = View.VISIBLE
            if (animate) { controls.alpha = 0f; controls.animate().alpha(1f).setDuration(150).start() } else controls.alpha = 1f
        } else if (animate && controls.visibility == View.VISIBLE) {
            controls.animate().alpha(0f).setDuration(200).withEndAction { if (!controllerShown) controls.visibility = View.GONE }.start()
        } else controls.visibility = View.GONE
        seek.showKnob = show; seek.isEnabled = show && !live
        seek.visibility = if (inPip || (isLandscape() && !show)) View.GONE else View.VISIBLE
        updateMinimizeButton(); updatePlayButton(); updateTime(); updateCC()
        btnAutoplay.checked = Prefs.autoplay
        scheduleHide()
    }

    private fun scheduleHide() {
        playerView.removeCallbacks(hideCtl)
        if (controllerShown && player.isPlaying && !seek.scrubbing) playerView.postDelayed(hideCtl, 3500)
    }

    private fun updatePlayButton() {
        if (!::btnPlay.isInitialized) return
        val ended = player.playbackState == Player.STATE_ENDED
        val playing = player.playWhenReady && !ended
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = if (playing) "หยุดชั่วคราว" else "เล่น"
    }

    private fun fmtTime(ms: Long): String {
        if (ms < 0 || ms == C.TIME_UNSET) return "0:00"
        val sec = ms / 1000; val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }

    private fun updateTime() {
        if (!::seek.isInitialized) return
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        if (live) {
            seek.duration = 0; timePill.text = "● สด"
        } else {
            seek.duration = dur
            seek.position = player.currentPosition
            seek.buffered = player.bufferedPosition
            if (!seek.scrubbing) timePill.text = "${fmtTime(player.currentPosition)} / ${fmtTime(dur)}"
        }
    }

    // ---- captions ----
    private fun textGroups() = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
    private fun captionsOn() = textGroups().any { it.isSelected } && !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    private fun updateCC() {
        if (!::btnCC.isInitialized) return
        val has = textGroups().isNotEmpty()
        btnCC.alpha = if (!has) 0.4f else if (captionsOn()) 1f else 0.7f
        btnCC.setColorFilter(if (captionsOn()) 0xFFFFFFFF.toInt() else 0xFFBDBDBD.toInt())
        btnCC.contentDescription = if (captionsOn()) "ปิดคำบรรยาย" else "เปิดคำบรรยาย"
    }
    private fun captionLabel(g: com.google.android.exoplayer2.Tracks.Group): String {
        val f = g.getTrackFormat(0)
        return f.label ?: f.language?.let { java.util.Locale.forLanguageTag(it).getDisplayName(java.util.Locale("th")) } ?: "คำบรรยาย"
    }
    private fun selectCaption(g: com.google.android.exoplayer2.Tracks.Group?) {
        val b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (g == null) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
        player.trackSelectionParameters = b.build()
        playerView.postDelayed({ updateCC() }, 300)
    }
    private fun toggleCC() {
        val groups = textGroups()
        if (groups.isEmpty()) { Toast.makeText(this, "วิดีโอนี้ไม่มีคำบรรยาย", Toast.LENGTH_SHORT).show(); return }
        if (captionsOn()) { selectCaption(null); Toast.makeText(this, "ปิดคำบรรยายแล้ว", Toast.LENGTH_SHORT).show(); return }
        // prefer Thai, then any non-auto track (they come first), then the first one
        val g = groups.firstOrNull { it.getTrackFormat(0).language?.startsWith("th") == true } ?: groups.first()
        selectCaption(g)
        Toast.makeText(this, "คำบรรยาย: ${captionLabel(g)}", Toast.LENGTH_SHORT).show()
    }
    private fun captionsSheet() {
        val groups = textGroups()
        if (groups.isEmpty()) { Toast.makeText(this, "วิดีโอนี้ไม่มีคำบรรยาย", Toast.LENGTH_SHORT).show(); return }
        val sel = if (captionsOn()) groups.indexOfFirst { it.isSelected } + 1 else 0
        choiceSheet("คำบรรยาย", listOf("ปิด") + groups.map { captionLabel(it) }, sel) { i -> selectCaption(if (i == 0) null else groups[i - 1]) }
    }

    /** ⚙: คุณภาพ / ความเร็วในการเล่น / คำบรรยาย. */
    private fun settingsSheet() {
        playerView.removeCallbacks(hideCtl)
        val q = qualities.getOrNull(Playback.current)?.label ?: "—"
        val sp = player.playbackParameters.speed
        val groups = textGroups()
        val cc = if (groups.isEmpty()) "ไม่มี" else if (captionsOn()) groups.firstOrNull { it.isSelected }?.let { captionLabel(it) } ?: "เปิด" else "ปิด"
        val d = sheet { box, dlg ->
            box.sheetRow(R.drawable.ic_tune, "คุณภาพ", q, end = chevron()) { dlg.dismiss(); pickQuality() }
            box.sheetRow(R.drawable.ic_speed, "ความเร็วในการเล่น", speedLabel(sp), end = chevron()) { dlg.dismiss(); speedSheet() }
            box.sheetRow(R.drawable.ic_cc, "คำบรรยาย", cc, end = chevron()) { dlg.dismiss(); captionsSheet() }
        }
        d.setOnDismissListener { scheduleHide() }
    }
    private fun chevron() = ImageView(this).apply {
        setImageResource(R.drawable.ic_chevron_right); setColorFilter(androidx.core.content.ContextCompat.getColor(context, R.color.text2))
    }
    private fun speedSheet() {
        val cur = player.playbackParameters.speed
        val i = speeds.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }
        choiceSheet("ความเร็วในการเล่น", speeds.map { speedLabel(it) }, i) { player.setPlaybackSpeed(speeds[it]) }
    }

    private fun toggleFullscreen() {
        runCatching {
            requestedOrientation = if (isLandscape()) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun moreSheet() = videoActionSheet(item) {
        Downloads.start(this, item, boundInfo?.takeIf { it.url == item.url || Playback.state.value.item?.url == item.url })
    }

    /** Landscape: first-comment card at the bottom left. */
    private fun updateLandComments(title: String?, preview: String?, avatar: String?) {
        findViewById<View>(R.id.landComments).visibility = if (title == null) View.INVISIBLE else View.VISIBLE
        findViewById<TextView>(R.id.landCommentsTitle).text = title
        findViewById<TextView>(R.id.landCommentText).text = preview
        findViewById<ImageView>(R.id.landCommentAvatar).apply { visibility = if (avatar == null) View.GONE else View.VISIBLE; loadAvatar(avatar) }
    }

    /** "...เพิ่มเติม": full title, counts, date and description in a sheet. */
    private fun descriptionSheet(title: String, stats: String, desc: CharSequence) = sheet { box, _ ->
        box.sheetTitle(title)
        val pad = dp(20f).toInt()
        box.addView(TextView(this).apply {
            text = stats; textSize = 13f; setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text2)); setPadding(pad, 0, pad, pad / 2)
        })
        box.addView(TextView(this).apply {
            text = desc.ifEmpty { "ไม่มีคำอธิบาย" }; textSize = 14f; setLineSpacing(dp(2f), 1f)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text1)); setPadding(pad, 0, pad, pad)
            movementMethod = LinkMovementMethod.getInstance(); setTextIsSelectable(true)
            setLinkTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.link))
        })
    }

    /** Double-tap left/right = seek 10 s, single tap = controls (swipe down is handled by [DragFrame]). */
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { seekBy(e); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val side = if (e.x < playerView.width / 2f) -1 else 1
                if (System.currentTimeMillis() - lastSeekAt < 800 && side == seekSide) { seekBy(e); return true }
                showControls(!controllerShown)
                return true
            }
        })
        // no long-press: a slow tap must still toggle the controls
        detector.setIsLongpressEnabled(false)
        playerView.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }
    }

    private fun seekBy(e: MotionEvent) {
        if (qualities.isEmpty() || live) return
        val side = if (e.x < playerView.width / 2f) -1 else 1
        if (side != seekSide) seekAccum = 0
        seekSide = side
        seekAccum += 10
        lastSeekAt = System.currentTimeMillis()
        if (side < 0) player.seekBack() else player.seekForward()
        val tv = if (side < 0) seekLeft else seekRight
        (if (side < 0) seekRight else seekLeft).alpha = 0f
        tv.text = if (side < 0) "◀◀\n$seekAccum วินาที" else "▶▶\n$seekAccum วินาที"
        tv.animate().cancel(); tv.alpha = 1f
        playerView.removeCallbacks(hideSeek); playerView.postDelayed(hideSeek, 700)
    }

    /** Title, meta line, channel/actions row and the comments card shown above the up-next videos. */
    private inner class HeaderAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var title = ""; private var channel = ""; private var meta = ""; private var stats = ""
        private var avatar: String? = null; private var channelUrl: String? = null; private var subCount = -1L; private var likes = ""; private var desc: CharSequence = ""

        private var cTitle: String? = null; private var cPreview: String? = null; private var cAvatar: String? = null
        fun setComments(title: String?, preview: String?, avatar: String?) {
            cTitle = title; cPreview = preview; cAvatar = avatar; notifyItemChanged(0); updateLandComments(title, preview, avatar)
        }

        fun reset() { likes = ""; desc = ""; subCount = -1; stats = "" }

        fun bindItem(v: VideoItem) {
            title = v.title; channel = v.channel; avatar = v.avatar; channelUrl = v.channelUrl
            meta = listOf(v.channel, Fmt.views(v.views, v.live), Fmt.ageMs(v.uploadedMs, v.age).takeUnless { v.live }.orEmpty()).filter { it.isNotBlank() }.joinToString("  ")
            if (stats.isEmpty()) stats = meta
            notifyItemChanged(0)
        }

        fun bindInfo(i: StreamInfo) {
            val live = i.streamType == StreamType.LIVE_STREAM
            title = i.name
            channel = i.uploaderName.orEmpty()
            val age = if (live) "" else Fmt.age(i.uploadDate, i.textualUploadDate)
            meta = listOf(channel, Fmt.views(i.viewCount, live), age).filter { it.isNotBlank() }.joinToString("  ")
            val date = i.uploadDate?.offsetDateTime()?.let { Fmt.thaiDate(it) } ?: i.textualUploadDate.orEmpty()
            stats = listOf(channel, Fmt.subscribers(i.uploaderSubscriberCount), Fmt.fullViews(i.viewCount, live), if (live) "" else date,
                if (i.likeCount >= 0) "ถูกใจ ${Fmt.shortNum(i.likeCount)}" else "").filter { it.isNotBlank() }.joinToString(" · ")
            subCount = i.uploaderSubscriberCount
            channelUrl = i.uploaderUrl ?: channelUrl
            avatar = i.uploaderAvatars.pick(88) ?: avatar
            likes = if (i.likeCount >= 0) Fmt.shortNum(i.likeCount) else ""
            val d = i.description ?: Description.EMPTY_DESCRIPTION
            desc = if (d.type() == Description.Type.HTML) HtmlCompat.fromHtml(d.content().orEmpty(), HtmlCompat.FROM_HTML_MODE_LEGACY).trim()
            else android.text.SpannableString(d.content().orEmpty()).also { android.text.util.Linkify.addLinks(it, android.text.util.Linkify.WEB_URLS) }
            notifyItemChanged(0)
        }

        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_watch_header, parent, false)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            v.findViewById<TextView>(R.id.wTitle).text = title
            val more = "...เพิ่มเติม"
            val sp = android.text.SpannableStringBuilder(meta).append(if (meta.isEmpty()) "" else "  ")
            val st = sp.length
            sp.append(more)
            sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(this@WatchActivity, R.color.text1)), st, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            v.findViewById<TextView>(R.id.wMeta).text = sp
            v.findViewById<View>(R.id.titleRow).setOnClickListener { descriptionSheet(title, stats.ifEmpty { meta }, desc) }
            v.findViewById<ImageView>(R.id.wAvatar).apply { loadAvatar(avatar); setOnClickListener { openChannel(channelUrl, channel, avatar) } }
            v.findViewById<TextView>(R.id.likeCount).apply { text = likes; visibility = if (likes.isEmpty()) View.GONE else View.VISIBLE }
            v.findViewById<View>(R.id.btnShare).setOnClickListener { shareUrl(item.url) }
            v.findViewById<View>(R.id.btnMore).setOnClickListener { moreSheet() }
            val cRow = v.findViewById<View>(R.id.commentsRow)
            cRow.visibility = if (cTitle == null) View.GONE else View.VISIBLE
            v.findViewById<TextView>(R.id.commentsTitle).text = cTitle
            v.findViewById<TextView>(R.id.previewText).text = cPreview
            v.findViewById<ImageView>(R.id.previewAvatar).apply { visibility = if (cAvatar == null) View.GONE else View.VISIBLE; loadAvatar(cAvatar) }
            cRow.setOnClickListener { openComments() }
            val soon = View.OnClickListener { Toast.makeText(this@WatchActivity, R.string.coming_soon, Toast.LENGTH_SHORT).show() }
            v.findViewById<TextView>(R.id.btnSubscribe).bindSubscribe({
                channelUrl?.let { u -> Channel(u, channel, avatar, subCount) }
            }, capsule = true)
            v.findViewById<View>(R.id.btnLike).setOnClickListener(soon)
            v.findViewById<View>(R.id.btnDislike).setOnClickListener(soon)
        }
    }
}
