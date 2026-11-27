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
        helper = object : SQLiteOpenHelper(c.applicationContext, "ytclassic.db", null, 7) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE subs (url TEXT PRIMARY KEY, name TEXT NOT NULL, avatar TEXT, subscribers INTEGER NOT NULL DEFAULT -1, added INTEGER NOT NULL)")
                createV2(db); createV3(db); createV4(db); createV6(db); createV7(db)
            }
            override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { if (o < 2) createV2(db); if (o < 3) createV3(db); if (o < 4) createV4(db); if (o in 2..4) rekeyHistory(db); if (o < 6) createV6(db); if (o < 7) createV7(db) }
        }
    }

    /** 6.0.1: remember which saved videos are Shorts. ALTER only, existing rows stay. */
    fun addShortFormColumnSql(table: String) = "ALTER TABLE $table ADD COLUMN short_form INTEGER NOT NULL DEFAULT 0"

    private fun createV6(db: SQLiteDatabase) {
        listOf("history", "likes", "playlist_items").forEach { db.execSQL(addShortFormColumnSql(it)) }
    }

    /** 6.0.3: snap nearly finished history rows to the end so they count as watched. */
    fun markFinishedWatchProgressSql() =
        "UPDATE history SET position_ms = length_ms WHERE length_ms > 0 AND position_ms > 0 AND position_ms >= length_ms - 15000"

    private fun createV7(db: SQLiteDatabase) {
        db.execSQL(markFinishedWatchProgressSql())
    }

    /** 5.0: local likes and remembered deleted / private videos. */
    private fun createV4(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS likes ($VDEF, value INTEGER NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(url))")
        db.execSQL("CREATE TABLE IF NOT EXISTS unavailable (url TEXT PRIMARY KEY, kind INTEGER NOT NULL, at INTEGER NOT NULL)")
    }

    /** History, one row per video (url). */
    fun historyDdl(table: String) = "CREATE TABLE $table ($VDEF, watched_at INTEGER NOT NULL, position_ms INTEGER NOT NULL DEFAULT 0, " +
        "length_ms INTEGER NOT NULL DEFAULT 0, watch_count INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(url))"

    /**
     * 5.0.1: before v5 the history table was created with "$VDEF PRIMARY KEY", which made uploaded_ms (the last column)
     * the primary key. Two rows with the same upload time (0, or the same day) then made History.record throw
     * "UNIQUE constraint failed: history.uploaded_ms" and crash. Rebuild it keyed by url, keeping the newest row per video.
     */
    private fun rekeyHistory(db: SQLiteDatabase) {
        db.execSQL(historyDdl("history_v5"))
        db.execSQL("INSERT OR IGNORE INTO history_v5 SELECT $VCOLS, watched_at, position_ms, length_ms, watch_count FROM history ORDER BY watched_at DESC")
        db.execSQL("DROP TABLE history")
        db.execSQL("ALTER TABLE history_v5 RENAME TO history")
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL(historyDdl("history"))
        db.execSQL("CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE playlist_items (playlist_id INTEGER NOT NULL, $VDEF, added INTEGER NOT NULL, PRIMARY KEY(playlist_id, url))")
        db.execSQL("INSERT INTO playlists (id, name, created) VALUES (${Playlists.WATCH_LATER}, 'ดูภายหลัง', 0)")
    }

    private fun createV3(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE downloads (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, title TEXT, channel TEXT, thumb TEXT, duration INTEGER, " +
            "audio_only INTEGER NOT NULL, height INTEGER NOT NULL, status INTEGER NOT NULL, done_bytes INTEGER NOT NULL DEFAULT 0, total_bytes INTEGER NOT NULL DEFAULT 0, " +
            "file TEXT, error TEXT, created INTEGER NOT NULL)")
    }

    fun values(v: VideoItem) = ContentValues().apply {
        put("url", v.url); put("title", v.title); put("channel", v.channel); put("channel_url", v.channelUrl)
        put("thumb", v.thumb); put("avatar", v.avatar); put("duration", v.durationSec); put("views", v.views); put("uploaded_ms", v.uploadedMs)
        if (v.shortForm) put("short_form", 1)
    }

    /** Reads the first 9 columns in [VCOLS] order, plus optional short_form if the cursor selected it. */
    fun video(c: Cursor): VideoItem {
        val item = VideoItem(
            url = c.getString(0), title = c.getString(1).orEmpty(), channel = c.getString(2).orEmpty(), channelUrl = c.getString(3),
            thumb = c.getString(4), avatar = c.getString(5), durationSec = c.getLong(6), views = c.getLong(7), age = "",
            live = false, upcoming = false, members = false, scheduledEpochMs = 0, uploadedMs = c.getLong(8),
        )
        val idx = c.getColumnIndex("short_form")
        val flagged = idx >= 0 && !c.isNull(idx) && c.getInt(idx) == 1
        return if (flagged || item.isShortClip()) item.copy(shortForm = true) else item
    }
}

