package com.bear.ytclassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Features607Test {
    // ---- 1: likes / dislikes / hidden affect Home ----

    private val sig = RecScoring.signals(
        history = listOf(
            RecScoring.Watch("v1", "chA", 3), RecScoring.Watch("v2", "chB", 4), RecScoring.Watch("v3", "chC", 1),
        ),
        ratings = listOf(RecScoring.Rated("v2", "chB", Likes.DISLIKE), RecScoring.Rated("l1", "chL", Likes.LIKE)),
        hiddenVideos = mapOf("h1" to "chN", "h2" to "chN"),
        hiddenChannels = setOf("chX"),
        subscribed = emptySet(),
    )

    @Test fun dislikedWatchDoesNotCountAndChannelIsPenalized() {
        assertEquals(null, sig.channelWatch["chB"])       // the disliked watch is not counted
        assertEquals(3, sig.channelWatch["chA"])
        val neutral = RecScoring.score(1.0, "chZ", 0.0, sig)
        assertTrue(RecScoring.score(1.0, "chB", 0.0, sig) < neutral)
        assertTrue(RecScoring.score(1.0, "chL", 0.0, sig) > neutral)  // liked channel boosted
        assertTrue(RecScoring.score(1.0, "chN", 0.0, sig) < neutral)  // ไม่สนใจ x2 = small penalty
        assertTrue(RecScoring.score(1.0, "chN", 0.0, sig) > RecScoring.score(1.0, "chB", 0.0, sig) - 1.0)
    }

    @Test fun seedsSkipDislikedAndAddLiked() {
        val seeds = RecScoring.relatedSeeds(listOf("v2", "v1", "v3"), listOf("l1", "v1"), sig)
        assertEquals(listOf("v1", "v3", "l1"), seeds.map { it.first })
        assertEquals(RecScoring.LIKED_SEED_BONUS, seeds.last().second, 1e-9)
    }

    @Test fun hiddenVideosChannelsAndDislikedVideosAreFiltered() {
        assertFalse(RecScoring.allowed("h1", "chN", sig))
        assertFalse(RecScoring.allowed("any", "chX", sig))
        assertFalse(RecScoring.allowed("v2", "chB", sig))
        assertTrue(RecScoring.allowed("new", "chN", sig))   // ไม่สนใจ hides only that video
        assertTrue(HiddenRecs.ddl().startsWith("CREATE TABLE IF NOT EXISTS hidden_recs"))
        assertEquals(8, Db.VERSION)
    }

    // ---- 2: chapters ----

    private val chapters = Chapters.of(listOf(Triple("ช่วงสอง", 60_000L, null), Triple("Intro", 0L, "t0"), Triple("End", 150_000L, "")))

    @Test fun chaptersSortFindCurrentAndCutTheBar() {
        assertEquals(listOf("Intro", "ช่วงสอง", "End"), chapters.map { it.title })
        assertEquals(null, chapters[2].thumb)
        assertEquals(0, Chapters.indexAt(chapters, 59_999))
        assertEquals(1, Chapters.indexAt(chapters, 60_000))
        assertEquals(2, Chapters.indexAt(chapters, 999_000))
        assertEquals(listOf(60_000L, 150_000L), Chapters.gapStarts(chapters, 200_000))
        assertEquals(listOf(0L until 60_000L, 60_000L until 150_000L, 150_000L until 200_000L), Chapters.ranges(Chapters.gapStarts(chapters, 200_000), 200_000))
        assertTrue(Chapters.of(listOf(Triple("only", 0L, null))).isEmpty())  // one chapter = hidden
        assertTrue(Chapters.from(null).isEmpty())
    }

    @Test fun descriptionTimestampsAreFound() {
        val text = "0:00 เริ่ม\n12:30 ตอนกลาง 1:02:03 ท้าย 2024 ไม่ใช่ 10:75 ไม่ใช่ 3:5"
        val stamps = Chapters.timestamps(text)
        assertEquals(listOf(0L, 750_000L, 3_723_000L), stamps.map { it.ms })
        assertEquals("12:30", text.substring(stamps[1].start, stamps[1].end))
        assertEquals(listOf(0L, 750_000L), Chapters.timestamps(text, durationMs = 1_000_000).map { it.ms })
    }

    // ---- 3: channel About ----

    @Test fun aboutShowsOnlyFieldsTheExtractorGives() {
        val about = ChannelAbout(description = "สวัสดี https://www.instagram.com/x", subscriberCount = 1200, links = listOf("https://www.instagram.com/x"))
        val rows = about.infoRows("https://www.youtube.com/channel/UCabc")
        assertEquals(listOf(ChannelAbout.Info.URL, ChannelAbout.Info.SUBSCRIBERS), rows.map { it.first })
        assertEquals("www.youtube.com/channel/UCabc", rows[0].second)
        assertEquals("instagram.com", ChannelPage.linkTitle("https://www.instagram.com/x"))
        assertTrue(ChannelAbout().infoRows("").isEmpty())
    }
}
