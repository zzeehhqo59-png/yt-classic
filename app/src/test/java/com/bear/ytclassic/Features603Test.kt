package com.bear.ytclassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Features603Test {
    private fun season(title: String, url: String = title, count: Int = 0, episodes: List<VideoItem> = emptyList()) =
        SeriesSeasons.Season(url, title, SeriesSeasons.parseNumber(title), null, episodes, count)

    @Test fun baseTitleStripsTagsSeasonAndEpisode() {
        assertEquals(
            SeriesSeasons.baseTitle("น้องโล่สายแทงก์แกร่งเกินร้อย ซีซัน 2 [พากย์ไทย]"),
            SeriesSeasons.baseTitle("น้องโล่สายแทงก์แกร่งเกินร้อย [พากย์ไทย]"),
        )
        assertEquals(
            SeriesSeasons.baseTitle("The Show Season 2"),
            SeriesSeasons.baseTitle("The Show S1"),
        )
        assertEquals(
            SeriesSeasons.baseTitle("เรื่องนี้ ตอนที่ 12"),
            SeriesSeasons.baseTitle("เรื่องนี้"),
        )
        assertTrue(SeriesSeasons.baseTitle("น้องโล่สายแทงก์แกร่งเกินร้อย ซีซัน 2 [พากย์ไทย]") !=
            SeriesSeasons.baseTitle("เกิดใหม่เป็นประธานนักเรียน ซีซัน 2 [พากย์ไทย]"))
    }

    @Test fun dubAndSubStaySeparate() {
        val dub = "น้องโล่สายแทงก์แกร่งเกินร้อย ซีซัน 2 [พากย์ไทย]"
        val sub = "น้องโล่สายแทงก์แกร่งเกินร้อย ซีซัน 2 [ซับไทย]"
        val plain = "น้องโล่สายแทงก์แกร่งเกินร้อย ซีซัน 2"
        assertEquals("dub", SeriesSeasons.editionOf(dub))
        assertEquals("sub", SeriesSeasons.editionOf(sub))
        assertEquals("", SeriesSeasons.editionOf(plain))
        assertTrue(SeriesSeasons.sameSeries(dub, "น้องโล่สายแทงก์แกร่งเกินร้อย [พากย์ไทย]"))
        assertFalse(SeriesSeasons.sameSeries(dub, sub))
        assertFalse(SeriesSeasons.sameSeries(dub, plain))
    }

    @Test fun ofCurrentKeepsOnlyThisSeriesDefaultsMissingNumberToSeason1AndDedupes() {
        val current = season("น้องโล่สายแทงก์แกร่งเกินร้อย ซีซัน 2 [พากย์ไทย]", "u2", 12)
        val candidates = listOf(
            current,
            season("น้องโล่สายแทงก์แกร่งเกินร้อย [พากย์ไทย]", "u1", 10), // missing number = S1
            season("น้องโล่สายแทงก์แกร่งเกินร้อย ซีซัน 2 [พากย์ไทย]", "u2-dup", 3), // dupe S2
            season("น้องโล่สายแทงก์แกร่งเกินร้อย ซีซัน 2 [ซับไทย]", "u2sub", 12),
            season("เกิดใหม่เป็นประธานนักเรียน ซีซัน 2 [พากย์ไทย]", "other", 8),
            season("ซีซัน 2", "bare", 20),
        )
        val out = SeriesSeasons.ofCurrent(current, candidates)
        assertEquals(listOf(1, 2), out.map { it.number })
        assertEquals("u1", out[0].url)
        assertEquals("u2", out[1].url) // prefer the open playlist over the weaker duplicate
        assertTrue(out.none { it.url == "u2sub" || it.url == "other" || it.url == "bare" })
        assertFalse(SeriesSeasons.showSeasonPicker(out.take(1)))
        assertTrue(SeriesSeasons.showSeasonPicker(out))
        assertEquals("ซีซัน 1 · 10 ตอน", SeriesSeasons.sheetLabel(out[0]))
        assertEquals("ซีซัน 2 · 12 ตอน", SeriesSeasons.sheetLabel(out[1]))
        assertEquals("ซีซัน 3", SeriesSeasons.sheetLabel(season("Show S3", "x", 0)))
    }

    @Test fun sleepTimerPausesAtDeadlineAndAtEndOfVideo() {
        assertEquals("ปิด", SleepTimer.summary(SleepTimer.Off, now = 0))
        assertEquals("ถึงจบตอนนี้", SleepTimer.summary(SleepTimer.EndOfVideo, now = 0))
        val in15 = SleepTimer.Minutes(untilElapsed = 15 * 60_000L)
        assertEquals("อีก 15 นาที", SleepTimer.summary(in15, now = 0))
        assertEquals("อีก 1 นาที", SleepTimer.summary(in15, now = 14 * 60_000L + 1))
        assertTrue(SleepTimer.shouldFire(in15, now = 15 * 60_000L))
        assertFalse(SleepTimer.shouldFire(in15, now = 14 * 60_000L))
        assertFalse(SleepTimer.shouldPauseAtEnd(SleepTimer.Minutes(1)))
        assertTrue(SleepTimer.shouldPauseAtEnd(SleepTimer.EndOfVideo))
        assertFalse(SleepTimer.shouldPauseAtEnd(SleepTimer.Off))
        assertEquals(listOf(15, 30, 45, 60), SleepTimer.MINUTE_OPTIONS)
    }

    @Test fun watchProgressMarksNearlyFinishedAndResumesOtherwise() {
        assertEquals(0L, WatchProgress.resumeAt(4_000, 60_000))
        assertEquals(20_000L, WatchProgress.resumeAt(20_000, 60_000))
        assertEquals(0L, WatchProgress.resumeAt(50_000, 60_000)) // last 15s = watched
        assertTrue(WatchProgress.isFinished(50_000, 60_000))
        assertFalse(WatchProgress.isFinished(20_000, 60_000))
        assertEquals(1000, WatchProgress.bar(60_000, 60_000))
        assertEquals(500, WatchProgress.bar(30_000, 60_000))
        assertEquals(0, WatchProgress.bar(0, 60_000))
        val stored = WatchProgress.storedPosition(50_000, 60_000)
        assertEquals(60_000L, stored.first)
        assertEquals(60_000L, stored.second)
        assertTrue(Db.markFinishedWatchProgressSql().contains("position_ms = length_ms"))
        assertTrue(Db.historyDdl("history").contains("position_ms INTEGER NOT NULL DEFAULT 0"))
        assertTrue(Db.historyDdl("history").contains("length_ms INTEGER NOT NULL DEFAULT 0"))
    }
}
