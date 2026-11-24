package com.bear.ytclassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Features602Test {
    private fun v(id: String, title: String = id, kind: Int = 0) = VideoItem(
        url = "https://www.youtube.com/watch?v=$id", title = title, channel = "c",
        channelUrl = "https://www.youtube.com/@c", thumb = null, avatar = null,
        durationSec = 60, views = 1, age = "", live = false, upcoming = false,
        members = false, scheduledEpochMs = 0, kind = kind,
    )

    // ---- item 1: opening a video must not launch WatchActivity ----

    @Test fun openPathUsesOverlayWhenCallerIsAnActivity() {
        assertEquals("overlay", WatchNav.route(fromActivity = true))
        assertEquals("MainActivity", WatchNav.route(fromActivity = false))
        assertFalse(WatchNav.launchesWatchActivity(fromActivity = true))
        assertFalse(WatchNav.launchesWatchActivity(fromActivity = false))
    }

    @Test fun backAndSwipeDownOnlyCollapseTheOverlay() {
        assertEquals("collapse", WatchNav.backAction(overlayExpanded = true, landscape = false))
        assertEquals("exit_landscape", WatchNav.backAction(overlayExpanded = true, landscape = true))
        assertEquals("none", WatchNav.backAction(overlayExpanded = false, landscape = false))
        assertEquals("collapse", WatchNav.swipeDownAction())
        assertFalse(WatchNav.opensCommentsOnCollapse())
    }

    // ---- item 2: recover from network / expired stream ----

    @Test fun retriesNetworkAndExpiredStreamErrorsWithBackoff() {
        assertTrue(StreamRecovery.isRecoverable(2001)) // IO network
        assertTrue(StreamRecovery.isRecoverable(2002)) // IO timeout
        assertTrue(StreamRecovery.isRecoverable(1003)) // player timeout (was ignored)
        assertTrue(StreamRecovery.isRecoverable(1000))
        assertTrue(StreamRecovery.isRecoverable(3001))
        assertFalse(StreamRecovery.isRecoverable(4001)) // decoder
        assertFalse(StreamRecovery.isRecoverable(1004)) // runtime check
        assertTrue(StreamRecovery.shouldRetry(errorCode = 2001, retriesSoFar = 0, offline = false))
        assertTrue(StreamRecovery.shouldRetry(errorCode = 1003, retriesSoFar = 4, offline = false))
        assertFalse(StreamRecovery.shouldRetry(errorCode = 2001, retriesSoFar = 5, offline = false))
        assertFalse(StreamRecovery.shouldRetry(errorCode = 2001, retriesSoFar = 0, offline = true))
        assertEquals(1_000L, StreamRecovery.delayMs(1))
        assertEquals(2_000L, StreamRecovery.delayMs(2))
        assertEquals(8_000L, StreamRecovery.delayMs(4))
        assertEquals(16_000L, StreamRecovery.delayMs(9))
    }

    @Test fun networkRestoreRefetchesWhenPlaybackFailed() {
        assertTrue(StreamRecovery.shouldRefetchOnNetworkRestore(
            hasItem = true, offline = false, hasError = true, playWhenReady = true, playbackIdle = false,
        ))
        assertTrue(StreamRecovery.shouldRefetchOnNetworkRestore(
            hasItem = true, offline = false, hasError = false, playWhenReady = true, playbackIdle = true,
        ))
        assertFalse(StreamRecovery.shouldRefetchOnNetworkRestore(
            hasItem = true, offline = true, hasError = true, playWhenReady = true, playbackIdle = false,
        ))
        assertFalse(StreamRecovery.shouldRefetchOnNetworkRestore(
            hasItem = false, offline = false, hasError = true, playWhenReady = false, playbackIdle = true,
        ))
        assertFalse(StreamRecovery.shouldRefetchOnNetworkRestore(
            hasItem = true, offline = false, hasError = false, playWhenReady = true, playbackIdle = false,
        ))
    }

    // ---- item 3: channel tab swipe stays in sync ----

    @Test fun channelSwipeIndexMatchesTabIdBothWays() {
        val tabs = listOf(
            ChannelTabSpec("home", "หน้าแรก"),
            ChannelTabSpec("videos", "วิดีโอ"),
            ChannelTabSpec("shorts", "Shorts"),
            ChannelTabSpec("playlists", "เพลย์ลิสต์"),
        )
        assertEquals(0, ChannelSwipe.pageFor(tabs, "home"))
        assertEquals(1, ChannelSwipe.pageFor(tabs, "videos"))
        assertEquals(3, ChannelSwipe.pageFor(tabs, "playlists"))
        assertEquals(-1, ChannelSwipe.pageFor(tabs, "missing"))
        assertEquals("home", ChannelSwipe.tabFor(tabs, 0))
        assertEquals("shorts", ChannelSwipe.tabFor(tabs, 2))
        assertEquals("เพลย์ลิสต์", ChannelSwipe.tabFor(tabs, 3)?.let { id -> tabs.first { it.id == id }.label })
        assertNull(ChannelSwipe.tabFor(tabs, -1))
        assertNull(ChannelSwipe.tabFor(tabs, 9))
        // swipe left (next) and right (previous)
        assertEquals("videos", ChannelSwipe.tabAfterSwipe(tabs, "home", delta = +1))
        assertEquals("home", ChannelSwipe.tabAfterSwipe(tabs, "videos", delta = -1))
        assertEquals("home", ChannelSwipe.tabAfterSwipe(tabs, "home", delta = -1))
        assertEquals("playlists", ChannelSwipe.tabAfterSwipe(tabs, "playlists", delta = +1))
    }

    // ---- item 4: seasons from channel playlists, not invented extractor fields ----

    @Test fun seasonNumbersParseFromCommonTitles() {
        assertEquals(1, SeriesSeasons.parseNumber("ซีซัน 1"))
        assertEquals(2, SeriesSeasons.parseNumber("ซีซั่น 2"))
        assertEquals(3, SeriesSeasons.parseNumber("ซีซันที่ 3"))
        assertEquals(1, SeriesSeasons.parseNumber("Season 1"))
        assertEquals(10, SeriesSeasons.parseNumber("Season 10 | The Show"))
        assertEquals(4, SeriesSeasons.parseNumber("S4"))
        assertEquals(4, SeriesSeasons.parseNumber("S04"))
        assertNull(SeriesSeasons.parseNumber("Best of 2024"))
        assertNull(SeriesSeasons.parseNumber("Clips"))
    }

    @Test fun episodeLabelsAndSeasonSort() {
        assertEquals("ซีซัน 1 ตอน 2", SeriesSeasons.episodeLabel(season = 1, episodeIndex = 1))
        assertEquals("ซีซัน 3 ตอน 1", SeriesSeasons.episodeLabel(season = 3, episodeIndex = 0))
        assertEquals("ตอน 4", SeriesSeasons.episodeLabel(season = null, episodeIndex = 3))
        val raw = listOf(
            SeriesSeasons.Season("u2", "ซีซัน 2", 2, null, emptyList()),
            SeriesSeasons.Season("u1", "ซีซัน 1", 1, null, emptyList()),
            SeriesSeasons.Season("ux", "พิเศษ", null, null, emptyList()),
        )
        assertEquals(listOf("u1", "u2", "ux"), SeriesSeasons.sort(raw).map { it.url })
    }

    @Test fun resumePicksLatestUnfinishedAndQueuesAcrossSeasons() {
        val s1 = listOf(v("a"), v("b"), v("c"))
        val s2 = listOf(v("d"), v("e"))
        val seasons = listOf(
            SeriesSeasons.Season("s1", "ซีซัน 1", 1, null, s1),
            SeriesSeasons.Season("s2", "ซีซัน 2", 2, null, s2),
        )
        val marks = listOf(
            SeriesSeasons.WatchMark("https://www.youtube.com/watch?v=a", 10_000, 60_000, watchedAt = 10),
            SeriesSeasons.WatchMark("https://www.youtube.com/watch?v=b", 20_000, 60_000, watchedAt = 50),
            SeriesSeasons.WatchMark("https://www.youtube.com/watch?v=d", 60_000, 60_000, watchedAt = 99), // finished
        )
        val resume = SeriesSeasons.resume(seasons, marks)
        assertEquals("b", resume?.title)
        assertEquals("ดูต่อ", SeriesSeasons.playLabel(resume))
        assertEquals("เล่นตอน", SeriesSeasons.playLabel(null))
        assertEquals(listOf("b", "c", "d", "e"), SeriesSeasons.queueFrom(seasons, seasonIndex = 0, episodeIndex = 1).map { it.title })
        assertEquals(listOf("a", "b", "c", "d", "e"), SeriesSeasons.queueFrom(seasons, 0, 0).map { it.title })
    }
}
