package com.bear.ytclassic

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.exoplayer2.C
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
            .build().also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        savePosition()
                        _state.value = _state.value.copy(loading = false, error = "เล่นวิดีโอไม่ได้ ลองเลือกคุณภาพอื่น\n(${error.errorCodeName})")
                    }
                    override fun onPlaybackStateChanged(s: Int) {
                        if (s == Player.STATE_READY && _state.value.loading) _state.value = _state.value.copy(loading = false)
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
                val def = qualities.indexOfLast { it.height in 1..720 }.takeIf { it >= 0 } ?: 0
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
        if (out.isEmpty() && hls.isNotEmpty()) out.add(Quality("อัตโนมัติ", 0) { HlsMediaSource.Factory(dsFactory).createMediaSource(MediaItem.fromUri(hls)) })
        return out
    }
}
