package com.bear.ytclassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Features603Test {
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
