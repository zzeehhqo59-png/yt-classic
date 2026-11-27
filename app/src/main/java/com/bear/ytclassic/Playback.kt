package com.bear.ytclassic

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaMetadata
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.SingleSampleMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.util.MimeTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.time.OffsetDateTime
import java.util.Locale

/** One app-wide player shared by the watch page, the mini player, the popup (PiP) and the notification. */
object Playback {
    data class Quality(val label: String, val height: Int, val build: () -> MediaSource)
    data class State(
        val item: VideoItem? = null,
        val info: StreamInfo? = null,
        val loading: Boolean = false,
        val error: String? = null,
    )

    private lateinit var app: Context
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    /** True while the watch page is shown as a popup (PiP); the mini bar hides then so the popup keeps the video. */
    val pip = MutableStateFlow(false)
    /** True while the full watch page exists; the mini bar stays hidden (and off the video surface) meanwhile. */
    val watchActive = MutableStateFlow(false)
    var qualities: List<Quality> = emptyList(); private set
    var current = -1; private set
    var live = false; private set
    private var subtitles: List<SubtitlesStream> = emptyList()
    var audioTracks: List<AudioTracks.Track> = emptyList(); private set
    var currentAudio: AudioTracks.Track? = null; private set
    private val _autoplay = kotlinx.coroutines.flow.MutableStateFlow<Autoplay.Offer?>(null)
    val autoplayOffer: StateFlow<Autoplay.Offer?> = _autoplay
    private var loadJob: Job? = null
    private val dsFactory by lazy { YtDataSource.Factory(DownloaderImpl.instance.client) }
    private var appVisible = true
    /** Playing a downloaded file (no network, no re-fetch on errors). */
    var offline = false; private set
    private var offlineFile: java.io.File? = null

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(app)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 60_000, 2_500, 5_000).build())
            .build().also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        savePosition()
                        if (offline) { _state.value = _state.value.copy(loading = false, error = "เล่นไฟล์ที่ดาวน์โหลดไว้ไม่ได้\n(ไฟล์อาจถูกลบหรือเสียหาย)"); return }
                        if (StreamRecovery.shouldRetry(error.errorCode, retries, offline) && _state.value.item != null) {
                            retries++
                            lastRetryAt = System.currentTimeMillis()
                            _state.value = _state.value.copy(loading = true, error = null)
                            handler.removeCallbacks(recoverGo)
                            handler.postDelayed(recoverGo, StreamRecovery.delayMs(retries))
                            return
                        }
                        _state.value = _state.value.copy(loading = false, error = "เล่นวิดีโอไม่ได้ ลองเลือกคุณภาพอื่น\n(${error.errorCodeName})")
                    }
                    override fun onPlaybackStateChanged(s: Int) {
                        if (s == Player.STATE_READY && _state.value.loading) _state.value = _state.value.copy(loading = false)
                        if (s == Player.STATE_READY && System.currentTimeMillis() - lastRetryAt > 60_000) retries = 0
                        if (s == Player.STATE_ENDED) {
                            _state.value.item?.let { val d = p.duration; if (d > 0) History.updatePosition(it.url, d, d) }
                            if (SleepTimer.onVideoEnded()) return
                            offerAutoplay()
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (!isPlaying) savePosition()
                        handler.removeCallbacks(saver); if (isPlaying) handler.postDelayed(saver, 5000)
                    }
                })
            }
    }

    private val saver = object : Runnable {
        override fun run() { savePosition(); if (player.isPlaying) handler.postDelayed(this, 5000) }
    }

    private val recoverGo = Runnable { refetch() }

    fun init(c: Context) {
        app = c.applicationContext
        NetworkWatch(app) { recoverIfNeeded() }.start()
        SleepTimer.init { player.pause() }
    }

    fun recoverIfNeeded() {
        val st = _state.value
        val idle = player.playbackState == Player.STATE_IDLE
        if (!StreamRecovery.shouldRefetchOnNetworkRestore(st.item != null, offline, st.error != null, player.playWhenReady, idle)) return
        retries = 0
        refetch()
    }

    // ---- previous / next (old-app buttons) ----
    /** Videos watched before the current one in this session (for ⏮). */
    private val back = ArrayList<VideoItem>()
    private var goingBack = false
    fun hasPrev() = back.isNotEmpty()
    fun relatedItem(): VideoItem? =
        _state.value.info?.relatedItems?.videosOnly()?.firstOrNull { it.unplayableBadge() == null && !it.live && !it.upcoming }

    /** Up next = user/playlist queue, else the first playable related video. */
    fun nextItem(): VideoItem? = if (offline) null else PlayQueue.peekNext() ?: relatedItem()

    fun next() { cancelAutoplay(); nextItem()?.let { open(it) } }

    /** ⏮: restart the video if it has played a while, else go back to the previous one. */
    fun prev() {
        cancelAutoplay()
        if (PlaybackNav.shouldRestart(player.currentPosition, back.isNotEmpty())) { player.seekTo(0); return }
        goingBack = true
        try { open(back.removeAt(back.size - 1)) } finally { goingBack = false }
    }

    fun cancelAutoplay() {
        handler.removeCallbacks(autoplayGo)
        _autoplay.value = null
    }

    fun confirmAutoplay() {
        val n = _autoplay.value?.next
        cancelAutoplay()
        n?.let { open(it) }
    }

    private val autoplayGo = Runnable {
        val n = _autoplay.value?.next
        _autoplay.value = null
        n?.let { open(it) }
    }

    private fun offerAutoplay() {
        cancelAutoplay()
        val next = Autoplay.nextAfterEnd(Prefs.autoplay, offline, live, PlayQueue.peekNext(), relatedItem()) ?: return
        _autoplay.value = Autoplay.Offer(next, System.currentTimeMillis())
        handler.postDelayed(autoplayGo, Autoplay.DELAY_MS)
    }

    private var retries = 0
    private var lastRetryAt = 0L
    private var resumeAt = 0L

    /** Re-extract the video (fresh URLs) and continue at the same position. */
    private fun refetch() {
        val item = _state.value.item ?: return
        resumeAt = if (live) 0 else player.currentPosition.coerceAtLeast(0)
        val label = qualities.getOrNull(current)?.label
        val attempt = retries
        loadJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching { ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java)) }
        loadJob = scope.launch {
            try {
                val info = Repo.stream(item.url)
                qualities = buildQualities(info)
                if (qualities.isEmpty()) throw ContentNotAvailableException("no streams")
                var idx = qualities.indexOfFirst { it.label == label }
                // second attempt: leave adaptive (DASH) mode for a plain progressive stream
                if (idx < 0 || (attempt >= 2 && qualities[idx].height == AUTO)) idx = defaultIndex(progressiveOnly = attempt >= 2)
                _state.value = _state.value.copy(info = info)
                play(idx, resumeAt)
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(loading = false, error = Errors.video(e, item))
            }
        }
    }

    const val AUTO = Int.MAX_VALUE
    private fun metered(): Boolean = runCatching {
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
    }.getOrDefault(false)

    private fun defaultIndex(progressiveOnly: Boolean = false): Int {
        // Settings > การเล่น: default height on Wi-Fi / mobile data (0 = อัตโนมัติ)
        val want = if (metered()) Prefs.qualityMobile else Prefs.qualityWifi
        if (want > 0) {
            qualities.indexOfLast { it.height in 1..want }.takeIf { it >= 0 }?.let { return it }
            qualities.indexOfFirst { it.height in 1 until AUTO }.takeIf { it >= 0 }?.let { return it }
        }
        if (!progressiveOnly) qualities.indexOfFirst { it.height == AUTO }.takeIf { it >= 0 }?.let { return it }
        return qualities.indexOfLast { it.height in 1..720 }.takeIf { it >= 0 } ?: 0
    }

    private fun savePosition() {
        val it = _state.value.item ?: return
        if (live || qualities.isEmpty() || player.playbackState == Player.STATE_ENDED) return
        val pos = player.currentPosition
        if (pos > 0) History.updatePosition(it.url, pos, player.duration.coerceAtLeast(0))
    }

    /** Start playing [item]; does nothing if it is already the current video. */
    fun open(item: VideoItem) {
        cancelAutoplay()
        PlayQueue.onOpen(item.url)
        if (_state.value.item?.url == item.url && _state.value.error == null) return
        _state.value.item?.let { if (it.url != item.url && !goingBack) { back.add(it); if (back.size > 50) back.removeAt(0) } }
        savePosition()
        loadJob?.cancel()
        retries = 0; offline = false; offlineFile = null
        player.stop(); player.clearMediaItems()
        qualities = emptyList(); current = -1; subtitles = emptyList(); audioTracks = emptyList(); currentAudio = null; live = false
        _state.value = State(item = item, loading = true)
        History.record(item)
        runCatching { ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java)) }
        loadJob = scope.launch {
            try {
                val info = Repo.stream(item.url)
                live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
                val short = item.shortForm || info.isShortFormContent || item.url.contains("/shorts/")
                val full = item.copy(
                    title = info.name.ifBlank { item.title }, channel = info.uploaderName ?: item.channel,
                    channelUrl = info.uploaderUrl ?: item.channelUrl, avatar = info.uploaderAvatars.pick(88) ?: item.avatar,
                    thumb = item.thumb ?: info.thumbnails.pick(360), durationSec = if (info.duration > 0) info.duration else item.durationSec,
                    live = live, shortForm = short,
                )
                withContext(Dispatchers.IO) { History.updateMeta(item.url, full.channelUrl, full.avatar, full.durationSec, full.title, shortForm = short) }
                qualities = buildQualities(info)
                // the extractor now lists only TTML links; YouTube serves the same captions as WebVTT with fmt=vtt
                subtitles = info.subtitles.filter { it.isUrl && (it.format == MediaFormat.VTT || (it.format == MediaFormat.TTML && it.content.contains("fmt=ttml"))) }
                    .distinctBy { it.languageTag + it.isAutoGenerated }.sortedBy { it.isAutoGenerated }
                _state.value = State(item = full, info = info, loading = qualities.isNotEmpty())
                if (qualities.isEmpty()) {
                    val future = info.uploadDate?.offsetDateTime()?.isAfter(OffsetDateTime.now()) == true
                    val it2 = if (future) full.copy(upcoming = true, scheduledEpochMs = info.uploadDate!!.instant.toEpochMilli()) else full
                    _state.value = _state.value.copy(loading = false, error = Errors.video(ContentNotAvailableException("no streams"), it2))
                    app.stopService(Intent(app, PlaybackService::class.java))
                    return@launch
                }
                Availability.clear(item.url)
                val def = defaultIndex()
                val saved = if (live) 0 else History.position(item.url)
                val durMs = full.durationSec * 1000
                play(def, WatchProgress.resumeAt(saved, durMs))
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(loading = false, error = Errors.video(e, item))
                if (!StreamRecovery.isNetworkFailure(e)) app.stopService(Intent(app, PlaybackService::class.java))
                onUnavailable(item, Availability.kindFromError(e))
            }
        }
    }

    /** Remember a deleted / private video; while playing a playlist, move on to the next one. */
    private fun onUnavailable(item: VideoItem, kind: Int) {
        if (kind == Availability.OK) return
        Availability.mark(item.url, kind)
        if (PlayQueue.active && PlayQueue.peekNext() != null) handler.postDelayed({
            if (_state.value.item?.url == item.url && _state.value.error != null) next()
        }, 1500)
    }

    fun play(index: Int, positionMs: Long) {
        val st = _state.value
        val item = st.item ?: return
        current = index
        _state.value = st.copy(loading = true, error = null)
        val tb = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (Prefs.ccEnabled) {
            tb.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            Prefs.ccLanguage.trim().takeIf { it.isNotEmpty() }?.let { tb.setPreferredTextLanguage(it) }
        } else tb.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        player.trackSelectionParameters = tb.build()
        val main = qualities[index].build()
        val subs = if (live) emptyList() else subtitles.map { s ->
            val name = runCatching { Locale.forLanguageTag(s.languageTag).getDisplayName(Locale("th")) }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: s.displayLanguageName
            val url = if (s.format == MediaFormat.TTML) s.content.replace("fmt=ttml", "fmt=vtt") else s.content
            val cfg = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(url))
                .setMimeType(MimeTypes.TEXT_VTT).setLanguage(s.languageTag)
                .setLabel(if (s.isAutoGenerated) "$name (สร้างอัตโนมัติ)" else name).build()
            SingleSampleMediaSource.Factory(dsFactory).createMediaSource(cfg, C.TIME_UNSET)
        }
        player.setMediaSource(if (subs.isEmpty()) main else MergingMediaSource(true, main, *subs.toTypedArray()), positionMs)
        player.playlistMetadata = MediaMetadata.Builder().setTitle(item.title).setArtist(item.channel).build()
        player.prepare()
        player.playWhenReady = true
    }

    fun retry() {
        val f = offlineFile
        _state.value.item?.let { if (offline && f != null) openFile(it, f) else { _state.value = _state.value.copy(error = "x"); open(it) } }
    }

    /** Play a downloaded file (คลังวิดีโอ > การดาวน์โหลด) with the same player, offline. */
    fun openFile(item: VideoItem, file: java.io.File) {
        savePosition()
        loadJob?.cancel()
        retries = 0
        player.stop(); player.clearMediaItems()
        offline = true; offlineFile = file
        live = false; subtitles = emptyList(); audioTracks = emptyList(); currentAudio = null
        cancelAutoplay()
        val factory = ProgressiveMediaSource.Factory(com.google.android.exoplayer2.upstream.DefaultDataSource.Factory(app))
        qualities = listOf(Quality("ไฟล์ที่ดาวน์โหลด", 0) { factory.createMediaSource(MediaItem.fromUri(android.net.Uri.fromFile(file))) })
        current = 0
        _state.value = State(item = item, loading = true)
        History.record(item)
        runCatching { ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java)) }
        val saved = History.position(item.url)
        val durMs = item.durationSec * 1000
        play(0, WatchProgress.resumeAt(saved, durMs))
    }

    fun close() {
        SleepTimer.clear()
        cancelAutoplay()
        savePosition()
        back.clear()
        PlayQueue.clear()
        offline = false; offlineFile = null
        audioTracks = emptyList(); currentAudio = null
        loadJob?.cancel()
        player.stop(); player.clearMediaItems()
        qualities = emptyList(); current = -1
        _state.value = State()
        app.stopService(Intent(app, PlaybackService::class.java))
    }

    /** Called when the whole app goes to / comes back from the background (home, screen off). */
    fun onAppVisible(visible: Boolean) {
        if (visible == appVisible) return
        appVisible = visible
        if (_state.value.item == null) return
        if (!visible && !Prefs.background) { player.pause(); return }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !visible).build()
    }

    fun setAudioTrack(id: String) {
        val track = audioTracks.firstOrNull { it.id == id } ?: return
        if (track.id == currentAudio?.id) return
        currentAudio = track
        track.language?.let { Prefs.audioLanguage = it }
        val info = _state.value.info ?: return
        val pos = player.currentPosition.coerceAtLeast(0)
        val label = qualities.getOrNull(current)?.label
        qualities = buildQualities(info)
        if (qualities.isEmpty()) return
        var idx = qualities.indexOfFirst { it.label == label }
        if (idx < 0) idx = defaultIndex()
        play(idx, pos)
    }

    private fun buildQualities(info: StreamInfo): List<Quality> {
        val r = StreamSources.build(info, dsFactory, currentAudio, Prefs.audioLanguage)
        audioTracks = r.audioTracks
        currentAudio = r.currentAudio
        return r.qualities
    }
}
