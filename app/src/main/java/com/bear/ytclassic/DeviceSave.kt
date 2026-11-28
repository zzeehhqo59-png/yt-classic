package com.bear.ytclassic

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Save finished downloads into the phone's shared storage: audio → Music/YT Classic, video → Movies/YT Classic.
 * On API 29+ this is MediaStore (no permission). Below that it is a public-directory file plus a MediaStore row
 * (needs WRITE_EXTERNAL_STORAGE). Temp / in-progress files always stay in the app-private download folder.
 *
 * Cover art is not embedded: Android's MediaMuxer cannot write iTunes/MP4 metadata (©nam / covr), and this
 * project has no tag library. Music players still get TITLE / ARTIST / ALBUM through MediaStore.
 */
object DeviceSave {
    const val FOLDER = "YT Classic"
    const val ALBUM = "YT Classic"
    const val REQ_WRITE = 6091

    data class Meta(val title: String, val artist: String, val audio: Boolean, val durationMs: Long, val taken: Set<String> = emptySet())

    // literal "Music"/"Movies": Environment.DIRECTORY_* is null under unit-test android.jar stubs
    fun relativePath(audio: Boolean): String = (if (audio) "Music" else "Movies") + "/" + FOLDER

    fun mime(audio: Boolean) = if (audio) "audio/mp4" else "video/mp4"
    fun ext(audio: Boolean) = if (audio) "m4a" else "mp4"
    fun folderLabel(audio: Boolean) = if (audio) "Music/$FOLDER" else "Movies/$FOLDER"

    fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").trim().take(80).ifBlank { "video" }

    fun displayName(title: String, audio: Boolean, height: Int, taken: Set<String>): String {
        val base = safeName(title) + if (audio) "" else " (${height}p)"
        val e = ext(audio)
        val first = "$base.$e"
        if (first !in taken) return first
        var i = 2
        while ("$base ($i).$e" in taken) i++
        return "$base ($i).$e"
    }

    fun isPublic(stored: String?): Boolean = stored.orEmpty().startsWith("content:")

    /** Show "บันทึกลงเครื่อง" only for a finished private file (not already in Music/Movies). */
    fun canExport(status: Int, stored: String?): Boolean = status == Downloads.DONE && !stored.isNullOrBlank() && !isPublic(stored)

    fun playUri(stored: String?): Uri? = when {
        stored.isNullOrBlank() -> null
        stored.startsWith("content:") || stored.startsWith("file:") -> Uri.parse(stored)
        else -> Uri.fromFile(File(stored))
    }

    fun exists(c: Context, stored: String?): Boolean {
        if (stored.isNullOrBlank()) return false
        return if (isPublic(stored)) {
            runCatching { c.contentResolver.openFileDescriptor(Uri.parse(stored), "r")?.use { true } ?: false }.getOrDefault(false)
        } else File(stored).exists()
    }

    fun removeStored(c: Context, stored: String?) {
        if (stored.isNullOrBlank()) return
        if (isPublic(stored)) runCatching { c.contentResolver.delete(Uri.parse(stored), null, null) }
            .onFailure { android.util.Log.w("DeviceSave", "delete public file failed", it) }
        else File(stored).delete()
    }

    fun needsLegacyWrite(): Boolean = Build.VERSION.SDK_INT < 29
    fun hasLegacyWrite(c: Context): Boolean = !needsLegacyWrite() ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    fun canWritePublic(c: Context): Boolean = hasLegacyWrite(c)
    fun requestLegacyWrite(a: Activity) {
        if (needsLegacyWrite()) ActivityCompat.requestPermissions(a, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE)
    }

    /**
     * Copies [src] into Music/ or Movies/YT Classic and returns the content (or file) Uri.
     * Null = could not publish (caller should keep the private file).
     */
    /** Publish [src] under [fileName] (e.g. "Title (720p).mp4"). Null = keep the private file. */
    fun publishNamed(c: Context, src: File, meta: Meta, fileName: String): Uri? {
        if (!src.exists() || src.length() <= 0L || !canWritePublic(c)) return null
        val m = meta.copy(artist = meta.artist.ifBlank { "Unknown" })
        return if (Build.VERSION.SDK_INT >= 29) publishQ(c, src, m, fileName) else publishLegacy(c, src, m, fileName)
    }

