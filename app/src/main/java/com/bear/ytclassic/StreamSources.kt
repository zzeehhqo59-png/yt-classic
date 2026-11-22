package com.bear.ytclassic

import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.util.MimeTypes
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/** Builds ExoPlayer media sources for a stream (extracted from Playback so that file stays small). */
object StreamSources {
    data class Result(
        val qualities: List<Playback.Quality>,
        val audioTracks: List<AudioTracks.Track>,
        val currentAudio: AudioTracks.Track?,
    )

    fun build(
        info: StreamInfo,
        dsFactory: DataSource.Factory,
        currentAudio: AudioTracks.Track?,
        preferredLang: String,
    ): Result {
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        val hls = info.hlsUrl.orEmpty()
        val dash = info.dashMpdUrl.orEmpty()
        val out = ArrayList<Playback.Quality>()
        if (live || (info.videoStreams.isEmpty() && info.videoOnlyStreams.isEmpty())) {
            if (hls.isNotEmpty()) out.add(Playback.Quality("อัตโนมัติ", 0) { HlsMediaSource.Factory(dsFactory).createMediaSource(MediaItem.fromUri(hls)) })
            else if (dash.isNotEmpty()) out.add(Playback.Quality("อัตโนมัติ", 0) { DashMediaSource.Factory(dsFactory).createMediaSource(MediaItem.fromUri(dash)) })
            if (live) return Result(out, emptyList(), currentAudio)
        }
        val prog = ProgressiveMediaSource.Factory(dsFactory)
        fun ok(s: org.schabi.newpipe.extractor.stream.Stream) = s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        val allAudio = info.audioStreams.filter { ok(it) }
        val audioTracks = AudioTracks.groupStreams(allAudio)
        var chosen = currentAudio
        if (chosen == null || audioTracks.none { it.id == chosen?.id }) {
            chosen = AudioTracks.pick(audioTracks, preferredLang)
        }
        val audios = AudioTracks.filter(allAudio, chosen).ifEmpty { allAudio }
        val byHeight = LinkedHashMap<Int, Playback.Quality>()
        val videoOnly = info.videoOnlyStreams.filter { ok(it) && it.height > 0 }
        if (audios.isNotEmpty()) {
            videoOnly.groupBy { it.height }.forEach { (h, list) ->
                val v: VideoStream = list.firstOrNull { it.format == MediaFormat.MPEG_4 && (it.codec ?: "").startsWith("avc") }
                    ?: list.firstOrNull { it.format == MediaFormat.MPEG_4 } ?: list.first()
                val a = bestAudio(audios, v.format == MediaFormat.WEBM) ?: return@forEach
                val label = "${h}p" + if (v.fps > 30) "${v.fps}" else ""
                byHeight[h] = Playback.Quality(label, h) {
                    MergingMediaSource(true, prog.createMediaSource(MediaItem.fromUri(v.content)), prog.createMediaSource(MediaItem.fromUri(a.content)))
                }
            }
        }
        info.videoStreams.filter { ok(it) }.forEach { v ->
            val h = v.height.takeIf { it > 0 } ?: v.resolution.filter { c -> c.isDigit() }.take(4).toIntOrNull() ?: 0
            if (!byHeight.containsKey(h)) byHeight[h] = Playback.Quality(if (h > 0) "${h}p" else v.resolution, h) { prog.createMediaSource(MediaItem.fromUri(v.content)) }
        }
        out.addAll(byHeight.values.sortedBy { it.height })
        if (audios.isNotEmpty()) runCatching { adaptive(info, videoOnly, audios, dsFactory) }.getOrNull()?.let { out.add(it) }
        if (out.isEmpty() && hls.isNotEmpty()) out.add(Playback.Quality("อัตโนมัติ", 0) { HlsMediaSource.Factory(dsFactory).createMediaSource(MediaItem.fromUri(hls)) })
        return Result(out, audioTracks, chosen)
    }

    /**
     * "อัตโนมัติ": one DASH manifest holding every H.264 video height + the best AAC audio,
     * so ExoPlayer picks (and switches) the height by measured bandwidth.
     */
    private fun adaptive(info: StreamInfo, videoOnly: List<VideoStream>, audios: List<AudioStream>, dsFactory: DataSource.Factory): Playback.Quality? {
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
        return Playback.Quality("อัตโนมัติ", Playback.AUTO) {
            DashMediaSource.Factory(dsFactory).createMediaSource(manifest,
                MediaItem.Builder().setUri(info.url).setMimeType(MimeTypes.APPLICATION_MPD).build())
        }
    }

    private fun bestAudio(audios: List<AudioStream>, forWebm: Boolean): AudioStream? {
        val pref = audios.filter { if (forWebm) it.format == MediaFormat.WEBMA || it.format == MediaFormat.WEBMA_OPUS else it.format == MediaFormat.M4A }
        return (pref.ifEmpty { audios }).maxByOrNull { if (it.averageBitrate > 0) it.averageBitrate else it.bitrate }
    }

}
