package com.bear.ytclassic

import android.database.sqlite.SQLiteDatabase

/** Like / dislike kept on this phone only (table `likes`, value 1 = ถูกใจ, -1 = ไม่ชอบ). */
object Likes {
    const val LIKE = 1
    const val DISLIKE = -1

    /** 1, -1 or 0 when the video has no rating. */
    fun state(url: String): Int = Db.r.rawQuery("SELECT value FROM likes WHERE url=?", arrayOf(url)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Set the rating ([value] 0 removes it). */
    fun set(v: VideoItem, value: Int) {
        if (value == 0) Db.w.delete("likes", "url=?", arrayOf(v.url))
        else Db.w.insertWithOnConflict("likes", null, Db.values(v).apply { put("value", value); put("at", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
        version++
    }

    /** Tapping 👍 / 👎: same button again clears it. Returns the new state. */
    fun toggle(v: VideoItem, value: Int): Int {
        val next = if (state(v.url) == value) 0 else value
        set(v, next)
        return next
    }

    fun liked(): List<VideoItem> = Db.r.rawQuery("SELECT ${Db.VCOLS} FROM likes WHERE value=1 ORDER BY at DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Db.video(c)) }
    }

    fun count(): Int = Db.r.rawQuery("SELECT COUNT(*) FROM likes WHERE value=1", null).use { it.moveToFirst(); it.getInt(0) }

    @Volatile var version = 0L
        private set
}