object History {
    fun record(v: VideoItem) {
        if (v.kind != 0 || Prefs.historyPaused) return
        val now = System.currentTimeMillis()
        val cv = Db.values(v).apply { put("watched_at", now) }
        val db = Db.w
        val upd = ContentValues(cv).apply { remove("url"); if (v.uploadedMs <= 0) remove("uploaded_ms") }
        if (db.update("history", upd, "url=?", arrayOf(v.url)) > 0) {
            db.execSQL("UPDATE history SET watch_count = watch_count + 1 WHERE url=?", arrayOf(v.url))
        } else db.insert("history", null, cv)
        version++
    }

    /** Fill in channel url / avatar / duration once the stream info is known. */
    fun updateMeta(url: String, channelUrl: String?, avatar: String?, durationSec: Long, title: String, shortForm: Boolean = false) {
        val cv = ContentValues().apply {
            channelUrl?.let { put("channel_url", it) }; avatar?.let { put("avatar", it) }
            if (durationSec > 0) put("duration", durationSec); if (title.isNotBlank()) put("title", title)
            if (shortForm) put("short_form", 1)
        }
        if (cv.size() > 0) Db.w.update("history", cv, "url=?", arrayOf(url))
    }

    fun updatePosition(url: String, pos: Long, length: Long) {
        val stored = WatchProgress.storedPosition(pos, length)
        Db.w.update("history", ContentValues().apply { put("position_ms", stored.first); if (stored.second > 0) put("length_ms", stored.second) }, "url=?", arrayOf(url))
    }

    fun position(url: String): Long = Db.r.rawQuery("SELECT position_ms FROM history WHERE url=?", arrayOf(url)).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    /** url -> watched fraction 0..1000 */
    fun progressMap(): Map<String, Int> = Db.r.rawQuery("SELECT url, position_ms, length_ms FROM history WHERE length_ms > 0 AND position_ms > 0", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), WatchProgress.bar(c.getLong(1), c.getLong(2))) }
    }

    fun marks(urls: List<String>): List<SeriesEpisodes.WatchMark> {
        if (urls.isEmpty()) return emptyList()
        return urls.chunked(500).flatMap { part ->
            Db.r.rawQuery(
                "SELECT url, position_ms, length_ms, watched_at FROM history WHERE url IN (${part.joinToString(",") { "?" }})",
                part.toTypedArray(),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(SeriesEpisodes.WatchMark(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3)))
                }
            }
        }
    }

    data class Entry(val video: VideoItem, val watchedAt: Long, val count: Int)

    fun recent(limit: Int = 500): List<Entry> =
        Db.r.rawQuery("SELECT ${Db.VCOLS}, watched_at, watch_count, short_form FROM history ORDER BY watched_at DESC LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(Entry(Db.video(c), c.getLong(9), c.getInt(10))) }
        }

    /** url -> title for the given videos that are in the history. */
    fun titles(urls: List<String>): Map<String, String> = urls.chunked(500).flatMap { part ->
        Db.r.rawQuery("SELECT url, title FROM history WHERE url IN (${part.joinToString(",") { "?" }})", part.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1).orEmpty()) }
        }
    }.toMap()

    fun watchedUrls(): Set<String> = Db.r.rawQuery("SELECT url FROM history", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    fun remove(url: String) { Db.w.delete("history", "url=?", arrayOf(url)); version++ }
    fun clear() { Db.w.delete("history", null, null); version++ }
    fun count(): Int = Db.r.rawQuery("SELECT COUNT(*) FROM history", null).use { it.moveToFirst(); it.getInt(0) }
    @Volatile var version = 0L
        private set
}

object Playlists {
    const val WATCH_LATER = 1L
    data class Pl(val id: Long, val name: String, val count: Int, val thumb: String?, val latest: Long = 0)

