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
    companion object { const val EXTRA_ITEM = "item" }

    private lateinit var item: VideoItem
    private val player get() = Playback.player
    private lateinit var playerView: StyledPlayerView
    private lateinit var playerBox: FrameLayout
    private lateinit var playerError: TextView
    private lateinit var playerLoading: View
    private lateinit var btnMinimize: View
    private lateinit var list: RecyclerView
    private val header = HeaderAdapter()
    private val related = VideoAdapter(false) { openVideo(it) }
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
        findViewById<View>(R.id.root).padForSystemBars { !isLandscape() && !inPip }
        seekLeft = findViewById(R.id.seekLeft)
        seekRight = findViewById(R.id.seekRight)
        playerView = findViewById(R.id.playerView)
        playerBox = findViewById(R.id.playerBox)
        playerError = findViewById(R.id.playerError)
        playerLoading = findViewById(R.id.playerLoading)
        btnMinimize = findViewById(R.id.btnMinimize)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = ConcatAdapter(header, related)
        playerError.setOnClickListener { Playback.retry() }

        val qBtn = findViewById<View>(R.id.btnPlayerQuality)
        qBtn.setOnClickListener { pickQuality() }
        btnMinimize.setOnClickListener { minimize() }
        playerView.setControllerVisibilityListener(StyledPlayerView.ControllerVisibilityListener { vis ->
            qBtn.visibility = if (vis == View.VISIBLE && qualities.size > 1 && !inPip) View.VISIBLE else View.GONE
            btnMinimize.visibility = if (vis == View.VISIBLE && !isLandscape() && !inPip) View.VISIBLE else View.GONE
        })
        setupGestures()
        playerView.setFullscreenButtonClickListener { full ->
            requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLandscape()) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else minimize()
            }
        })
        applyLayout(resources.configuration)
        if (!handleIntent(intent)) { finish(); return }
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
        if (Playback.state.value.item?.url != v.url) {
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
        playerError.visibility = if (st.error != null) View.VISIBLE else View.GONE
        playerError.text = st.error
        updatePipParams()
    }

    /** Drops back to the app with the video still playing in the mini player. */
    private fun minimize() {
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, R.anim.slide_down)
    }

    override fun onStart() {
        super.onStart()
        playerView.player = player
        player.addListener(pipListener)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            && !Prefs.p.getBoolean("asked_notif", false)) {
            Prefs.p.edit().putBoolean("asked_notif", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onDestroy() { super.onDestroy(); if (inPip) Playback.pip.value = false }

    override fun onStop() {
        super.onStop()
        player.removeListener(pipListener)
        playerView.player = null
    }

    /** Take the video surface back (the mini bar may have grabbed it). */
    private fun reattach() { playerView.player = null; playerView.player = player }

    override fun onResume() { super.onResume(); reattach(); internalNav = false; updatePipParams(); header.notifyItemChanged(0) }

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
        playerView.useController = !inPip
        if (inPip) playerView.hideController()
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
        lp.height = if (land || inPip) ViewGroup.LayoutParams.MATCH_PARENT else resources.displayMetrics.widthPixels * 9 / 16
        playerBox.layoutParams = lp
        list.visibility = if (land || inPip) View.GONE else View.VISIBLE
        playerView.setShowSubtitleButton(true)
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (land) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else ctl.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun pickQuality() {
        if (qualities.isEmpty()) { Toast.makeText(this, "ยังไม่มีตัวเลือกคุณภาพ", Toast.LENGTH_SHORT).show(); return }
        val labels = qualities.map { it.label }.reversed().toTypedArray()
        val checked = qualities.size - 1 - Playback.current
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_quality)
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                val idx = qualities.size - 1 - which
                if (idx != Playback.current) Playback.play(idx, player.currentPosition)
            }.show()
    }

    /** Double-tap left/right = seek 10 s, single tap = controls, swipe down = minimize. */
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { seekBy(e); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val side = if (e.x < playerView.width / 2f) -1 else 1
                if (System.currentTimeMillis() - lastSeekAt < 800 && side == seekSide) { seekBy(e); return true }
                if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dy = e2.y - (e1?.y ?: e2.y)
                if (!isLandscape() && !inPip && vy > 1200 && dy > 80 * resources.displayMetrics.density / 2 && kotlin.math.abs(vy) > kotlin.math.abs(vx)) {
                    minimize(); return true
                }
                return false
            }
        })
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

    /** Title, counts, channel row and description shown above the related videos. */
    private inner class HeaderAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var view: View? = null
        private var title = ""; private var views = ""; private var channel = ""; private var subs = ""
        private var avatar: String? = null; private var channelUrl: String? = null; private var subCount = -1L; private var likes = ""; private var desc: CharSequence = ""
        private var expanded = false

        fun reset() { expanded = false; likes = ""; desc = ""; subs = ""; subCount = -1 }

        fun bindItem(v: VideoItem) {
            title = v.title; channel = v.channel; avatar = v.avatar; channelUrl = v.channelUrl
            views = listOf(Fmt.views(v.views, v.live), v.age).filter { it.isNotBlank() }.joinToString(" · ")
            notifyItemChanged(0)
        }

        fun bindInfo(i: StreamInfo) {
            val live = i.streamType == StreamType.LIVE_STREAM
            title = i.name
            val date = i.uploadDate?.offsetDateTime()?.let { Fmt.thaiDate(it) } ?: i.textualUploadDate.orEmpty()
            views = listOf(Fmt.fullViews(i.viewCount, live), if (live) "" else date).filter { it.isNotBlank() }.joinToString(" · ")
            channel = i.uploaderName.orEmpty()
            subs = Fmt.subscribers(i.uploaderSubscriberCount); subCount = i.uploaderSubscriberCount
            channelUrl = i.uploaderUrl ?: channelUrl
            avatar = i.uploaderAvatars.pick(88) ?: avatar
            likes = if (i.likeCount >= 0) Fmt.shortNum(i.likeCount) else ""
            val d = i.description ?: Description.EMPTY_DESCRIPTION
            desc = if (d.type() == Description.Type.HTML) HtmlCompat.fromHtml(d.content().orEmpty(), HtmlCompat.FROM_HTML_MODE_LEGACY).trim()
            else d.content().orEmpty()
            notifyItemChanged(0)
        }

        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_watch_header, parent, false)
            view = v
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            v.findViewById<TextView>(R.id.wTitle).apply { text = title; maxLines = if (expanded) 10 else 2 }
            v.findViewById<TextView>(R.id.wViews).text = views
            v.findViewById<TextView>(R.id.wChannel).text = channel
            v.findViewById<TextView>(R.id.wSubs).apply { text = subs; visibility = if (subs.isEmpty()) View.GONE else View.VISIBLE }
            v.findViewById<ImageView>(R.id.wAvatar).loadAvatar(avatar)
            v.findViewById<TextView>(R.id.btnLike).text = likes.ifEmpty { getString(R.string.like) }
            val descView = v.findViewById<TextView>(R.id.wDesc)
            val toggle = v.findViewById<TextView>(R.id.wDescToggle)
            descView.text = desc
            descView.movementMethod = LinkMovementMethod.getInstance()
            descView.maxLines = if (expanded) Int.MAX_VALUE else 3
            descView.visibility = if (desc.isEmpty()) View.GONE else View.VISIBLE
            toggle.visibility = descView.visibility
            toggle.setText(if (expanded) R.string.show_less else R.string.show_more)
            val flip = View.OnClickListener { expanded = !expanded; notifyItemChanged(0) }
            toggle.setOnClickListener(flip)
            v.findViewById<View>(R.id.titleRow).setOnClickListener(flip)
            v.findViewById<View>(R.id.btnQuality).setOnClickListener { pickQuality() }
            v.findViewById<View>(R.id.btnShare).setOnClickListener { shareUrl(item.url) }
            v.findViewById<View>(R.id.btnSave).setOnClickListener { saveToPlaylist(item) }
            val soon = View.OnClickListener { Toast.makeText(this@WatchActivity, R.string.coming_soon, Toast.LENGTH_SHORT).show() }
            v.findViewById<TextView>(R.id.btnSubscribe).bindSubscribe({
                channelUrl?.let { u -> Channel(u, channel, avatar, subCount) }
            })
            v.findViewById<View>(R.id.channelRow).setOnClickListener { openChannel(channelUrl, channel, avatar) }
            v.findViewById<View>(R.id.btnLike).setOnClickListener(soon)
            v.findViewById<View>(R.id.btnDislike).setOnClickListener(soon)
        }
    }
}
