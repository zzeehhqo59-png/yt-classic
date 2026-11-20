package com.bear.ytclassic

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.os.Environment
import android.widget.Toast
import java.nio.ByteBuffer
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File

/** Downloaded / downloading videos (table "downloads"), files in app storage: Android/data/<app>/files/Download/YT Classic. */
object Downloads {
    const val QUEUED = 0; const val RUNNING = 1; const val PAUSED = 2; const val DONE = 3; const val FAILED = 4; const val MERGING = 5

    data class Dl(
        val id: Long, val url: String, val title: String, val channel: String, val thumb: String?, val durationSec: Long,
        val audioOnly: Boolean, val height: Int, val status: Int, val done: Long, val total: Long, val file: String?, val error: String?,
    ) {
        val percent get() = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        val quality get() = if (audioOnly) "เสียง m4a" else "${height}p"
        fun video() = VideoItem(url = url, title = title, channel = channel, channelUrl = null, thumb = thumb, avatar = null, durationSec = durationSec,
            views = -1, age = "", live = false, upcoming = false, members = false, scheduledEpochMs = 0)
    }

    /** Bumped on every change so lists can refresh. */
    val changes = MutableStateFlow(0L)
    private fun bump() { changes.value = changes.value + 1 }

    fun dir(c: Context): File = File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(c.filesDir, "downloads"), "YT Classic").apply { mkdirs() }
    fun tmpDir(c: Context): File = File(dir(c), ".tmp").apply { mkdirs() }

    private const val COLS = "id,url,title,channel,thumb,duration,audio_only,height,status,done_bytes,total_bytes,file,error"
    private fun read(c: android.database.Cursor) = Dl(c.getLong(0), c.getString(1), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getString(4), c.getLong(5),
        c.getInt(6) != 0, c.getInt(7), c.getInt(8), c.getLong(9), c.getLong(10), c.getString(11), c.getString(12))

    fun all(): List<Dl> = Db.r.rawQuery("SELECT $COLS FROM downloads ORDER BY id DESC", null).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    fun get(id: Long): Dl? = Db.r.rawQuery("SELECT $COLS FROM downloads WHERE id=?", arrayOf("$id")).use { c -> if (c.moveToFirst()) read(c) else null }
    fun next(): Dl? = Db.r.rawQuery("SELECT $COLS FROM downloads WHERE status=$QUEUED ORDER BY id LIMIT 1", null).use { c -> if (c.moveToFirst()) read(c) else null }
    fun active(): Int = Db.r.rawQuery("SELECT COUNT(*) FROM downloads WHERE status IN ($QUEUED,$RUNNING,$MERGING)", null).use { it.moveToFirst(); it.getInt(0) }

    fun add(v: VideoItem, audioOnly: Boolean, height: Int): Long {
        val id = Db.w.insert("downloads", null, ContentValues().apply {
            put("url", v.url); put("title", v.title); put("channel", v.channel); put("thumb", v.thumb); put("duration", v.durationSec)
            put("audio_only", if (audioOnly) 1 else 0); put("height", height); put("status", QUEUED); put("created", System.currentTimeMillis())
        })
        bump(); return id
    }

    fun update(id: Long, notify: Boolean = true, block: ContentValues.() -> Unit) {
        Db.w.update("downloads", ContentValues().apply(block), "id=?", arrayOf("$id")); if (notify) bump()
    }
    fun setStatus(id: Long, s: Int, error: String? = null) = update(id) { put("status", s); put("error", error) }

    fun parts(c: Context, id: Long) = listOf(File(tmpDir(c), "$id.v.part"), File(tmpDir(c), "$id.a.part"))

    /** Removes the entry and its files (finished file too). */
    fun delete(c: Context, id: Long) {
        get(id)?.file?.let { File(it).delete() }
        parts(c, id).forEach { it.delete() }
        Db.w.delete("downloads", "id=?", arrayOf("$id")); bump()
    }

    /** Recover rows left by a killed process: downloading -> paused, merging -> failed (tap to retry, or delete). */
    fun fixInterrupted() {
        Db.w.execSQL("UPDATE downloads SET status=$PAUSED WHERE status=$RUNNING")
        Db.w.execSQL("UPDATE downloads SET status=$FAILED, error='การรวมไฟล์ถูกขัดจังหวะ' WHERE status=$MERGING")
        bump()
    }

    fun mb(b: Long) = if (b <= 0) "0 MB" else String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)

    // ---- choosing streams ----
    private fun prog(s: org.schabi.newpipe.extractor.stream.Stream) = s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
    private fun h(v: VideoStream) = v.height.takeIf { it > 0 } ?: v.resolution.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0

    fun bestAudio(info: StreamInfo): AudioStream? {
        val all = info.audioStreams.filter { prog(it) && it.format == MediaFormat.M4A }
        val orig = all.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { all }
        return orig.maxByOrNull { if (it.averageBitrate > 0) it.averageBitrate else it.bitrate }
    }
    private fun muxed(info: StreamInfo) = info.videoStreams.filter { prog(it) && it.format == MediaFormat.MPEG_4 }
    private fun videoOnly(info: StreamInfo) = info.videoOnlyStreams.filter { prog(it) && it.format == MediaFormat.MPEG_4 && (it.codec ?: "").startsWith("avc") && it.height > 0 }

    /** (video, audio-or-null) to fetch for [height]: a ready-made mp4 when one has that height, else video-only + m4a to merge. */
    fun pick(info: StreamInfo, height: Int): Pair<VideoStream, AudioStream?>? {
        muxed(info).filter { h(it) == height }.firstOrNull()?.let { return it to null }
        val a = bestAudio(info)
        if (a != null) videoOnly(info).filter { it.height == height }.maxByOrNull { it.bitrate }?.let { return it to a }
        return muxed(info).maxByOrNull { h(it) }?.let { it to null }
    }

    data class Option(val label: String, val height: Int, val audio: Boolean)
    fun options(info: StreamInfo): List<Option> {
        val hs = (muxed(info).map { h(it) } + (if (bestAudio(info) != null) videoOnly(info).map { it.height } else emptyList())).filter { it > 0 }.distinct().sortedDescending()
        val audio = bestAudio(info)
        fun size(height: Int): String {
            val p = pick(info, height) ?: return ""
            val b = (p.first.itagItem?.contentLength ?: -1) + (p.second?.itagItem?.contentLength ?: 0)
            return if (b > 0) " · ${mb(b)}" else ""
        }
        return hs.map { Option("วิดีโอ ${it}p" + size(it), it, false) } +
            listOfNotNull(audio?.let { Option("เสียงอย่างเดียว (m4a)" + (it.itagItem?.contentLength?.takeIf { s -> s > 0 }?.let { s -> " · ${mb(s)}" } ?: ""), 0, true) })
    }

    // ---- merging (MediaMuxer) ----
    /** Copies the tracks of [inputs] (fragmented mp4 / m4a from YouTube) into one regular mp4 / m4a, interleaved by time. */
    fun mux(inputs: List<File>, out: File) {
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val exs = inputs.map { f -> MediaExtractor().apply { setDataSource(f.path) } }
        try {
            val tracks = exs.map { ex ->
                val t = (0 until ex.trackCount).first { val m = ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME).orEmpty(); m.startsWith("video/") || m.startsWith("audio/") }
                ex.selectTrack(t)
                muxer.addTrack(ex.getTrackFormat(t))
            }
            muxer.start()
            // direct buffer: with a heap buffer the framework copies the whole array through JNI for every sample (very slow)
            val buf = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val ended = BooleanArray(exs.size)
            while (true) {
                var pickI = -1; var best = Long.MAX_VALUE
                exs.forEachIndexed { i, ex -> if (!ended[i]) { val t = ex.sampleTime; if (t < 0) ended[i] = true else if (t < best) { best = t; pickI = i } } }
                if (pickI < 0) break
                val ex = exs[pickI]
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) { ended[pickI] = true; continue }
                val key = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                info.set(0, n, ex.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(tracks[pickI], buf, info)
                ex.advance()
            }
            muxer.stop()
        } finally {
            exs.forEach { runCatching { it.release() } }
            runCatching { muxer.release() }
        }
    }

    // ---- UI entry point ----
    /** Download button / ⋮ menu: pick quality (or use the defaults from Settings) and queue it. */
    fun start(c: Context, v: VideoItem, known: StreamInfo? = null) {
        if (v.kind != 0 || v.live) { Toast.makeText(c, "ดาวน์โหลดได้เฉพาะวิดีโอทั่วไป (ไม่รวมไลฟ์สด)", Toast.LENGTH_SHORT).show(); return }
        val wait = if (known == null) AlertDialog.Builder(c).setMessage("กำลังโหลดตัวเลือกการดาวน์โหลด…").setCancelable(true).show() else null
        val launcher: CoroutineScope = (c as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope ?: MainScope()
        launcher.launch {
            val info = known ?: runCatching { Repo.stream(v.url) }.getOrNull()
            if (wait != null) { if (!wait.isShowing) return@launch; wait.dismiss() }
            if (info == null) { Toast.makeText(c, "โหลดข้อมูลวิดีโอไม่ได้ ลองอีกครั้ง", Toast.LENGTH_SHORT).show(); return@launch }
            val opts = options(info)
            if (opts.isEmpty()) { Toast.makeText(c, "วิดีโอนี้ดาวน์โหลดไม่ได้", Toast.LENGTH_SHORT).show(); return@launch }
            val item = v.copy(title = info.name.ifBlank { v.title }, channel = info.uploaderName ?: v.channel,
                thumb = v.thumb ?: info.thumbnails.pick(360), durationSec = if (info.duration > 0) info.duration else v.durationSec)
            val videos = opts.filter { !it.audio }
            val def = if (Prefs.dlAudioOnly) opts.indexOfFirst { it.audio }.takeIf { it >= 0 } ?: 0
                else (if (Prefs.dlQuality == 0) 0 else videos.indexOfFirst { it.height <= Prefs.dlQuality }.takeIf { it >= 0 } ?: (videos.size - 1).coerceAtLeast(0))
            fun go(o: Option) {
                val id = add(item, o.audio, o.height)
                DownloadService.send(c, DownloadService.ACTION_START, id)
                Toast.makeText(c, "เริ่มดาวน์โหลดแล้ว ดูได้ที่ แท็บคุณ > การดาวน์โหลด", Toast.LENGTH_SHORT).show()
            }
            if (!Prefs.dlAsk) { go(opts[def]); return@launch }
            var choice = def
            AlertDialog.Builder(c).setTitle("ดาวน์โหลด")
                .setSingleChoiceItems(opts.map { it.label }.toTypedArray(), def) { _, w -> choice = w }
                .setPositiveButton("ดาวน์โหลด") { _, _ -> go(opts[choice]) }
                .setNegativeButton("ยกเลิก", null).show()
        }
    }
}
