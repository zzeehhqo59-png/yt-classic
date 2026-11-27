package com.bear.ytclassic

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Home "ไม่สนใจ" (one video) and "ไม่แนะนำช่องนี้" (whole channel), stored in table hidden_recs (DB v8). */
object HiddenRecs {
    const val VIDEO = 0
    const val CHANNEL = 1

    fun ddl() = "CREATE TABLE IF NOT EXISTS hidden_recs (target TEXT NOT NULL, kind INTEGER NOT NULL, channel_key TEXT, " +
        "at INTEGER NOT NULL, PRIMARY KEY(target, kind))"

    data class Snapshot(val videos: Map<String, String?>, val channels: Set<String>)

    fun hideVideo(url: String, channelKey: String?) = put(url, VIDEO, channelKey)
    fun hideChannel(channelKey: String) = put(channelKey, CHANNEL, channelKey)

    fun undo(target: String, kind: Int) {
        Db.w.delete("hidden_recs", "target=? AND kind=?", arrayOf(target, "$kind"))
        Recommender.invalidate()
    }

    fun clear() { Db.w.delete("hidden_recs", null, null); Recommender.invalidate() }

    fun count(): Int = Db.r.rawQuery("SELECT COUNT(*) FROM hidden_recs", null).use { it.moveToFirst(); it.getInt(0) }

    fun snapshot(): Snapshot {
        val videos = HashMap<String, String?>(); val channels = HashSet<String>()
        Db.r.rawQuery("SELECT target, kind, channel_key FROM hidden_recs", null).use { c ->
            while (c.moveToNext()) if (c.getInt(1) == CHANNEL) channels += c.getString(0) else videos[c.getString(0)] = c.getString(2)
        }
        return Snapshot(videos, channels)
    }

    private fun put(target: String, kind: Int, channelKey: String?) {
        Db.w.insertWithOnConflict("hidden_recs", null, ContentValues().apply {
            put("target", target); put("kind", kind); put("channel_key", channelKey); put("at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        Recommender.invalidate()
    }
}
