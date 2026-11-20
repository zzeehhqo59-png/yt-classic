package com.bear.ytclassic

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.appcompat.app.AppCompatDelegate

/** App database ytclassic.db (subscriptions, history, playlists). */
object Db {
    lateinit var helper: SQLiteOpenHelper
    val r: SQLiteDatabase get() = helper.readableDatabase
    val w: SQLiteDatabase get() = helper.writableDatabase

    const val VCOLS = "url,title,channel,channel_url,thumb,avatar,duration,views,uploaded_ms"
    private const val VDEF = "url TEXT NOT NULL, title TEXT, channel TEXT, channel_url TEXT, thumb TEXT, avatar TEXT, duration INTEGER, views INTEGER, uploaded_ms INTEGER"

    fun init(c: Context) {
        helper = object : SQLiteOpenHelper(c.applicationContext, "ytclassic.db", null, 2) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE subs (url TEXT PRIMARY KEY, name TEXT NOT NULL, avatar TEXT, subscribers INTEGER NOT NULL DEFAULT -1, added INTEGER NOT NULL)")
                createV2(db)
            }
            override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { if (o < 2) createV2(db) }
        }
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history ($VDEF PRIMARY KEY, watched_at INTEGER NOT NULL, position_ms INTEGER NOT NULL DEFAULT 0, length_ms INTEGER NOT NULL DEFAULT 0, watch_count INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE playlist_items (playlist_id INTEGER NOT NULL, $VDEF, added INTEGER NOT NULL, PRIMARY KEY(playlist_id, url))")
        db.execSQL("INSERT INTO playlists (id, name, created) VALUES (${Playlists.WATCH_LATER}, 'ดูภายหลัง', 0)")
    }

    fun values(v: VideoItem) = ContentValues().apply {
        put("url", v.url); put("title", v.title); put("channel", v.channel); put("channel_url", v.channelUrl)
        put("thumb", v.thumb); put("avatar", v.avatar); put("duration", v.durationSec); put("views", v.views); put("uploaded_ms", v.uploadedMs)
    }

    /** Reads the first 9 columns in [VCOLS] order. */
    fun video(c: Cursor) = VideoItem(
        url = c.getString(0), title = c.getString(1).orEmpty(), channel = c.getString(2).orEmpty(), channelUrl = c.getString(3),
        thumb = c.getString(4), avatar = c.getString(5), durationSec = c.getLong(6), views = c.getLong(7), age = "",
        live = false, upcoming = false, members = false, scheduledEpochMs = 0, uploadedMs = c.getLong(8),
    )
}

object History {
    fun record(v: VideoItem) {
        if (v.kind != 0) return
        val now = System.currentTimeMillis()
        val cv = Db.values(v).apply { put("watched_at", now) }
        val db = Db.w
        if (db.update("history", ContentValues(cv).apply { remove("url") }, "url=?", arrayOf(v.url)) > 0) {
            db.execSQL("UPDATE history SET watch_count = watch_count + 1 WHERE url=?", arrayOf(v.url))
        } else db.insert("history", null, cv)
        version++
    }

    /** Fill in channel url / avatar / duration once the stream info is known. */
    fun updateMeta(url: String, channelUrl: String?, avatar: String?, durationSec: Long, title: String) {
        val cv = ContentValues().apply {
            channelUrl?.let { put("channel_url", it) }; avatar?.let { put("avatar", it) }
            if (durationSec > 0) put("duration", durationSec); if (title.isNotBlank()) put("title", title)
        }
        if (cv.size() > 0) Db.w.update("history", cv, "url=?", arrayOf(url))
    }

    fun updatePosition(url: String, pos: Long, length: Long) {
        Db.w.update("history", ContentValues().apply { put("position_ms", pos); if (length > 0) put("length_ms", length) }, "url=?", arrayOf(url))
    }

