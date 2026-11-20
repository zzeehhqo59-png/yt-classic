package com.bear.ytclassic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.exoplayer2.upstream.DataSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

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

    /** Recover rows left RUNNING by a killed process. */
    fun fixInterrupted() { Db.w.execSQL("UPDATE downloads SET status=$PAUSED WHERE status IN ($RUNNING,$MERGING)") }

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
            val buf = ByteBuffer.allocate(8 * 1024 * 1024)
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
                Toast.makeText(c, "เริ่มดาวน์โหลดแล้ว ดูได้ที่ คลังวิดีโอ > การดาวน์โหลด", Toast.LENGTH_SHORT).show()
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


/** Foreground service that downloads the queue one item at a time, with a progress notification (pause / resume / cancel). */
class DownloadService : Service() {
    companion object {
        const val ACTION_START = "start"; const val ACTION_PAUSE = "pause"; const val ACTION_CANCEL = "cancel"
        private const val CH = "downloads"
        private const val FG_ID = 2000
        fun send(c: Context, action: String, id: Long) {
            val i = Intent(c, DownloadService::class.java).setAction(action).putExtra("id", id)
            if (action == ACTION_START) ContextCompat.startForegroundService(c, i) else c.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var currentId = -1L
    private var stopAs = -1   // status to leave the current item in when its job is cancelled; -2 = deleted
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "การดาวน์โหลด", NotificationManager.IMPORTANCE_LOW))
        Downloads.fixInterrupted()
    }

    private fun goForeground(text: String) {
        val n = NotificationCompat.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(text).setSilent(true)
            .setProgress(0, 0, true).build()
        ServiceCompat.startForeground(this, FG_ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra("id", -1) ?: -1
        when (intent?.action) {
            ACTION_START -> { goForeground("กำลังเตรียมดาวน์โหลด…"); if (id > 0) Downloads.setStatus(id, Downloads.QUEUED) }
            ACTION_PAUSE -> if (id == currentId) { stopAs = Downloads.PAUSED; job?.cancel() } else if (id > 0) Downloads.setStatus(id, Downloads.PAUSED).also { showPaused(id) }
            ACTION_CANCEL -> if (id == currentId) { stopAs = -2; job?.cancel() } else if (id > 0) { Downloads.delete(this, id); nm.cancel(FG_ID + id.toInt()) }
        }
        pump()
        return START_NOT_STICKY
    }

    private fun pump() {
        if (job?.isActive == true) return
        val next = Downloads.next()
        if (next == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(); return
        }
        goForeground(next.title)
        currentId = next.id; stopAs = -1
        val j = scope.launch { run(next) }
        job = j
        j.invokeOnCompletion { main.post { if (job === j) { currentId = -1; pump() } } }
    }

    private fun pi(action: String, id: Long): PendingIntent = PendingIntent.getService(this, (id * 10 + action.length).toInt(),
        Intent(this, DownloadService::class.java).setAction(action).putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("tab", "library")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun progressNotif(d: Downloads.Dl, done: Long, total: Long, merging: Boolean) {
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val b = NotificationCompat.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(d.title).setSilent(true).setOngoing(true)
            .setContentIntent(openApp())
            .setContentText(if (merging) "กำลังรวมวิดีโอและเสียง…" else "$pct% · ${Downloads.mb(done)} / ${Downloads.mb(total)} · ${d.quality}")
            .setProgress(100, pct, merging || total <= 0)
        if (!merging) b.addAction(0, "หยุดชั่วคราว", pi(ACTION_PAUSE, d.id))
        b.addAction(0, "ยกเลิก", pi(ACTION_CANCEL, d.id))
        nm.notify(FG_ID, b.build())
    }

    private fun showPaused(id: Long) {
        val d = Downloads.get(id) ?: return
        nm.notify(FG_ID + id.toInt(), NotificationCompat.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(d.title)
            .setContentText("หยุดชั่วคราว ${d.percent}%").setSilent(true).setContentIntent(openApp()).setAutoCancel(true)
            .addAction(0, "ดาวน์โหลดต่อ", pi(ACTION_START, id)).addAction(0, "ยกเลิก", pi(ACTION_CANCEL, id)).build())
    }

    private fun finished(d: Downloads.Dl, ok: Boolean, msg: String) {
        nm.notify(FG_ID + d.id.toInt(), NotificationCompat.Builder(this, CH)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(d.title).setContentText(msg).setContentIntent(openApp()).setAutoCancel(true).build())
    }

    private suspend fun run(d0: Downloads.Dl) {
        val ctx = applicationContext
        nm.cancel(FG_ID + d0.id.toInt())
        Downloads.setStatus(d0.id, Downloads.RUNNING)
        val (vPart, aPart) = Downloads.parts(ctx, d0.id)
        try {
            var attempt = 0
            while (true) {
                try {
                    val info = Repo.stream(d0.url)
                    val audioOnly = d0.audioOnly
                    val pick = if (audioOnly) null else Downloads.pick(info, d0.height) ?: throw IllegalStateException("ไม่มีไฟล์คุณภาพนี้แล้ว")
                    val audio = if (audioOnly) Downloads.bestAudio(info) ?: throw IllegalStateException("ไม่มีไฟล์เสียง") else pick!!.second
                    val jobs = buildList {
                        if (pick != null) add(pick.first.content to vPart)
                        if (audio != null) add(audio.content to aPart)
                    }
                    val sizes = jobs.map { (u, f) -> contentLength(u, if (f == vPart) pick?.first?.itagItem?.contentLength else audio?.itagItem?.contentLength) }
                    val total = sizes.sum()
                    Downloads.update(d0.id) { put("total_bytes", total) }
                    var base = 0L
                    jobs.forEachIndexed { i, (u, f) ->
                        fetch(u, f, sizes[i]) { got -> report(d0, base + got, total) }
                        base += sizes[i]
                    }
                    // merge / remux into the final file
                    Downloads.update(d0.id) { put("status", Downloads.MERGING); put("done_bytes", total) }
                    progressNotif(d0, total, total, merging = true)
                    val ext = if (audioOnly) "m4a" else "mp4"
                    val out = unique(Downloads.dir(ctx), safeName(d0.title) + (if (audioOnly) "" else " (${d0.height}p)"), ext)
                    val inputs = jobs.map { it.second }
                    val muxOk = runCatching { Downloads.mux(inputs, out) }.isSuccess
                    if (!muxOk) {
                        out.delete()
                        if (inputs.size == 1) inputs[0].copyTo(out, overwrite = true) else throw IllegalStateException("รวมวิดีโอกับเสียงไม่สำเร็จ")
                    }
                    inputs.forEach { it.delete() }
                    Downloads.update(d0.id) { put("status", Downloads.DONE); put("file", out.path); put("total_bytes", out.length()); put("done_bytes", out.length()); put("error", null as String?) }
                    finished(d0, true, "ดาวน์โหลดเสร็จแล้ว · ${d0.quality} · ${Downloads.mb(out.length())}")
                    return
                } catch (e: CancellationException) { throw e
                } catch (e: Throwable) {
                    // links expire / get refused: fetch fresh ones and continue where we stopped
                    if (++attempt >= 3) throw e
                    kotlinx.coroutines.delay(1500L * attempt)
                }
            }
        } catch (e: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) {
                if (stopAs == -2) { Downloads.delete(ctx, d0.id); nm.cancel(FG_ID + d0.id.toInt()) }
                else { Downloads.setStatus(d0.id, Downloads.PAUSED); main.post { showPaused(d0.id) } }
            }
        } catch (e: Throwable) {
            val msg = when (e) {
                is java.io.IOException -> "การเชื่อมต่อขัดข้อง"
                else -> e.message?.takeIf { it.isNotBlank() && it.any { c -> c in '\u0E00'..'\u0E7F' } } ?: "ดาวน์โหลดไม่สำเร็จ"
            }
            Downloads.setStatus(d0.id, Downloads.FAILED, msg)
            finished(d0, false, "ดาวน์โหลดไม่สำเร็จ: $msg")
        }
    }

    private var lastReport = 0L
    private fun report(d: Downloads.Dl, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastReport < 500 && done < total) return
        lastReport = now
        Downloads.update(d.id) { put("done_bytes", done) }
        progressNotif(d, done, total, merging = false)
    }

    private val ds by lazy { YtDataSource(DownloaderImpl.instance.client) }

    /** Size of a stream: from the extractor if known, else from a 1-byte ranged request. */
    private fun contentLength(url: String, known: Long?): Long {
        if (known != null && known > 0) return known
        val src = YtDataSource(DownloaderImpl.instance.client)
        try {
            src.open(DataSpec.Builder().setUri(Uri.parse(url)).setPosition(0).setLength(1).build())
            val cr = src.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
            return cr?.substringAfter('/')?.trim()?.toLongOrNull() ?: throw java.io.IOException("no length")
        } finally { src.close() }
    }

    /** Appends to [f] from its current size in 2 MB ranged requests (resumable, avoids YouTube throttling of long requests). */
    private suspend fun fetch(url: String, f: File, total: Long, progress: (Long) -> Unit) {
        val chunk = 2L * 1024 * 1024
        RandomAccessFile(f, "rw").use { raf ->
            var pos = raf.length().coerceAtMost(total)
            raf.seek(pos)
            val buf = ByteArray(64 * 1024)
            while (pos < total) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val len = minOf(chunk, total - pos)
                ds.open(DataSpec.Builder().setUri(Uri.parse(url)).setPosition(pos).setLength(len).build())
                try {
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val n = ds.read(buf, 0, buf.size)
                        if (n < 0) break
                        raf.write(buf, 0, n); pos += n
                        progress(pos)
                    }
                } finally { ds.close() }
            }
        }
    }

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").trim().take(80).ifBlank { "video" }
    private fun unique(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext"); var i = 2
        while (f.exists()) { f = File(dir, "$base ($i).$ext"); i++ }
        return f
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }
}
