package com.bear.ytclassic

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
        val text = c.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }.removePrefix("\uFEFF")
        val list = if (text.trimStart().startsWith("{")) parseNewPipe(text) else parseTakeoutCsv(text)
        if (list.isEmpty()) throw IllegalArgumentException("no channels")
        addAll(list)
    }

    private fun parseNewPipe(text: String): List<Channel> {
        val arr = JSONObject(text).getJSONArray("subscriptions")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (o.optInt("service_id", 0) != 0) return@mapNotNull null
            val url = o.optString("url"); if (url.isBlank()) null else Channel(url, o.optString("name"), null)
        }
    }

    private fun parseTakeoutCsv(text: String): List<Channel> {
        val rows = text.lines().filter { it.isNotBlank() }.map { csvRow(it) }
        return rows.mapNotNull { r ->
            val id = r.getOrNull(0)?.trim().orEmpty()
            if (!id.startsWith("UC")) return@mapNotNull null   // skips the header row (any language)
            val url = r.getOrNull(1)?.trim()?.takeIf { it.startsWith("http") } ?: "https://www.youtube.com/channel/$id"
            Channel(url, r.getOrNull(2)?.trim().orEmpty(), null)
        }
    }

    private fun csvRow(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                q && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                ch == ',' && !q -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
