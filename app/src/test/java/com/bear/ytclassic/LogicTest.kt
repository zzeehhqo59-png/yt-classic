package com.bear.ytclassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import java.io.IOException

class LogicTest {
    private fun v(id: String, unavailable: Boolean = false, members: Boolean = false, live: Boolean = false) = VideoItem(
        url = "https://www.youtube.com/watch?v=$id", title = id, channel = "c", channelUrl = null, thumb = null, avatar = null,
        durationSec = 60, views = 1, age = "", live = live, upcoming = false, members = members, scheduledEpochMs = 0, unavailable = unavailable,
    )

    @Test fun placeholderTitles() {
        assertEquals(Availability.PRIVATE, Availability.kindFromTitle("[Private video]"))
        assertEquals(Availability.REMOVED, Availability.kindFromTitle(" [Deleted video] "))
        assertEquals(Availability.PRIVATE, Availability.kindFromTitle("[วิดีโอส่วนตัว]"))
        assertEquals(Availability.OK, Availability.kindFromTitle("My private video diary"))
        assertEquals(Availability.OK, Availability.kindFromTitle(null))
    }

    @Test fun playbackErrors() {
        assertEquals(Availability.PRIVATE, Availability.kindFromError(PrivateContentException("This video is private")))
        assertEquals(Availability.REMOVED, Availability.kindFromError(ContentNotAvailableException("Got error: \"This video has been removed by the uploader\"")))
        assertEquals(Availability.REMOVED, Availability.kindFromError(ContentNotAvailableException("This video is unavailable")))
        assertEquals(Availability.OK, Availability.kindFromError(ContentNotAvailableException("no streams")))
        assertEquals(Availability.OK, Availability.kindFromError(AgeRestrictedContentException("age")))
        assertEquals(Availability.OK, Availability.kindFromError(IOException("timeout")))
    }

    @Test fun queueSkipsUnplayable() {
        val list = listOf(v("a"), v("b", unavailable = true), v("c", members = true), v("d", live = true), v("e"))
        assertEquals(0, PlayQueue.nextPlayableIndex(list, -1))
        assertEquals(4, PlayQueue.nextPlayableIndex(list, 0))
        assertEquals(-1, PlayQueue.nextPlayableIndex(list, 4))
    }

    @Test fun queueStartAndNext() {
        val list = listOf(v("a", unavailable = true), v("b"), v("c", unavailable = true), v("d"))
        assertEquals("b", PlayQueue.start(list, 0)?.title)
        assertEquals("d", PlayQueue.peekNext()?.title)
        PlayQueue.onOpen(list[3].url)
        assertNull(PlayQueue.peekNext())
        PlayQueue.onOpen("https://www.youtube.com/watch?v=other")
        assertEquals(false, PlayQueue.active)
    }

    @Test fun channelHandle() {
        assertEquals("@mkbhd", Fmt.channelLabel("Marques Brownlee", "https://www.youtube.com/@mkbhd"))
        assertEquals("@mkbhd", Fmt.channelLabel("Marques Brownlee", "https://www.youtube.com/@mkbhd/videos?x=1"))
        assertEquals("Marques Brownlee", Fmt.channelLabel("Marques Brownlee", "https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ"))
        assertEquals("ช่อง", Fmt.channelLabel("ช่อง", null))
    }

    @Test fun likeMessages() {
        assertEquals("เพิ่มในวิดีโอที่ชอบแล้ว", Likes.message(Likes.LIKE, Likes.LIKE))
        assertEquals("คุณไม่ชอบวิดีโอนี้", Likes.message(Likes.DISLIKE, Likes.DISLIKE))
        assertEquals("นำออกจากวิดีโอที่ชอบแล้ว", Likes.message(0, Likes.LIKE))
        assertEquals("ยกเลิกการไม่ชอบแล้ว", Likes.message(0, Likes.DISLIKE))
    }
}
