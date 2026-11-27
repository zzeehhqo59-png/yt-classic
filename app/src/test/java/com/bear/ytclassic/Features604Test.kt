package com.bear.ytclassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Features604Test {
    private fun v(id: String, title: String = id) = VideoItem(
        url = "https://www.youtube.com/watch?v=$id", title = title, channel = "c",
        channelUrl = "https://www.youtube.com/@c", thumb = "t_$id", avatar = null,
        durationSec = 60, views = 1, age = "", live = false, upcoming = false,
        members = false, scheduledEpochMs = 0,
    )

    // ---- C: the ⋯ button under the video called itself forever (StackOverflowError) ----

    @Test(timeout = 2_000)
    fun moreButtonOpensTheSheetOnceInsteadOfRecursing() {
        var more = 0; var comments = 0; var share = 0; var save = 0; var rated = 0
        val cb: WatchHeaderAdapter.Callbacks = WatchHeaderBridge(
            onMore = { more++ }, onComments = { comments++ }, onShare = { share++ }, onSave = { save++ },
            currentRating = { 1 }, onRate = { rated = it }, onCommentsChanged = { _, _, _ -> },
        )
        cb.moreSheet()
        cb.openComments(); cb.share(); cb.save(); cb.rate(-1)
        assertEquals(1, more)
        assertEquals(listOf(1, 1, 1), listOf(comments, share, save))
        assertEquals(-1, rated)
        assertEquals(1, cb.rating())
    }

    // ---- B: playlist page without seasons ----

    @Test fun episodeLabelsHaveNoSeason() {
        assertEquals("ตอนที่ 1", SeriesEpisodes.episodeLabel(0))
        assertEquals("ตอนที่ 12", SeriesEpisodes.episodeLabel(11))
    }

    @Test fun resumePicksLatestUnfinishedAndQueuesFromTheTappedEpisode() {
        val eps = listOf(v("a"), v("b"), v("c"), v("d"))
        val marks = listOf(
            SeriesEpisodes.WatchMark(eps[0].url, 10_000, 60_000, watchedAt = 10),
            SeriesEpisodes.WatchMark(eps[1].url, 20_000, 60_000, watchedAt = 50),
            SeriesEpisodes.WatchMark(eps[3].url, 60_000, 60_000, watchedAt = 99), // finished
        )
        val resume = SeriesEpisodes.resume(eps, marks)
        assertEquals("b", resume?.title)
        assertEquals("ดูต่อ", SeriesEpisodes.playLabel(resume))
        assertEquals("เล่นตอน", SeriesEpisodes.playLabel(null))
        assertEquals(listOf("b", "c", "d"), SeriesEpisodes.queueFrom(eps, 1).map { it.title })
        assertEquals(listOf("a", "b", "c", "d"), SeriesEpisodes.queueFrom(eps, -3).map { it.title })
        assertEquals(1, SeriesEpisodes.startIndex(eps, resume))
        assertEquals(0, SeriesEpisodes.startIndex(eps, null))
    }

    // ---- D: You / library page ----

    private fun dl(id: Long, audio: Boolean = false, created: Long = id) = LibraryModel.DownloadInfo(id, "t$id", audio, created)

    private fun snapshot(
        playlists: List<LibraryModel.PlaylistInfo> = emptyList(),
        downloads: List<LibraryModel.DownloadInfo> = emptyList(),
        likedCount: Int = 0,
    ) = LibraryModel.Snapshot(
        history = listOf(v("h")),
        watchLater = LibraryModel.PlaylistInfo(Playlists.WATCH_LATER, "ดูภายหลัง", 2, "wl", latest = 30),
        likedCount = likedCount, likedCover = if (likedCount > 0) "lk" else null, likedLatest = 40,
        playlists = playlists, downloads = downloads, subscriptions = 3, watchedCount = 7,
    )

    @Test fun chipsOnlyAppearWhenTheAppHasThatData() {
        assertEquals(listOf(LibraryModel.Filter.RECENT), LibraryModel.filters(snapshot()))
        val full = snapshot(
            playlists = listOf(LibraryModel.PlaylistInfo(5, "อนิเมะ", 1, "p", latest = 10)),
            downloads = listOf(dl(1), dl(2, audio = true)),
        )
        assertEquals(
            listOf(LibraryModel.Filter.RECENT, LibraryModel.Filter.DOWNLOADED, LibraryModel.Filter.PLAYLISTS, LibraryModel.Filter.MUSIC),
            LibraryModel.filters(full),
        )
        assertEquals(listOf("ล่าสุด", "ดาวน์โหลดแล้ว", "เพลย์ลิสต์", "เพลง"), LibraryModel.filters(full).map { it.label })
        assertFalse(LibraryModel.Filter.MUSIC in LibraryModel.filters(snapshot(downloads = listOf(dl(1)))))
        // A chip whose data disappeared falls back to ล่าสุด
        assertEquals(LibraryModel.Filter.RECENT, LibraryModel.effective(snapshot(), LibraryModel.Filter.MUSIC))
    }

    @Test fun recentRowsSortByLatestActivityAndEndWithCreate() {
        val s = snapshot(
            playlists = listOf(LibraryModel.PlaylistInfo(5, "อนิเมะ", 1, "p", latest = 50)),
            downloads = listOf(dl(9, created = 45)),
            likedCount = 3,
        )
        val rows = LibraryModel.rows(s, LibraryModel.Filter.RECENT)
        assertEquals(
            listOf(LibraryModel.Kind.PLAYLIST, LibraryModel.Kind.DOWNLOADS, LibraryModel.Kind.LIKED, LibraryModel.Kind.WATCH_LATER, LibraryModel.Kind.CREATE),
            rows.map { it.kind },
        )
        assertEquals("p", rows[0].cover)
        assertEquals("t9", rows[1].cover) // cover = latest clip
        assertEquals("3 วิดีโอ", rows[2].meta)
        assertTrue(rows[0].editable)
        assertFalse(rows.first { it.kind == LibraryModel.Kind.WATCH_LATER }.editable)
    }

    @Test fun emptyCollectionsSayEmpty() {
        val rows = LibraryModel.rows(snapshot(), LibraryModel.Filter.RECENT)
        assertEquals("ยังว่าง", rows.first { it.kind == LibraryModel.Kind.LIKED }.meta)
        assertFalse(rows.any { it.kind == LibraryModel.Kind.DOWNLOADS && it.meta.isEmpty() })
        assertEquals("ยังไม่มีวิดีโอที่ดาวน์โหลด", LibraryModel.emptyText(LibraryModel.Filter.DOWNLOADED))
        assertEquals("ติดตาม 3 ช่อง · ดูแล้ว 7 วิดีโอ", LibraryModel.profileLine(snapshot()))
    }

    @Test fun downloadChipsShowTheMatchingDownloads() {
        val s = snapshot(downloads = listOf(dl(1), dl(2, audio = true)))
        assertEquals(listOf(1L, 2L), LibraryModel.downloads(s, LibraryModel.Filter.DOWNLOADED).map { it.id })
        assertEquals(listOf(2L), LibraryModel.downloads(s, LibraryModel.Filter.MUSIC).map { it.id })
        assertTrue(LibraryModel.downloads(s, LibraryModel.Filter.RECENT).isEmpty())
        assertEquals(
            listOf(LibraryModel.Kind.WATCH_LATER, LibraryModel.Kind.LIKED, LibraryModel.Kind.CREATE),
            LibraryModel.rows(s, LibraryModel.Filter.PLAYLISTS).map { it.kind },
        )
    }
}
