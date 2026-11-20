package com.bear.ytclassic

import android.content.ContentValues
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException

/**
 * Deleted / private videos. Remote lists tell us by their placeholder title; for saved playlists we remember
 * videos that failed to play with a "private" or "removed" error (table `unavailable`).
 */
object Availability {
    const val OK = 0
    const val REMOVED = 1
    const val PRIVATE = 2

    private val PLACEHOLDER_TITLES = listOf(
        "[private video]", "[deleted video]", "[unavailable video]",
        "[วิดีโอส่วนตัว]", "[วิดีโอที่ถูกลบ]", "[วิดีโอที่ไม่พร้อมใช้งาน]",
    )

    /** Kind from a list entry's title (YouTube replaces the title of hidden videos with a placeholder). */
    fun kindFromTitle(title: String?): Int {
        val t = title.orEmpty().trim().lowercase()
        if (t !in PLACEHOLDER_TITLES) return OK
        return if (t.contains("private") || t.contains("ส่วนตัว")) PRIVATE else REMOVED
    }

    /** Kind from a playback error; anything temporary or restricted (age, region, members, network) is [OK]. */
    fun kindFromError(e: Throwable): Int {
        val msg = e.message.orEmpty().lowercase()
        return when {
            e is PrivateContentException -> PRIVATE
            e is AccountTerminatedException -> REMOVED
            e is AgeRestrictedContentException || e is GeographicRestrictionException || e is UnsupportedContentInCountryException ||
                e is PaidContentException || e is YoutubeMusicPremiumContentException -> OK
            e is ContentNotAvailableException -> when {
                msg.contains("private") -> PRIVATE
                msg.contains("removed") || msg.contains("deleted") || msg.contains("terminated") ||
                    msg.contains("no longer available") || msg.contains("video unavailable") || msg.contains("is unavailable") || msg.contains("ถูกนำออก") -> REMOVED
                else -> OK
            }
            else -> OK
        }
    }

    fun mark(url: String, kind: Int) {
        if (kind == OK) { clear(url); return }
        Db.w.insertWithOnConflict("unavailable", null, ContentValues().apply {
            put("url", url); put("kind", kind); put("at", System.currentTimeMillis())
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clear(url: String) { Db.w.delete("unavailable", "url=?", arrayOf(url)) }

    private fun known(): Map<String, Int> = Db.r.rawQuery("SELECT url, kind FROM unavailable", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
    }

    /** Flag remembered unavailable videos and give placeholder entries back the title we saw in history. */
    fun apply(list: List<VideoItem>): List<VideoItem> {
        if (list.isEmpty()) return list
        val flagged = known()
        val needTitle = list.filter { it.unavailable }.map { it.url }
        val titles = if (needTitle.isEmpty()) emptyMap() else History.titles(needTitle)
        return list.map { v ->
            when {
                v.kind != 0 -> v
                v.unavailable -> v.copy(title = titles[v.url]?.takeIf { it.isNotBlank() && kindFromTitle(it) == OK } ?: v.title)
                flagged[v.url] != null -> v.copy(unavailable = true, privateVideo = flagged[v.url] == PRIVATE)
                else -> v
            }
        }
    }
}
