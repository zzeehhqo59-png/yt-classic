package com.bear.ytclassic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File

/** Immutable watch-page state derived from [Playback]. */
data class WatchUiState(
    val item: VideoItem? = null,
    val info: StreamInfo? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Holds watch-page state across rotation and issues playback commands.
 * The Activity only renders [ui] and forwards user events here.
 */
class WatchViewModel : ViewModel() {
    val ui: StateFlow<WatchUiState> = Playback.state
        .map { WatchUiState(it.item, it.info, it.loading, it.error) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Playback.state.value.let {
            WatchUiState(it.item, it.info, it.loading, it.error)
        })

    /** How [open] should refresh the header / related list (UI stays in the Activity). */
    enum class OpenKind { NewFile, NewStream, Reuse, Unchanged }

    /**
     * Open a video from an intent. [item] may be null when the notification re-opens the one already playing.
     * @return the item to show and how the page should bind, or null if there is nothing to show
     */
    /** Decide how to open without touching the player yet, so the Activity can reset the UI first. */
    fun planOpen(item: VideoItem?, file: File?): Pair<VideoItem, OpenKind>? {
        val v = item ?: Playback.state.value.item ?: return null
        val kind = when {
            file != null && (Playback.state.value.item?.url != v.url || !Playback.offline) -> OpenKind.NewFile
            file != null -> OpenKind.Unchanged
            Playback.state.value.item?.url == v.url -> OpenKind.Reuse
            else -> OpenKind.NewStream
        }
        return v to kind
    }

    fun applyOpen(item: VideoItem, file: File?, kind: OpenKind) {
        when (kind) {
            OpenKind.NewFile -> Playback.openFile(item, file!!)
            OpenKind.NewStream -> Playback.open(item)
            OpenKind.Reuse, OpenKind.Unchanged -> {}
        }
    }

    /** Toggle 👍 / 👎 and return the toast to show. */
    fun rate(item: VideoItem, value: Int): String {
        val v = Playback.state.value.item?.takeIf { it.url == item.url } ?: item
        return Likes.message(Likes.toggle(v, value), value)
    }

    fun downloadInfo(item: VideoItem): StreamInfo? =
        Playback.state.value.info?.takeIf { it.url == item.url || Playback.state.value.item?.url == item.url }
}
