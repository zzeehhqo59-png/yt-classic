package com.bear.ytclassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs

class Features601Test {
    private fun v(
        id: String,
        channelUrl: String? = "https://www.youtube.com/@ch",
        url: String = "https://www.youtube.com/watch?v=$id",
        shortForm: Boolean = false,
        kind: Int = 0,
    ) = VideoItem(
        url = url, title = id, channel = "c", channelUrl = channelUrl, thumb = null, avatar = null,
        durationSec = 60, views = 1, age = "", live = false, upcoming = false, members = false,
        scheduledEpochMs = 0, kind = kind, shortForm = shortForm,
    )

    @Test fun avatarOrNameOpensChannelNotTheVideo() {
        val item = v("abc", channelUrl = "https://www.youtube.com/@ice")
        assertEquals("https://www.youtube.com/@ice", VideoClicks.channelUrl(item))
        assertNull(VideoClicks.channelUrl(item.copy(channelUrl = null)))
        assertNull(VideoClicks.channelUrl(item.copy(channelUrl = "")))
        assertNull(VideoClicks.channelUrl(item.copy(channelUrl = "   ")))
        // never treat the video URL as a channel destination
        assertNull(VideoClicks.channelUrl(item.copy(channelUrl = item.url)))
    }

    @Test fun channelTabsHideMissingAndUnsupportedOnes() {
        val onlyVideos = ChannelPage.visibleTabs(listOf(ChannelTabs.VIDEOS)).map { it.id }
        assertEquals(listOf(ChannelPage.HOME, ChannelTabs.VIDEOS), onlyVideos)
        assertFalse(onlyVideos.contains(ChannelTabs.SHORTS))

        val ids = ChannelPage.visibleTabs(listOf(
            ChannelTabs.VIDEOS, ChannelTabs.SHORTS, ChannelTabs.PLAYLISTS, "posts", "community",
        )).map { it.id }
        assertEquals(
            listOf(ChannelPage.HOME, ChannelTabs.VIDEOS, ChannelTabs.SHORTS, ChannelTabs.PLAYLISTS),
            ids,
        )
        assertFalse(ids.contains("posts"))
        assertFalse(ids.contains("community"))
        assertTrue(ChannelPage.visibleTabs(emptyList()).isEmpty())
    }

    @Test fun channelTabLabelsAreThai() {
        val labels = ChannelPage.visibleTabs(listOf(
            ChannelTabs.VIDEOS, ChannelTabs.SHORTS, ChannelTabs.LIVESTREAMS,
            ChannelTabs.PODCASTS, ChannelTabs.PLAYLISTS,
        )).associate { it.id to it.label }
        assertEquals("หน้าแรก", labels[ChannelPage.HOME])
        assertEquals("วิดีโอ", labels[ChannelTabs.VIDEOS])
        assertEquals("Shorts", labels[ChannelTabs.SHORTS])
        assertEquals("ไลฟ์สด", labels[ChannelTabs.LIVESTREAMS])
        assertEquals("พอดแคสต์", labels[ChannelTabs.PODCASTS])
        assertEquals("เพลย์ลิสต์", labels[ChannelTabs.PLAYLISTS])
    }

    @Test fun sortOptionsHiddenWhenExtractorHasNone() {
        assertTrue(ChannelPage.sortOptions(emptyList()).isEmpty())
        assertEquals(
            listOf("newest" to "ล่าสุด", "popular" to "ยอดนิยม", "oldest" to "เก่าที่สุด"),
            ChannelPage.sortOptions(listOf("newest", "popular", "oldest")),
        )
        assertEquals(listOf("custom" to "custom"), ChannelPage.sortOptions(listOf("custom")))
    }

    @Test fun aboutKeepsOnlyProvidedFieldsAndSkipsFacebookAndMembership() {
        val empty = ChannelAbout()
        assertTrue(empty.stats().isEmpty())
        assertTrue(empty.links.isEmpty())

        val about = ChannelAbout(
            description = "สวัสดี https://example.com/me และ https://facebook.com/page",
            subscriberCount = 12_000,
            videoCount = -1,
            viewCount = -1,
            joinedMs = 0,
            links = ChannelPage.linksFrom(
                "สวัสดี https://example.com/me และ https://facebook.com/page",
                listOf("https://www.facebook.com/x", "https://www.youtube.com/channel/UC123/join", "https://mysite.com"),
            ),
        )
        val stats = about.stats().map { it.first }.toSet()
        assertTrue(stats.contains("ผู้ติดตาม"))
        assertFalse(stats.contains("วิดีโอ"))
        assertFalse(stats.contains("ยอดชม"))
        assertFalse(stats.contains("วันที่สมัคร"))
        assertEquals(listOf("https://example.com/me", "https://mysite.com"), about.links)
    }

    @Test fun playlistFilterSplitsVideosAndShorts() {
        val items = listOf(
            v("long"),
            v("flag", shortForm = true),
            v("path", url = "https://www.youtube.com/shorts/xyz"),
            v("pl", kind = 2),
        )
        assertEquals(4, PlaylistFilter.apply(items, PlaylistFilter.ALL).size)
        assertEquals(listOf("long", "pl"), PlaylistFilter.apply(items, PlaylistFilter.VIDEOS).map { it.title })
        assertEquals(listOf("flag", "path"), PlaylistFilter.apply(items, PlaylistFilter.SHORTS).map { it.title })
    }

    @Test fun shortFormColumnMigrationDoesNotRebuildTables() {
        val sql = Db.addShortFormColumnSql("history")
        assertTrue(sql, sql.startsWith("ALTER TABLE history ADD COLUMN short_form"))
        assertFalse(sql, sql.contains("DROP"))
        assertFalse(sql, sql.contains("CREATE TABLE"))
        assertTrue(Db.historyDdl("history").contains("PRIMARY KEY(url)"))
    }
}
