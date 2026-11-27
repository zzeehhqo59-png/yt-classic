package com.bear.ytclassic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor

data class SeriesUi(
    val title: String = "",
    val owner: String? = null,
    val thumb: String? = null,
    val episodes: List<VideoItem> = emptyList(),
    val resume: VideoItem? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

/** One playlist shown as a series: hero, play/resume button and numbered episodes. */
class SeriesViewModel : ViewModel() {
    private val _ui = MutableStateFlow(SeriesUi())
    val ui: StateFlow<SeriesUi> = _ui
    private var pager: ListPager? = null
    private var job: Job? = null

    fun load(playlistUrl: String, title: String) {
        job?.cancel()
        job = viewModelScope.launch {
            _ui.value = SeriesUi(title = title, loading = true)
            try {
                val p = withContext(Dispatchers.IO) { Repo.playlistPager(playlistUrl) }
                pager = p
                val items = Availability.apply(p.next())
                val pe = p.extractor as? PlaylistExtractor
                val name = runCatching { p.extractor.name }.getOrNull() ?: title
                val owner = pe?.let { runCatching { it.uploaderName }.getOrNull() }
                val thumb = pe?.let { runCatching { it.thumbnails.pick(720) }.getOrNull() } ?: items.firstOrNull()?.thumb
                _ui.value = SeriesUi(name, owner, thumb, items, resumeOf(items), loading = false)
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                _ui.value = _ui.value.copy(loading = false, error = Errors.list(e))
            }
        }
    }

    /** Re-read watch marks (after returning from the player). */
    fun refreshResume() {
        val eps = _ui.value.episodes
        if (eps.isEmpty()) return
        viewModelScope.launch {
            val resume = resumeOf(eps)
            _ui.value = _ui.value.copy(resume = resume)
        }
    }

    fun loadMore() {
        val p = pager ?: return
        if (job?.isActive == true || !p.hasMore) return
        job = viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingMore = true)
            try {
                val more = Availability.apply(p.next())
                val eps = _ui.value.episodes + more
                _ui.value = _ui.value.copy(episodes = eps, loadingMore = false, resume = resumeOf(eps))
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                android.util.Log.w("Series", "next page failed", e)
                _ui.value = _ui.value.copy(loadingMore = false)
            }
        }
    }

    /** Prefetch remaining pages (bounded) so autoplay can run through the whole playlist. */
    suspend fun fillForPlayback() {
        val p = pager ?: return
        var guard = 0
        while (p.hasMore && guard++ < MAX_PREFETCH_PAGES) {
            val more = runCatching { Availability.apply(p.next()) }.getOrDefault(emptyList())
            if (more.isEmpty()) break
            _ui.value = _ui.value.copy(episodes = _ui.value.episodes + more)
        }
    }

    private suspend fun resumeOf(episodes: List<VideoItem>): VideoItem? = withContext(Dispatchers.IO) {
        SeriesEpisodes.resume(episodes, History.marks(episodes.map { it.url }))
    }

    private companion object { const val MAX_PREFETCH_PAGES = 8 }
}
