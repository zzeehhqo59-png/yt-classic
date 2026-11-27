package com.bear.ytclassic

import com.google.android.exoplayer2.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Features608Test {
    private val main = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private fun src(path: String) = File(main, path).readText()

    // ---- 1: search result taps open the row's own item ----

    @Test fun onlyTheAvatarOpensTheChannelNotTheRowsChannelLine() {
        assertTrue(VideoClicks.opensChannel(VideoClicks.Part.AVATAR))
        assertFalse(VideoClicks.opensChannel(VideoClicks.Part.CHANNEL_NAME))   // the 6.0.7 bug: middle of a search row opened the channel
        assertFalse(VideoClicks.opensChannel(VideoClicks.Part.ROW))
        // compact rows (search) have the channel line but no avatar, so nothing in them may open a channel by itself
        val compact = src("res/layout/item_video_compact.xml")
        assertTrue(compact.contains("@+id/channel")); assertFalse(compact.contains("@+id/avatar"))
    }

    // ---- 2: stuck spinner ----

    @Test fun spinnerHidesWhenThePlayerIsReadyOrFailed() {
        val loading = Playback.State(loading = true)
        assertTrue(WatchSpinner.visible(loading, Player.STATE_BUFFERING))
        assertTrue(WatchSpinner.visible(loading, Player.STATE_IDLE))
        assertFalse(WatchSpinner.visible(loading, Player.STATE_READY))          // video already playing: no spinner
        assertFalse(WatchSpinner.visible(loading.copy(error = "x"), Player.STATE_IDLE))
        assertFalse(WatchSpinner.visible(Playback.State(), Player.STATE_BUFFERING))
    }

    @Test fun newWatchLayerStartsWithoutSpinnerAndSyncsOnShow() {
        val layout = src("res/layout/activity_watch.xml")
        val tag = Regex("""<ProgressBar[^>]*@\+id/playerLoading[^>]*>""").find(layout)?.value.orEmpty()
        assertTrue(tag, tag.contains("""android:visibility="gone""""))
        val show = src("java/com/bear/ytclassic/WatchLayer.kt").substringAfter("private fun show(").substringBefore("private fun hide(")
        assertTrue(show.contains("render(Playback.state.value)"))
    }

    // ---- 3: no long-press anywhere; ⋮ / × instead ----

    @Test fun noLongPressListenersLeft() {
        val offenders = main.walk().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { f -> f.readText().let { it.contains("setOnLongClickListener") || it.contains("OnLongClickListener") || it.contains("longClickable=\"true\"") } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun menuOnlyOnUserPlaylists() {
        val mine = LibraryModel.Row(LibraryModel.Kind.PLAYLIST, "เพลง", "3 วิดีโอ", null, 7L, editable = true)
        assertTrue(LibraryModel.hasMenu(mine))
        assertFalse(LibraryModel.hasMenu(mine.copy(kind = LibraryModel.Kind.WATCH_LATER, editable = false)))
        assertFalse(LibraryModel.hasMenu(mine.copy(kind = LibraryModel.Kind.LIKED, editable = false, playlistId = null)))
        assertFalse(LibraryModel.hasMenu(mine.copy(kind = LibraryModel.Kind.DOWNLOADS, editable = false, playlistId = null)))
        assertFalse(LibraryModel.hasMenu(mine.copy(kind = LibraryModel.Kind.CREATE, editable = false, playlistId = null)))
        assertTrue(src("res/layout/item_download.xml").contains("@+id/more"))
        assertTrue(src("res/layout/item_suggestion.xml").contains("@+id/remove"))
    }
}