    fun all(): List<Pl> = Db.r.rawQuery(
        "SELECT p.id, p.name, (SELECT COUNT(*) FROM playlist_items i WHERE i.playlist_id=p.id), " +
            "(SELECT thumb FROM playlist_items i WHERE i.playlist_id=p.id ORDER BY added DESC LIMIT 1), " +
            "COALESCE((SELECT MAX(added) FROM playlist_items i WHERE i.playlist_id=p.id), p.created) FROM playlists p ORDER BY p.id", null
    ).use { c -> buildList { while (c.moveToNext()) add(Pl(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3), c.getLong(4))) } }

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
    fun items(id: Long): List<VideoItem> = Db.r.rawQuery("SELECT ${Db.VCOLS}, short_form FROM playlist_items WHERE playlist_id=? ORDER BY added ${if (id == WATCH_LATER) "DESC" else "ASC"}", arrayOf("$id"))
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
    /** เล่นอัตโนมัติ: play the up-next video when one ends. */
    var autoplay: Boolean
        get() = p.getBoolean("autoplay", true)
        set(v) { p.edit().putBoolean("autoplay", v).apply() }
    private fun b(k: String, def: Boolean) = p.getBoolean(k, def)
    private fun setB(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    /** Don't add newly watched videos to the watch history. */
    var historyPaused: Boolean get() = b("history_paused", false); set(v) = setB("history_paused", v)
    /** Hide members-only / upcoming / paid / removed videos from lists. */
    var hideUnplayable: Boolean get() = b("hide_unplayable", false); set(v) = setB("hide_unplayable", v)
    /** Default video height on Wi-Fi / on mobile data; 0 = อัตโนมัติ (adaptive). */
    var qualityWifi: Int get() = p.getInt("q_wifi", 0); set(v) = p.edit().putInt("q_wifi", v).apply()
    var qualityMobile: Int get() = p.getInt("q_mobile", 0); set(v) = p.edit().putInt("q_mobile", v).apply()
    /** Download defaults: height (0 = สูงสุด), audio only, ask every time. */
    var dlQuality: Int get() = p.getInt("dl_quality", 720); set(v) = p.edit().putInt("dl_quality", v).apply()
    var dlAudioOnly: Boolean get() = b("dl_audio", false); set(v) = setB("dl_audio", v)
    var dlAsk: Boolean get() = b("dl_ask", true); set(v) = setB("dl_ask", v)
    /** Captions: on/off, last language tag (blank = auto), font size (0/1/2). */
    var ccEnabled: Boolean get() = b("cc_on", false); set(v) = setB("cc_on", v)
    var ccLanguage: String
        get() = p.getString("cc_lang", "") ?: ""
        set(v) { p.edit().putString("cc_lang", v).apply() }
    var ccFontSize: Int
        get() = p.getInt("cc_size", Captions.SIZE_NORMAL)
        set(v) { p.edit().putInt("cc_size", v).apply() }
    /** คลังวิดีโอ sort on the คุณ page (LibraryModel.Sort name). */
    var librarySort: String
        get() = p.getString("library_sort", "") ?: ""
        set(v) { p.edit().putString("library_sort", v).apply() }
    /** Preferred audio language tag (blank = original track). */
    var audioLanguage: String
        get() = p.getString("audio_lang", "") ?: ""
        set(v) { p.edit().putString("audio_lang", v).apply() }
    val qualityHeights = intArrayOf(0, 1080, 720, 480, 360, 240, 144)
    fun qualityName(h: Int, zero: String = "อัตโนมัติ") = if (h == 0) zero else "${h}p"
    fun applyTheme() = AppCompatDelegate.setDefaultNightMode(when (theme) {
        1 -> AppCompatDelegate.MODE_NIGHT_NO; 2 -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    })
    val themeNames = arrayOf("ตามระบบ", "สว่าง", "มืด")
}

/** Recent search queries, newest first, no duplicates, at most 50 (stored on the phone only). */
object SearchHistory {
    private const val KEY = "search_history"
    private const val MAX = 50
    fun all(): List<String> = runCatching {
        val a = org.json.JSONArray(Prefs.p.getString(KEY, "[]")); List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())
    private fun save(l: List<String>) { Prefs.p.edit().putString(KEY, org.json.JSONArray(l.take(MAX)).toString()).apply() }
    fun add(q: String) { val t = q.trim(); if (t.isEmpty()) return; save(listOf(t) + all().filterNot { it.equals(t, ignoreCase = true) }) }
    fun remove(q: String) = save(all().filterNot { it == q })
    fun clear() = save(emptyList())
}
