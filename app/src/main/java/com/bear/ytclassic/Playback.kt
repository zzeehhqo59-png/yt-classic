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
import org.schabi.newpipe.extractor.stream.AudioTrackType
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
    private var loadJob: Job? = null
    private val dsFactory by lazy { YtDataSource.Factory(DownloaderImpl.instance.client) }
    private var appVisible = true

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
                        // stream URLs expire / get throttled (typically after the app sat in the background): fetch fresh ones and continue
                        val sourceError = error.errorCode in 2000..3999
                        if (sourceError && retries < 2 && _state.value.item != null) { retries++; lastRetryAt = System.currentTimeMillis(); refetch(); return }
                        _state.value = _state.value.copy(loading = false, error = "เล่นวิดีโอไม่ได้ ลองเลือกคุณภาพอื่น\n(${error.errorCodeName})")
                    }
                    override fun onPlaybackStateChanged(s: Int) {
                        if (s == Player.STATE_READY && _state.value.loading) _state.value = _state.value.copy(loading = false)
                        if (s == Player.STATE_READY && System.currentTimeMillis() - lastRetryAt > 60_000) retries = 0
                        if (s == Player.STATE_ENDED) _state.value.item?.let { val d = p.duration; if (d > 0) History.updatePosition(it.url, d, d) }
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

    fun init(c: Context) { app = c.applicationContext }

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
    private fun defaultIndex(progressiveOnly: Boolean = false): Int {
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
        if (_state.value.item?.url == item.url && _state.value.error == null) return
        savePosition()
        loadJob?.cancel()
        retries = 0
        player.stop(); player.clearMediaItems()
        qualities = emptyList(); current = -1; subtitles = emptyList(); live = false
        _state.value = State(item = item, loading = true)
        History.record(item)
        runCatching { ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java)) }
        loadJob = scope.launch {
            try {
                val info = Repo.stream(item.url)
                live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
                val full = item.copy(
                    title = info.name.ifBlank { item.title }, channel = info.uploaderName ?: item.channel,
                    channelUrl = info.uploaderUrl ?: item.channelUrl, avatar = info.uploaderAvatars.pick(88) ?: item.avatar,
                    thumb = item.thumb ?: info.thumbnails.pick(360), durationSec = if (info.duration > 0) info.duration else item.durationSec,
                    live = live,
                )
                withContext(Dispatchers.IO) { History.updateMeta(item.url, full.channelUrl, full.avatar, full.durationSec, full.title) }
                qualities = buildQualities(info)
                subtitles = info.subtitles.filter { it.isUrl && it.format == MediaFormat.VTT }
                    .distinctBy { it.languageTag + it.isAutoGenerated }.sortedBy { it.isAutoGenerated }
                _state.value = State(item = full, info = info, loading = qualities.isNotEmpty())
                if (qualities.isEmpty()) {
                    val future = info.uploadDate?.offsetDateTime()?.isAfter(OffsetDateTime.now()) == true
                    val it2 = if (future) full.copy(upcoming = true, scheduledEpochMs = info.uploadDate!!.instant.toEpochMilli()) else full
                    _state.value = _state.value.copy(loading = false, error = Errors.video(ContentNotAvailableException("no streams"), it2))
                    app.stopService(Intent(app, PlaybackService::class.java))
                    return@launch
                }
                val def = defaultIndex()
                val saved = if (live) 0 else History.position(item.url)
                val durMs = full.durationSec * 1000
                val start = if (saved > 5000 && (durMs <= 0 || saved < durMs - 15_000)) saved else 0
                play(def, start)
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(loading = false, error = Errors.video(e, item))
                app.stopService(Intent(app, PlaybackService::class.java))
            }
        }
    }

    fun play(index: Int, positionMs: Long) {
        val st = _state.value
        val item = st.item ?: return
        current = index
        _state.value = st.copy(loading = true, error = null)
        val textLang = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setPreferredTextLanguage(textLang).build()
        val main = qualities[index].build()
        val subs = if (live) emptyList() else subtitles.map { s ->
            val name = runCatching { Locale.forLanguageTag(s.languageTag).getDisplayName(Locale("th")) }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: s.displayLanguageName
            val cfg = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s.content))
                .setMimeType(MimeTypes.TEXT_VTT).setLanguage(s.languageTag)
                .setLabel(if (s.isAutoGenerated) "$name (สร้างอัตโนมัติ)" else name).build()
            SingleSampleMediaSource.Factory(dsFactory).createMediaSource(cfg, C.TIME_UNSET)
        }
        player.setMediaSource(if (subs.isEmpty()) main else MergingMediaSource(true, main, *subs.toTypedArray()), positionMs)
        player.playlistMetadata = MediaMetadata.Builder().setTitle(item.title).setArtist(item.channel).build()
        player.prepare()
        player.playWhenReady = true
    }

    fun retry() { _state.value.item?.let { _state.value = _state.value.copy(error = "x"); open(it) } }

    fun close() {
        savePosition()
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

    /**
     * "อัตโนมัติ": one DASH manifest holding every H.264 video height + the best AAC audio,
     * so ExoPlayer picks (and switches) the height by measured bandwidth.
     */
    private fun adaptive(info: StreamInfo, videoOnly: List<VideoStream>, audios: List<AudioStream>): Quality? {
        val durS = info.duration.takeIf { it > 0 } ?: return null
        fun indexed(s: org.schabi.newpipe.extractor.stream.Stream) = s.itagItem?.let { it.initEnd > 0 && it.indexEnd > it.indexStart && it.indexStart > 0 } == true
        val vids = videoOnly.filter { it.format == MediaFormat.MPEG_4 && (it.codec ?: "").startsWith("avc") && indexed(it) && it.height <= 1080 }
            .groupBy { it.height }.map { (_, l) -> l.maxBy { v -> v.itagItem!!.bitrate } }.sortedBy { it.height }
        if (vids.size < 2) return null
        val a = audios.filter { it.format == MediaFormat.M4A && indexed(it) }.maxByOrNull { it.itagItem!!.bitrate } ?: return null
        fun esc(u: String) = u.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun rep(id: String, s: org.schabi.newpipe.extractor.stream.Stream, attrs: String): String {
            val it = s.itagItem!!
            return "<Representation id=\"$id\" bandwidth=\"${it.bitrate.coerceAtLeast(1)}\" codecs=\"${esc(it.codec ?: "")}\" $attrs>" +
                "<BaseURL>${esc(s.content)}</BaseURL><SegmentBase indexRange=\"${it.indexStart}-${it.indexEnd}\">" +
                "<Initialization range=\"${it.initStart}-${it.initEnd}\"/></SegmentBase></Representation>"
        }
        val mpd = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" ")
            append("type=\"static\" minBufferTime=\"PT1.500S\" mediaPresentationDuration=\"PT${durS}S\"><Period>")
            append("<AdaptationSet id=\"0\" contentType=\"video\" mimeType=\"video/mp4\" subsegmentAlignment=\"true\">")
            vids.forEachIndexed { i, v -> append(rep("v$i", v, "width=\"${v.width}\" height=\"${v.height}\"" + if (v.fps > 0) " frameRate=\"${v.fps}\"" else "")) }
            append("</AdaptationSet><AdaptationSet id=\"1\" contentType=\"audio\" mimeType=\"audio/mp4\" subsegmentAlignment=\"true\">")
            append(rep("a0", a, "audioSamplingRate=\"44100\""))
            append("</AdaptationSet></Period></MPD>")
        }
        val manifest = DashManifestParser().parse(android.net.Uri.parse(info.url), java.io.ByteArrayInputStream(mpd.toByteArray()))
        return Quality("อัตโนมัติ", AUTO) {
            DashMediaSource.Factory(dsFactory).createMediaSource(manifest,
                MediaItem.Builder().setUri(info.url).setMimeType(MimeTypes.APPLICATION_MPD).build())
        }
    }

    private fun bestAudio(audios: List<AudioStream>, forWebm: Boolean): AudioStream? {
        val pref = audios.filter { if (forWebm) it.format == MediaFormat.WEBMA || it.format == MediaFormat.WEBMA_OPUS else it.format == MediaFormat.M4A }
        return (pref.ifEmpty { audios }).maxByOrNull { if (it.averageBitrate > 0) it.averageBitrate else it.bitrate }
    }

    private fun buildQualities(info: StreamInfo): List<Quality> {
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        val hls = info.hlsUrl.orEmpty()
        val dash = info.dashMpdUrl.orEmpty()
        val out = ArrayList<Quality>()
        if (live || (info.videoStreams.isEmpty() && info.videoOnlyStreams.isEmpty())) {
            if (hls.isNotEmpty()) out.add(Quality("อัตโนมัติ", 0) { HlsMediaSource.Factory(dsFactory).createMediaSource(MediaItem.fromUri(hls)) })
            else if (dash.isNotEmpty()) out.add(Quality("อัตโนมัติ", 0) { DashMediaSource.Factory(dsFactory).createMediaSource(MediaItem.fromUri(dash)) })
            if (live) return out
        }
        val prog = ProgressiveMediaSource.Factory(dsFactory)
        fun ok(s: org.schabi.newpipe.extractor.stream.Stream) = s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        val allAudio = info.audioStreams.filter { ok(it) }
        val audios = allAudio.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { allAudio }
        val byHeight = LinkedHashMap<Int, Quality>()
        val videoOnly = info.videoOnlyStreams.filter { ok(it) && it.height > 0 }
        if (audios.isNotEmpty()) {
            videoOnly.groupBy { it.height }.forEach { (h, list) ->
                val v: VideoStream = list.firstOrNull { it.format == MediaFormat.MPEG_4 && (it.codec ?: "").startsWith("avc") }
                    ?: list.firstOrNull { it.format == MediaFormat.MPEG_4 } ?: list.first()
                val a = bestAudio(audios, v.format == MediaFormat.WEBM) ?: return@forEach
                val label = "${h}p" + if (v.fps > 30) "${v.fps}" else ""
                byHeight[h] = Quality(label, h) {
                    MergingMediaSource(true, prog.createMediaSource(MediaItem.fromUri(v.content)), prog.createMediaSource(MediaItem.fromUri(a.content)))
                }
            }
        }
        info.videoStreams.filter { ok(it) }.forEach { v ->
            val h = v.height.takeIf { it > 0 } ?: v.resolution.filter { c -> c.isDigit() }.take(4).toIntOrNull() ?: 0
            if (!byHeight.containsKey(h)) byHeight[h] = Quality(if (h > 0) "${h}p" else v.resolution, h) { prog.createMediaSource(MediaItem.fromUri(v.content)) }
        }
        out.addAll(byHeight.values.sortedBy { it.height })
        if (audios.isNotEmpty()) runCatching { adaptive(info, videoOnly, audios) }.getOrNull()?.let { out.add(it) }
        if (out.isEmpty() && hls.isNotEmpty()) out.add(Quality("อัตโนมัติ", 0) { HlsMediaSource.Factory(dsFactory).createMediaSource(MediaItem.fromUri(hls)) })
        return out
    }
}
