package com.bear.ytclassic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.exoplayer2.upstream.DataSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

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
                    // same name on retry: overwrite this download's own partial file instead of making "(2)"
                    val nameBase = DeviceSave.safeName(d0.title) + (if (audioOnly) "" else " (${d0.height}p)")
                    val target = File(Downloads.dir(ctx), "$nameBase.$ext")
                    val taken = Downloads.all().any { it.id != d0.id && it.file == target.path }
                    val out = if (taken) unique(Downloads.dir(ctx), nameBase, ext) else target
                    Downloads.get(d0.id)?.file?.let { old -> if (old != out.path) DeviceSave.removeStored(ctx, old) }
                    out.delete()
                    Downloads.update(d0.id) { put("file", out.path) }
                    val inputs = jobs.map { it.second }
                    val muxOk = runCatching { Downloads.mux(inputs, out) }.isSuccess
                    if (!muxOk) {
                        out.delete()
                        if (inputs.size == 1) inputs[0].copyTo(out, overwrite = true) else throw IllegalStateException("รวมวิดีโอกับเสียงไม่สำเร็จ")
                    }
                    inputs.forEach { it.delete() }
                    val size = out.length()
                    var stored = out.path
                    if (Prefs.saveToDevice) {
                        val publicTaken = DeviceSave.takenNames(ctx, audioOnly)
                        val fileName = DeviceSave.displayName(d0.title, audioOnly, d0.height, publicTaken)
                        val uri = DeviceSave.publishNamed(ctx, out, DeviceSave.Meta(d0.title, d0.channel, audioOnly, d0.durationSec * 1000, publicTaken), fileName)
                        if (uri != null) { out.delete(); stored = uri.toString() }
                    }
                    Downloads.update(d0.id) { put("status", Downloads.DONE); put("file", stored); put("total_bytes", size); put("done_bytes", size); put("error", null as String?) }
                    finished(d0, true, "ดาวน์โหลดเสร็จแล้ว · ${d0.quality} · ${Downloads.mb(size)}")
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

    private fun unique(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext"); var i = 2
        while (f.exists()) { f = File(dir, "$base ($i).$ext"); i++ }
        return f
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }
}
