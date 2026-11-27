package com.bear.ytclassic

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList

data class Channel(val url: String, val name: String, val avatar: String?, val subscribers: Long = -1)

/** Local subscriptions (SQLite file ytclassic.db; the old NewPipe-fork database is newpipe.db and is never written). */
object Subs {
    private val helper get() = Db.helper
    /** Bumped on every change so screens know when to reload. */
    @Volatile var version = 0L
        private set

    /** Canonical https://www.youtube.com/channel/UC... form when possible. */
    fun normalize(url: String): String =
        runCatching { ServiceList.YouTube.channelLHFactory.fromUrl(url.trim()).url }.getOrDefault(url.trim())

    fun all(): List<Channel> = helper.readableDatabase
        .rawQuery("SELECT url,name,avatar,subscribers FROM subs ORDER BY name COLLATE NOCASE", null).use { c ->
            buildList { while (c.moveToNext()) add(Channel(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }

    fun count(): Int = helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM subs", null).use { it.moveToFirst(); it.getInt(0) }

    fun isSubscribed(url: String): Boolean = helper.readableDatabase
        .rawQuery("SELECT 1 FROM subs WHERE url=?", arrayOf(normalize(url))).use { it.moveToFirst() }

    /** @return true if newly added */
    fun add(ch: Channel): Boolean {
        val v = ContentValues().apply {
            put("url", normalize(ch.url)); put("name", ch.name); put("avatar", ch.avatar)
            put("subscribers", ch.subscribers); put("added", System.currentTimeMillis())
        }
        val ok = helper.writableDatabase.insertWithOnConflict("subs", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (ok) version++
        return ok
    }

    fun update(ch: Channel) {
        val v = ContentValues().apply {
            put("name", ch.name); ch.avatar?.let { put("avatar", it) }; if (ch.subscribers >= 0) put("subscribers", ch.subscribers)
        }
        helper.writableDatabase.update("subs", v, "url=?", arrayOf(normalize(ch.url)))
    }

    fun remove(url: String) {
        helper.writableDatabase.delete("subs", "url=?", arrayOf(normalize(url)))
        version++
    }

    data class ImportResult(val added: Int, val existing: Int, val total: Int)

    private fun addAll(list: List<Channel>): ImportResult {
        var added = 0
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            list.forEach { if (add(it)) added++ }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return ImportResult(added, list.size - added, list.size)
    }

    /** One-time import from the old YT Classic (NewPipe fork) database left in this app's data dir. */
    suspend fun importOldAppOnce(c: Context): Int? = withContext(Dispatchers.IO) {
        val prefs = c.getSharedPreferences("ytc", Context.MODE_PRIVATE)
        if (prefs.getBoolean("old_db_checked", false)) return@withContext null
        val f = c.getDatabasePath("newpipe.db")
        var result: Int? = null
        if (f.exists()) {
            runCatching {
                SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.rawQuery("SELECT url,name,avatar_url FROM subscriptions WHERE service_id=0", null).use { cur ->
                        val list = buildList { while (cur.moveToNext()) add(Channel(cur.getString(0), cur.getString(1) ?: "", cur.getString(2))) }
                        result = addAll(list.filter { it.url.isNotBlank() }).added
                    }
                }
            }
        }
        prefs.edit().putBoolean("old_db_checked", true).apply()
        result
    }

    /** NewPipe subscriptions export (JSON) or YouTube Takeout subscriptions.csv. */
    suspend fun importFile(c: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val stream = c.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("cannot open file")
        val list = SubsFormat.parse(stream.use { it.readBytes().toString(Charsets.UTF_8) })
        if (list.isEmpty()) throw IllegalArgumentException("no channels")
        addAll(list)
    }

    /** Writes every subscription as a NewPipe JSON export (readable by [importFile] and by NewPipe). @return channel count */
    suspend fun exportFile(c: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val list = all()
        val stream = c.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalArgumentException("cannot open file")
        stream.use { it.write(SubsFormat.toNewPipeJson(list).toByteArray(Charsets.UTF_8)) }
        list.size
    }
}