    fun position(url: String): Long = Db.r.rawQuery("SELECT position_ms FROM history WHERE url=?", arrayOf(url)).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    /** url -> watched fraction 0..1000 */
    fun progressMap(): Map<String, Int> = Db.r.rawQuery("SELECT url, position_ms, length_ms FROM history WHERE length_ms > 0 AND position_ms > 0", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), (c.getLong(1) * 1000 / c.getLong(2)).toInt().coerceIn(0, 1000)) }
    }

    data class Entry(val video: VideoItem, val watchedAt: Long, val count: Int)

    fun recent(limit: Int = 500): List<Entry> =
        Db.r.rawQuery("SELECT ${Db.VCOLS}, watched_at, watch_count FROM history ORDER BY watched_at DESC LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(Entry(Db.video(c), c.getLong(9), c.getInt(10))) }
        }

    fun watchedUrls(): Set<String> = Db.r.rawQuery("SELECT url FROM history", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    fun remove(url: String) { Db.w.delete("history", "url=?", arrayOf(url)); version++ }
    fun clear() { Db.w.delete("history", null, null); version++ }
    fun count(): Int = Db.r.rawQuery("SELECT COUNT(*) FROM history", null).use { it.moveToFirst(); it.getInt(0) }
    @Volatile var version = 0L
        private set
}

object Playlists {
    const val WATCH_LATER = 1L
    data class Pl(val id: Long, val name: String, val count: Int, val thumb: String?)

    fun all(): List<Pl> = Db.r.rawQuery(
        "SELECT p.id, p.name, (SELECT COUNT(*) FROM playlist_items i WHERE i.playlist_id=p.id), " +
            "(SELECT thumb FROM playlist_items i WHERE i.playlist_id=p.id ORDER BY added DESC LIMIT 1) FROM playlists p ORDER BY p.id", null
    ).use { c -> buildList { while (c.moveToNext()) add(Pl(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3))) } }

    fun get(id: Long): Pl? = all().firstOrNull { it.id == id }
    fun create(name: String): Long = Db.w.insert("playlists", null, ContentValues().apply { put("name", name); put("created", System.currentTimeMillis()) })
    fun rename(id: Long, name: String) { Db.w.update("playlists", ContentValues().apply { put("name", name) }, "id=?", arrayOf("$id")) }
    fun delete(id: Long) {
        if (id == WATCH_LATER) return
        Db.w.delete("playlist_items", "playlist_id=?", arrayOf("$id")); Db.w.delete("playlists", "id=?", arrayOf("$id"))
    }
    fun contains(id: Long, url: String): Boolean = Db.r.rawQuery("SELECT 1 FROM playlist_items WHERE playlist_id=? AND url=?", arrayOf("$id", url)).use { it.moveToFirst() }
    fun add(id: Long, v: VideoItem): Boolean =
        Db.w.insertWithOnConflict("playlist_items", null, Db.values(v).apply { put("playlist_id", id); put("added", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    fun remove(id: Long, url: String) { Db.w.delete("playlist_items", "playlist_id=? AND url=?", arrayOf("$id", url)) }
    fun clear(id: Long) { Db.w.delete("playlist_items", "playlist_id=?", arrayOf("$id")) }
    fun items(id: Long): List<VideoItem> = Db.r.rawQuery("SELECT ${Db.VCOLS} FROM playlist_items WHERE playlist_id=? ORDER BY added ${if (id == WATCH_LATER) "DESC" else "ASC"}", arrayOf("$id"))
        .use { c -> buildList { while (c.moveToNext()) add(Db.video(c)) } }
}

object Prefs {
    lateinit var p: SharedPreferences
    fun init(c: Context) { p = c.getSharedPreferences("ytc", Context.MODE_PRIVATE) }
    /** 0 = follow system, 1 = light, 2 = dark */
    var theme: Int
        get() = p.getInt("theme", 0)
        set(v) { p.edit().putInt("theme", v).apply(); applyTheme() }
    var pip: Boolean
        get() = p.getBoolean("pip", true)
        set(v) { p.edit().putBoolean("pip", v).apply() }
    var background: Boolean
        get() = p.getBoolean("background", true)
        set(v) { p.edit().putBoolean("background", v).apply() }
    fun applyTheme() = AppCompatDelegate.setDefaultNightMode(when (theme) {
        1 -> AppCompatDelegate.MODE_NIGHT_NO; 2 -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    })
    val themeNames = arrayOf("ตามระบบ", "สว่าง", "มืด")
}
