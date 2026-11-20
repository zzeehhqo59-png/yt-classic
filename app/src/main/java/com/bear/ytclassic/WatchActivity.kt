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
    companion object {
        const val EXTRA_ITEM = "item"
        /** Start the page collapsed at this drag progress (1 = mini player) and animate it open. */
        const val EXTRA_EXPAND_FROM = "expand_from"
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

        val qBtn = findViewById<View>(R.id.btnPlayerQuality)
        qBtn.setOnClickListener { pickQuality() }
        btnMinimize.setOnClickListener { minimize() }
        playerView.setControllerVisibilityListener(StyledPlayerView.ControllerVisibilityListener { vis ->
            qBtn.visibility = if (vis == View.VISIBLE && qualities.size > 1 && !inPip) View.VISIBLE else View.GONE
            controllerShown = vis == View.VISIBLE
            updateMinimizeButton()
        })
        setupGestures()
        playerView.setFullscreenButtonClickListener { full ->
            runCatching {
                requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
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
        errorBox.visibility = if (st.error != null) View.VISIBLE else View.GONE
        playerError.text = st.error
        stateBlocking = st.loading || st.error != null
        updateMinimizeButton()
        updatePipParams()
        st.info?.let { loadComments(it.url) }
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
                    val n = if (ci.commentsCount >= 0) " · " + Fmt.shortNum(ci.commentsCount.toLong()) else ""
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

    private var controllerShown = false
    private var stateBlocking = false
    /** The ˅ button shows with the controls, and always while loading / showing an error (so the page can still be minimized). */
    private fun updateMinimizeButton() {
        btnMinimize.visibility = if ((controllerShown || stateBlocking) && !isLandscape() && !inPip) View.VISIBLE else View.GONE
    }

    // ---- drag to minimize ----
    private fun dp(v: Float) = v * resources.displayMetrics.density
    /** Distance the player travels to reach the mini bar (above the bottom nav of the home screen). */
    private fun range(): Float {
        val target = root.height - root.paddingBottom - dp(56f + 1f + 2f + 56f)
        return (target - playerBox.top).coerceAtLeast(dp(100f))
    }

    private fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        val w = playerBox.width.toFloat().coerceAtLeast(1f)
        val sc = 1f - progress * (1f - dp(100f) / w)
        playerBox.pivotX = 0f; playerBox.pivotY = 0f
        playerBox.scaleX = sc; playerBox.scaleY = sc
        playerBox.translationY = progress * range()
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
        playerView.hideController()
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
    }

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

    /** Double-tap left/right = seek 10 s, single tap = controls (swipe down is handled by [DragFrame]). */
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

        private var cTitle: String? = null; private var cPreview: String? = null; private var cAvatar: String? = null
        fun setComments(title: String?, preview: String?, avatar: String?) { cTitle = title; cPreview = preview; cAvatar = avatar; notifyItemChanged(0) }

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
            val cRow = v.findViewById<View>(R.id.commentsRow)
            cRow.visibility = if (cTitle == null) View.GONE else View.VISIBLE
            v.findViewById<View>(R.id.commentsDivider).visibility = cRow.visibility
            v.findViewById<TextView>(R.id.commentsTitle).text = cTitle
            v.findViewById<TextView>(R.id.previewText).text = cPreview
            v.findViewById<ImageView>(R.id.previewAvatar).apply { visibility = if (cAvatar == null) View.GONE else View.VISIBLE; loadAvatar(cAvatar) }
            cRow.setOnClickListener { openComments() }
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
