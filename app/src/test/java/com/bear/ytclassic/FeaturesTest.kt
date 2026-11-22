package com.bear.ytclassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeaturesTest {
    private fun v(id: String, unavailable: Boolean = false, members: Boolean = false, live: Boolean = false) = VideoItem(
        url = "https://www.youtube.com/watch?v=$id", title = id, channel = "c", channelUrl = null, thumb = null, avatar = null,
        durationSec = 60, views = 1, age = "", live = live, upcoming = false, members = members, scheduledEpochMs = 0, unavailable = unavailable,
    )

    @Before fun resetQueue() { PlayQueue.clear() }

    // ---- play queue: add / play next ----

    @Test fun addToQueueIsPeekedEvenWithoutPlaylist() {
        assertFalse(PlayQueue.active)
        assertTrue(PlayQueue.add(v("q1")))
        assertEquals("q1", PlayQueue.peekNext()?.title)
        assertTrue(PlayQueue.active)
    }

    @Test fun playNextInsertsAheadOfAddToQueue() {
        PlayQueue.add(v("later"))
        PlayQueue.playNext(v("soon"))
        assertEquals("soon", PlayQueue.peekNext()?.title)
        PlayQueue.onOpen(v("soon").url)
        assertEquals("later", PlayQueue.peekNext()?.title)
    }

    @Test fun userQueueComesBeforePlaylistNext() {
        val list = listOf(v("a"), v("b"), v("c"))
        PlayQueue.start(list, 0)
        PlayQueue.add(v("extra"))
        assertEquals("extra", PlayQueue.peekNext()?.title)
        PlayQueue.onOpen(v("extra").url)
        assertEquals("b", PlayQueue.peekNext()?.title)
    }

    @Test fun queueSkipsUnplayableUserItems() {
        PlayQueue.add(v("bad", unavailable = true))
        PlayQueue.add(v("good"))
        assertEquals("good", PlayQueue.peekNext()?.title)
    }

    @Test fun playNextMovesExistingQueuedItemToFront() {
        PlayQueue.add(v("a"))
        PlayQueue.add(v("b"))
        PlayQueue.playNext(v("b"))
        assertEquals(listOf("b", "a"), PlayQueue.extras().map { it.title })
    }

    @Test fun startPlaylistClearsUserQueue() {
        PlayQueue.add(v("extra"))
        PlayQueue.start(listOf(v("a"), v("b")), 0)
        assertEquals("b", PlayQueue.peekNext()?.title)
        assertTrue(PlayQueue.extras().isEmpty())
    }

    @Test fun openingUnrelatedVideoKeepsUserQueue() {
        PlayQueue.add(v("q1"))
        PlayQueue.onOpen("https://www.youtube.com/watch?v=other")
        assertEquals("q1", PlayQueue.peekNext()?.title)
    }

    // ---- autoplay offer ----

    @Test fun autoplayUsesQueueEvenWhenSwitchIsOff() {
        assertEquals("q", Autoplay.nextAfterEnd(autoplayOn = false, offline = false, live = false, queued = v("q"), related = v("r"))?.title)
    }

    @Test fun autoplayUsesRelatedOnlyWhenSwitchIsOn() {
        assertEquals("r", Autoplay.nextAfterEnd(autoplayOn = true, offline = false, live = false, queued = null, related = v("r"))?.title)
        assertNull(Autoplay.nextAfterEnd(autoplayOn = false, offline = false, live = false, queued = null, related = v("r")))
    }

    @Test fun autoplaySkipsWhenOfflineOrLive() {
        assertNull(Autoplay.nextAfterEnd(true, offline = true, live = false, queued = v("q"), related = v("r")))
        assertNull(Autoplay.nextAfterEnd(true, offline = false, live = true, queued = v("q"), related = v("r")))
    }

    @Test fun autoplayOfferExpiresAfterDelay() {
        val offer = Autoplay.Offer(v("n"), startedAtMs = 1_000, delayMs = 5_000)
        assertEquals(5_000, offer.remainingMs(1_000))
        assertEquals(1_000, offer.remainingMs(5_000))
        assertFalse(offer.expired(5_999))
        assertTrue(offer.expired(6_000))
        assertEquals(0, offer.remainingMs(9_000))
    }

    // ---- previous button ----

    @Test fun prevRestartsAfterFiveSecondsEvenIfHistoryExists() {
        assertTrue(PlaybackNav.shouldRestart(positionMs = 5001, hasPrev = true))
        assertFalse(PlaybackNav.shouldRestart(positionMs = 5000, hasPrev = true))
        assertFalse(PlaybackNav.shouldRestart(positionMs = 0, hasPrev = true))
    }

    @Test fun prevRestartsWhenThereIsNoPreviousVideo() {
        assertTrue(PlaybackNav.shouldRestart(positionMs = 0, hasPrev = false))
        assertTrue(PlaybackNav.shouldRestart(positionMs = 1000, hasPrev = false))
    }

    // ---- mini player when leaving the watch page ----

    @Test fun miniPlayerShowsOnChannelOpenedFromWatch() {
        assertTrue(MiniPlayerBar.shouldShow(pip = false, watchForeground = false, hasItem = true))
        assertFalse(MiniPlayerBar.shouldShow(pip = false, watchForeground = true, hasItem = true))
        assertFalse(MiniPlayerBar.shouldShow(pip = true, watchForeground = false, hasItem = true))
        assertFalse(MiniPlayerBar.shouldShow(pip = false, watchForeground = false, hasItem = false))
    }

    // ---- captions ----

    @Test fun captionFontSizes() {
        assertEquals(14f, Captions.fontSp(Captions.SIZE_SMALL))
        assertEquals(20f, Captions.fontSp(Captions.SIZE_NORMAL))
        assertEquals(28f, Captions.fontSp(Captions.SIZE_LARGE))
        assertEquals(20f, Captions.fontSp(99))
    }

    @Test fun captionPickIsOffWhenDisabledOrEmpty() {
        val tracks = listOf(Captions.Track("en", "English", auto = false))
        assertNull(Captions.pick(tracks, enabled = false, preferredLang = "en"))
        assertNull(Captions.pick(emptyList(), enabled = true, preferredLang = "en"))
    }

    @Test fun captionPickPrefersStoredLanguageIncludingRegionalTag() {
        val tracks = listOf(
            Captions.Track("en", "English", auto = false),
            Captions.Track("th-TH", "ไทย", auto = false),
            Captions.Track("th", "ไทย (อัตโนมัติ)", auto = true),
        )
        assertEquals("th-TH", Captions.pick(tracks, enabled = true, preferredLang = "th")?.language)
        assertEquals("en", Captions.pick(tracks, enabled = true, preferredLang = "en-US")?.language)
    }

    @Test fun captionPickPrefersNonAutoOfSameLanguage() {
        val tracks = listOf(
            Captions.Track("en", "English (auto)", auto = true),
            Captions.Track("en", "English", auto = false),
        )
        assertEquals(false, Captions.pick(tracks, enabled = true, preferredLang = "en")?.auto)
    }

    @Test fun captionPickDefaultsToThaiThenNonAuto() {
        val tracks = listOf(
            Captions.Track("en", "English", auto = false),
            Captions.Track("th", "ไทย", auto = false),
        )
        assertEquals("th", Captions.pick(tracks, enabled = true, preferredLang = null)?.language)
        val noThai = listOf(
            Captions.Track("en", "English (auto)", auto = true),
            Captions.Track("ja", "日本語", auto = false),
        )
        assertEquals("ja", Captions.pick(noThai, enabled = true, preferredLang = "")?.language)
    }

    // ---- audio tracks ----

    @Test fun audioMenuHiddenWhenOnlyOneTrack() {
        val one = listOf(AudioTracks.Track("orig", "ต้นฉบับ", "en", original = true))
        assertFalse(AudioTracks.showMenu(one))
        assertTrue(AudioTracks.showMenu(one + AudioTracks.Track("th", "ไทย", "th", original = false)))
    }

    @Test fun audioPickUsesPreferredLanguageThenOriginal() {
        val tracks = listOf(
            AudioTracks.Track("en", "English", "en", original = true),
            AudioTracks.Track("th", "ไทย", "th", original = false),
            AudioTracks.Track("ja", "日本語", "ja", original = false),
        )
        assertEquals("th", AudioTracks.pick(tracks, preferredLang = "th")?.language)
        assertEquals("en", AudioTracks.pick(tracks, preferredLang = "ko")?.language)
        assertEquals("en", AudioTracks.pick(tracks, preferredLang = null)?.language)
    }

    @Test fun audioPickFallsBackToFirstWhenNoOriginalAndNoPreference() {
        val tracks = listOf(
            AudioTracks.Track("th", "ไทย", "th", original = false),
            AudioTracks.Track("ja", "日本語", "ja", original = false),
        )
        assertEquals("th", AudioTracks.pick(tracks, preferredLang = "")?.id)
    }

    @Test fun audioGroupsByTrackId() {
        val streams = listOf(
            AudioTracks.Stream("a", "English", "en", original = true),
            AudioTracks.Stream("a", "English", "en", original = true),
            AudioTracks.Stream("b", "ไทย", "th", original = false),
        )
        val groups = AudioTracks.group(streams)
        assertEquals(2, groups.size)
        assertEquals(listOf("a", "b"), groups.map { it.id })
        assertTrue(groups.first().original)
    }

    @Test fun audioLabelMarksOriginalAndDubbed() {
        assertTrue(AudioTracks.label("English", "en", original = true, dubbed = false).contains("ต้นฉบับ"))
        assertTrue(AudioTracks.label("ไทย", "th", original = false, dubbed = true).contains("พากย์"))
    }
}
