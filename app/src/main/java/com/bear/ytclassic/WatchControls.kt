package com.bear.ytclassic

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import coil3.load
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * YouTube-style overlay on the watch page's video: play/prev/next, scrubber with storyboard preview,
 * captions / speed / quality sheets, landscape bottom row and double-tap seeking.
 * The page itself (layout, mini player, PiP) stays in [WatchActivity] and is reached through [Host].
 */
class WatchControls(private val a: AppCompatActivity, private val root: View, private val playerView: StyledPlayerView, private val playerBox: View, private val host: Host) {
    interface Host {
        val inPip: Boolean
        /** The page is being dragged / animated into the mini player. */
        val collapsed: Boolean
        fun isLandscape(): Boolean
        fun onControlsVisibility()
        fun openComments()
        fun moreSheet()
        fun share()
        fun toggleFullscreen()
        fun rating(): Int
        fun rate(value: Int)
    }

    private val player get() = Playback.player
    private val tracks = WatchTracks(a, playerView)
    private val qualities get() = Playback.qualities
    private val live get() = Playback.live
    /** Controls currently shown. */
    var shown = false
        private set
    private fun dp(v: Float) = v * a.resources.displayMetrics.density
    private lateinit var seekLeft: TextView
    private lateinit var seekRight: TextView
    private var seekSide = 0
    private var seekAccum = 0
    private var lastSeekAt = 0L
    private val hideSeek = Runnable {
        seekLeft.animate().alpha(0f).setDuration(250).start()
        seekRight.animate().alpha(0f).setDuration(250).start()
        seekAccum = 0; seekSide = 0
    }
    private lateinit var controls: View
    lateinit var seek: YtSeekBar
        private set
    private lateinit var btnPlay: ImageButton
    private lateinit var btnPrev: View
    lateinit var btnNext: View
        private set
    private lateinit var btnCC: ImageButton
    private lateinit var btnFull: ImageButton
    private lateinit var btnAutoplay: AutoplaySwitch
    private lateinit var timePill: TextView
    private lateinit var previewBox: View
    private lateinit var previewImg: ImageView
    private lateinit var previewPill: TextView
    private lateinit var centerRow: View
    private var storyboard: Storyboard? = null
    /** Chapter gaps on the bar, the landscape chapter pill and the header's chapter row. */
    lateinit var chapters: WatchChapters
        private set
    private val hideCtl = Runnable { show(false) }
    private val ticker = object : Runnable {
        override fun run() { updateTime(); playerView.postDelayed(this, 500) }
    }
    private val ctlListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { updatePlayButton(); scheduleHide() }
        override fun onPlaybackStateChanged(state: Int) { updatePlayButton(); updateTime() }
        override fun onTracksChanged(t: com.google.android.exoplayer2.Tracks) { tracks.applyPrefs(); updateCC() }
    }

    private fun pickQuality() {
        if (qualities.isEmpty()) { Toast.makeText(a, "ยังไม่มีตัวเลือกคุณภาพ", Toast.LENGTH_SHORT).show(); return }
        val labels = qualities.map { it.label }.reversed()
        a.choiceSheet(a.getString(R.string.choose_quality), labels, qualities.size - 1 - Playback.current) { which ->
            val idx = qualities.size - 1 - which
            if (idx != Playback.current) Playback.play(idx, player.currentPosition)
        }
    }

    // ---- YouTube-style overlay controls ----
    private val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    private fun speedLabel(f: Float) = if (f == 1f) "ปกติ" else (if (f % 1f == 0f) "${f.toInt()}" else "$f") + "x"

    fun setup() {
        seekLeft = root.findViewById(R.id.seekLeft)
        seekRight = root.findViewById(R.id.seekRight)
        controls = root.findViewById(R.id.controls)
        seek = root.findViewById(R.id.seek)
        btnPlay = root.findViewById(R.id.btnPlay)
        btnPrev = root.findViewById(R.id.btnPrev)
        btnNext = root.findViewById(R.id.btnNext)
        btnCC = root.findViewById(R.id.btnCC)
        btnFull = root.findViewById(R.id.btnFull)
        btnAutoplay = root.findViewById(R.id.btnAutoplay)
        timePill = root.findViewById(R.id.timePill)
        previewBox = root.findViewById(R.id.previewBox)
        previewImg = root.findViewById(R.id.previewImg)
        previewPill = root.findViewById(R.id.previewPill)
        centerRow = root.findViewById(R.id.centerRow)
        chapters = WatchChapters(a, seek, root.findViewById(R.id.chapterPill)) { player.seekTo(it); updateTime(); scheduleHide() }
        btnPlay.setOnClickListener {
            when {
                player.playbackState == Player.STATE_ENDED -> { Playback.cancelAutoplay(); player.seekTo(0); player.play() }
                player.playWhenReady -> player.pause()
                else -> { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
            }
            updatePlayButton(); scheduleHide()
        }
        btnPrev.setOnClickListener { Playback.prev(); scheduleHide() }
        btnNext.setOnClickListener {
            if (Playback.nextItem() == null) Toast.makeText(a, "ไม่มีวิดีโอถัดไป", Toast.LENGTH_SHORT).show() else Playback.next()
        }
        btnAutoplay.checked = Prefs.autoplay
        btnAutoplay.setOnClickListener {
            Prefs.autoplay = !Prefs.autoplay; btnAutoplay.checked = Prefs.autoplay
            Toast.makeText(a, if (Prefs.autoplay) "เปิดเล่นอัตโนมัติแล้ว" else "ปิดเล่นอัตโนมัติแล้ว", Toast.LENGTH_SHORT).show(); scheduleHide()
        }
        btnCC.setOnClickListener { tracks.toggle(); updateCC(); scheduleHide() }
        root.findViewById<View>(R.id.btnSettings).setOnClickListener { settingsSheet() }
        btnFull.setOnClickListener { host.toggleFullscreen() }
        root.findViewById<View>(R.id.btnBack).setOnClickListener { host.toggleFullscreen() }
        // landscape bottom row
        root.findViewById<View>(R.id.landComments).setOnClickListener { host.openComments() }
        root.findViewById<View>(R.id.landLike).setOnClickListener { host.rate(Likes.LIKE); scheduleHide() }
        root.findViewById<View>(R.id.landDislike).setOnClickListener { host.rate(Likes.DISLIKE); scheduleHide() }
        root.findViewById<View>(R.id.landShare).setOnClickListener { host.share() }
        root.findViewById<View>(R.id.landMore).setOnClickListener { host.moreSheet() }
        root.findViewById<View>(R.id.landNext).setOnClickListener { Playback.next() }
        seek.listener = object : YtSeekBar.Listener {
            override fun onScrubStart(pos: Long) {
                playerView.removeCallbacks(hideCtl)
                centerRow.animate().alpha(0f).setDuration(120).start()
                root.findViewById<View>(R.id.bottomBar).alpha = 0f
                previewBox.visibility = View.VISIBLE
            }
            override fun onScrubMove(pos: Long, x: Float) = showPreview(pos, x)
            override fun onScrubStop(pos: Long, cancelled: Boolean) {
                if (!cancelled) player.seekTo(pos)
                previewBox.visibility = View.GONE
                root.findViewById<View>(R.id.bottomBar).alpha = 1f
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
        val chapter = chapters.chapters.lastOrNull { it.startMs <= pos }?.title?.takeIf { it.isNotBlank() }
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

    fun layout(land: Boolean) {
        val g = if (land) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.btnBack).visibility = g
        root.findViewById<View>(R.id.titleBox).visibility = g
        root.findViewById<View>(R.id.topSpacer).visibility = if (land) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.landRow).visibility = g
        chapters.layout(land)
        btnFull.setImageResource(if (land) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        btnFull.contentDescription = if (land) "ออกจากโหมดเต็มหน้าจอ" else "เต็มหน้าจอ"
        val rowH = dp(80f).toInt()
        (seek.layoutParams as FrameLayout.LayoutParams).let { it.bottomMargin = if (land) rowH else 0; seek.layoutParams = it }
        val pad = if (land) dp(16f).toInt() else 0
        seek.setPadding(pad, 0, pad, 0)
        // portrait: the bar line sits on the video's bottom edge, the knob may overhang it
        seek.lineFromBottom = if (land) dp(14f) else dp(10f)
        seek.translationY = if (land) 0f else dp(9f)
        (root.findViewById<View>(R.id.bottomBar).layoutParams as FrameLayout.LayoutParams).let {
            it.bottomMargin = if (land) rowH + dp(26f).toInt() else dp(14f).toInt(); root.findViewById<View>(R.id.bottomBar).layoutParams = it
        }
        val pw = dp(if (land) 200f else 144f).toInt()
        previewImg.layoutParams = previewImg.layoutParams.apply { width = pw; height = pw * 9 / 16 }
        (previewBox.layoutParams as FrameLayout.LayoutParams).let { it.bottomMargin = if (land) rowH + dp(44f).toInt() else dp(18f).toInt(); previewBox.layoutParams = it }
        seek.visibility = if (host.inPip || (land && !shown)) View.GONE else View.VISIBLE
    }

    fun show(show0: Boolean, animate: Boolean = true) {
        val show = show0 && !host.inPip && !host.collapsed
        shown = show
        controls.animate().cancel()
        if (show) {
            controls.visibility = View.VISIBLE
            if (animate) { controls.alpha = 0f; controls.animate().alpha(1f).setDuration(150).start() } else controls.alpha = 1f
        } else if (animate && controls.visibility == View.VISIBLE) {
            controls.animate().alpha(0f).setDuration(200).withEndAction { if (!shown) controls.visibility = View.GONE }.start()
        } else controls.visibility = View.GONE
        seek.showKnob = show; seek.isEnabled = show && !live
        seek.visibility = if (host.inPip || (host.isLandscape() && !show)) View.GONE else View.VISIBLE
        host.onControlsVisibility(); updatePlayButton(); updateTime(); updateCC()
        btnAutoplay.checked = Prefs.autoplay
        scheduleHide()
    }

    private fun scheduleHide() {
        playerView.removeCallbacks(hideCtl)
        if (shown && player.isPlaying && !seek.scrubbing) playerView.postDelayed(hideCtl, 3500)
    }

    fun updatePlayButton() {
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

    fun updateTime() {
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
        chapters.tick(if (live) -1 else player.currentPosition)
    }

    // ---- captions ----
    fun updateCC() {
        if (!::btnCC.isInitialized) return
        val on = tracks.captionsOn()
        val has = tracks.textGroups().isNotEmpty()
        btnCC.alpha = if (!has) 0.4f else if (on) 1f else 0.7f
        btnCC.setColorFilter(if (on) 0xFFFFFFFF.toInt() else 0xFFBDBDBD.toInt())
        btnCC.contentDescription = if (on) "ปิดคำบรรยาย" else "เปิดคำบรรยาย"
    }

    private fun fontSheet() {
        a.choiceSheet("ขนาดคำบรรยาย", Captions.sizeNames.toList(), Prefs.ccFontSize) { i ->
            Prefs.ccFontSize = i
            tracks.applyFont()
        }
    }

    /** ⚙: คุณภาพ / ความเร็วในการเล่น / คำบรรยาย / แทร็กเสียง / ตั้งเวลาปิด. */
    private fun settingsSheet() {
        playerView.removeCallbacks(hideCtl)
        val q = qualities.getOrNull(Playback.current)?.label ?: "—"
        val sp = player.playbackParameters.speed
        val cc = tracks.captionSummary()
        val d = a.sheet { box, dlg ->
            box.sheetRow(R.drawable.ic_tune, "คุณภาพ", q, end = chevron()) { dlg.dismiss(); pickQuality() }
            box.sheetRow(R.drawable.ic_speed, "ความเร็วในการเล่น", speedLabel(sp), end = chevron()) { dlg.dismiss(); speedSheet() }
            box.sheetRow(R.drawable.ic_cc, "คำบรรยาย", cc, end = chevron()) { dlg.dismiss(); tracks.captionsSheet() }
            box.sheetRow(R.drawable.ic_cc, "ขนาดคำบรรยาย", Captions.sizeNames.getOrElse(Prefs.ccFontSize) { "ปกติ" }, end = chevron()) { dlg.dismiss(); fontSheet() }
            tracks.audioSummary()?.let { audio ->
                box.sheetRow(R.drawable.ic_audio, "แทร็กเสียง", audio, end = chevron()) { dlg.dismiss(); tracks.audioSheet() }
            }
            box.sheetRow(R.drawable.ic_timer, "ตั้งเวลาปิด", SleepTimer.summary(), end = chevron()) { dlg.dismiss(); sleepSheet() }
        }
        d.setOnDismissListener { scheduleHide() }
    }
    private fun chevron() = ImageView(a).apply {
        setImageResource(R.drawable.ic_chevron_right); setColorFilter(androidx.core.content.ContextCompat.getColor(context, R.color.text2))
    }
    private fun sleepSheet() {
        val labels = listOf("ปิด") + SleepTimer.MINUTE_OPTIONS.map { "$it นาที" } + listOf("ถึงจบตอนนี้")
        a.choiceSheet("ตั้งเวลาปิด", labels, SleepTimer.optionIndex()) { SleepTimer.applyOption(it) }
    }
    private fun speedSheet() {
        val cur = player.playbackParameters.speed
        val i = speeds.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }
        a.choiceSheet("ความเร็วในการเล่น", speeds.map { speedLabel(it) }, i) { player.setPlaybackSpeed(speeds[it]) }
    }


    /** Landscape: first-comment card at the bottom left. */
    fun updateLandComments(title: String?, preview: String?, avatar: String?) {
        root.findViewById<View>(R.id.landComments).visibility = if (title == null) View.INVISIBLE else View.VISIBLE
        root.findViewById<TextView>(R.id.landCommentsTitle).text = title
        root.findViewById<TextView>(R.id.landCommentText).text = preview
        root.findViewById<ImageView>(R.id.landCommentAvatar).apply { visibility = if (avatar == null) View.GONE else View.VISIBLE; loadAvatar(avatar) }
    }


    /** Double-tap left/right = seek 10 s, single tap = controls (swipe down is handled by [DragFrame]). */
    fun setupGestures() {
        val detector = GestureDetector(a, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { seekBy(e); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val side = if (e.x < playerView.width / 2f) -1 else 1
                if (System.currentTimeMillis() - lastSeekAt < 800 && side == seekSide) { seekBy(e); return true }
                show(!shown)
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


    /** Storyboard and chapters for scrubbing follow the loaded stream info. */
    private var sbFor: StreamInfo? = null
    fun bindInfo(info: StreamInfo?) {
        if (info === sbFor) return
        sbFor = info; storyboard?.release(); storyboard = Storyboard.of(info)
        if (::chapters.isInitialized) chapters.bind(info)
    }

    /** Landscape title row and the up-next button for the current video. */
    fun renderItem(it: VideoItem) {
        root.findViewById<TextView>(R.id.landTitle).text = it.title
        root.findViewById<TextView>(R.id.landChannel).text = it.channel
        val nxt = Playback.nextItem()
        btnNext.alpha = if (nxt != null) 1f else 0.4f
        root.findViewById<View>(R.id.landNext).visibility = if (nxt != null) View.VISIBLE else View.INVISIBLE
        nxt?.thumb?.let { u -> root.findViewById<ImageView>(R.id.landNextThumb).load(u) }
        updateRating()
        updatePlayButton(); updateTime()
    }

    /** Landscape 👍 / 👎 follow the local rating. */
    fun updateRating() {
        val r = host.rating()
        root.findViewById<ImageView>(R.id.landLike).setColorFilter(rateColor(a, r == Likes.LIKE, android.R.color.white))
        root.findViewById<ImageView>(R.id.landDislike).setColorFilter(rateColor(a, r == Likes.DISLIKE, android.R.color.white))
    }

    fun attach() {
        player.addListener(ctlListener)
        playerView.removeCallbacks(ticker); playerView.post(ticker)
        updatePlayButton(); tracks.applyFont(); updateCC()
    }

    fun detach() {
        player.removeListener(ctlListener)
        playerView.removeCallbacks(ticker)
    }

    fun release() { storyboard?.release() }
}