    private fun collection(audio: Boolean): Uri = if (Build.VERSION.SDK_INT >= 29) {
        if (audio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        @Suppress("DEPRECATION")
        if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    fun takenNames(c: Context, audio: Boolean): Set<String> {
        val out = HashSet<String>()
        val rel = relativePath(audio)
        if (Build.VERSION.SDK_INT >= 29) {
            val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            c.contentResolver.query(collection(audio), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), sel, arrayOf("$rel%"), null)?.use { cur ->
                while (cur.moveToNext()) cur.getString(0)?.let { out += it }
            }
        } else {
            publicDir(audio).listFiles()?.forEach { out += it.name }
        }
        return out
    }

    private fun values(meta: Meta, fileName: String, pending: Boolean): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, mime(meta.audio))
        put(MediaStore.MediaColumns.TITLE, meta.title.ifBlank { fileName.substringBeforeLast('.') })
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(meta.audio) + "/")
            put(MediaStore.MediaColumns.IS_PENDING, if (pending) 1 else 0)
            if (meta.durationMs > 0) put(MediaStore.MediaColumns.DURATION, meta.durationMs)
        }
        if (meta.audio) {
            put(MediaStore.Audio.Media.ARTIST, meta.artist)
            put(MediaStore.Audio.Media.ALBUM, ALBUM)
            if (meta.durationMs > 0) put(MediaStore.Audio.Media.DURATION, meta.durationMs)
        } else {
            put(MediaStore.Video.Media.ARTIST, meta.artist)
            put(MediaStore.Video.Media.ALBUM, ALBUM)
            if (meta.durationMs > 0) put(MediaStore.Video.Media.DURATION, meta.durationMs)
        }
    }

    private fun publishQ(c: Context, src: File, meta: Meta, fileName: String): Uri? {
        val resolver = c.contentResolver
        val uri = resolver.insert(collection(meta.audio), values(meta, fileName, pending = true)) ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { inp -> inp.copyTo(out) } } != null
        }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(c: Context, src: File, meta: Meta, fileName: String): Uri? {
        val dir = publicDir(meta.audio)
        if (!dir.exists() && !dir.mkdirs()) return null
        val dest = File(dir, fileName)
        runCatching { src.copyTo(dest, overwrite = true) }.onFailure { return null }
        if (!dest.exists() || dest.length() <= 0L) return null
        val cv = values(meta, fileName, pending = false).apply { put(MediaStore.MediaColumns.DATA, dest.absolutePath) }
        val uri = c.contentResolver.insert(collection(meta.audio), cv) ?: Uri.fromFile(dest)
        MediaScannerConnection.scanFile(c, arrayOf(dest.absolutePath), arrayOf(mime(meta.audio)), null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun publicDir(audio: Boolean) = File(
        Environment.getExternalStoragePublicDirectory(if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES),
        FOLDER,
    )

    /** Copy an already-finished private download into Music/Movies and point the DB row at the public Uri. */
    fun exportExisting(c: Context, d: Downloads.Dl): Boolean {
        val stored = d.file ?: return false
        if (isPublic(stored)) return exists(c, stored)
        val src = File(stored)
        if (!src.exists()) return false
        val name = src.name.ifBlank {
            displayName(d.title, d.audioOnly, d.height, takenNames(c, d.audioOnly))
        }
        val taken = takenNames(c, d.audioOnly)
        val fileName = if (name in taken) displayName(d.title, d.audioOnly, d.height, taken) else name
        val uri = publishNamed(c, src, Meta(d.title, d.channel, d.audioOnly, d.durationSec * 1000, taken), fileName) ?: return false
        Downloads.update(d.id) { put("file", uri.toString()) }
        src.delete()
        return true
    }

    /** ⋮ action: copy a finished private file to the phone. No re-download. */
    fun exportFromMenu(c: Context, d: Downloads.Dl) {
        if (needsLegacyWrite() && !hasLegacyWrite(c)) {
            (c as? Activity)?.let { requestLegacyWrite(it) }
            Toast.makeText(c, "อนุญาตการเข้าถึงที่เก็บข้อมูลก่อน แล้วเลือกอีกครั้ง", Toast.LENGTH_LONG).show()
            return
        }
        val scope = (c as? LifecycleOwner)?.lifecycleScope ?: MainScope()
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { exportExisting(c.applicationContext, d) } }
                .onFailure { android.util.Log.w("DeviceSave", "export failed", it) }
                .getOrDefault(false)
            Toast.makeText(c, if (ok) "บันทึกไว้ที่ ${folderLabel(d.audioOnly)}" else "บันทึกลงเครื่องไม่ได้", Toast.LENGTH_LONG).show()
        }
    }
}
