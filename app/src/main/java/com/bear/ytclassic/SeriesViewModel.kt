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
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor

data class SeriesUi(
    val title: String = "",
    val owner: String? = null,
    val ownerUrl: String? = null,
    val thumb: String? = null,
    val seasons: List<SeriesSeasons.Season> = emptyList(),
    val seasonIndex: Int = 0,
    val resume: VideoItem? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

class SeriesViewModel : ViewModel() {
    private val _ui = MutableStateFlow(SeriesUi())
    val ui: StateFlow<SeriesUi> = _ui
    private val pagers = HashMap<String, ListPager>()
    private var job: Job? = null

    fun load(playlistUrl: String, title: String, channelUrl: String?) {
        job?.cancel()
        job = viewModelScope.launch {
            _ui.value = SeriesUi(title = title, loading = true)
            try {
                val pager = withContext(Dispatchers.IO) { Repo.playlistPager(playlistUrl) }
                pagers[playlistUrl] = pager
                val items = Availability.apply(pager.next())
                val pe = pager.extractor as? PlaylistExtractor
                val name = runCatching { pager.extractor.name }.getOrNull() ?: title
                val owner = pe?.let { runCatching { it.uploaderName }.getOrNull() }
                val ownerUrl = pe?.let { runCatching { it.uploaderUrl }.getOrNull() } ?: channelUrl
                val thumb = pe?.let { runCatching { it.thumbnails.pick(720) }.getOrNull() } ?: items.firstOrNull()?.thumb
                val peCount = pe?.let { runCatching { it.streamCount.toInt() }.getOrDefault(-1) } ?: -1
                val current = SeriesSeasons.Season(playlistUrl, name, SeriesSeasons.parseNumber(name), thumb, items, if (peCount > 0) peCount else items.size)
                var seasons = listOf(current)
                val channel = ownerUrl?.takeIf { it.isNotBlank() }
                if (channel != null) {
                    val others = runCatching { loadChannelPlaylists(channel, current) }.getOrDefault(emptyList())
                    seasons = SeriesSeasons.ofCurrent(current, others)
                }
                val idx = seasons.indexOfFirst { it.url == playlistUrl }.coerceAtLeast(0)
                val resume = SeriesSeasons.resume(seasons, History.marks(seasons.flatMap { it.episodes.map { e -> e.url } }))
                _ui.value = SeriesUi(name, owner, ownerUrl, seasons[idx].thumb ?: thumb, seasons, idx, resume, loading = false)
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                _ui.value = _ui.value.copy(loading = false, error = Errors.list(e))
            }
        }
    }

    fun selectSeason(index: Int) {
        val st = _ui.value
        val season = st.seasons.getOrNull(index) ?: return
        if (index == st.seasonIndex && season.episodes.isNotEmpty()) return
        if (season.episodes.isNotEmpty()) {
            _ui.value = st.copy(seasonIndex = index, thumb = season.thumb ?: st.thumb, resume = SeriesSeasons.resume(st.seasons, marksOf(st.seasons)))
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            _ui.value = st.copy(seasonIndex = index, loading = true, error = null)
            try {
                val loaded = loadSeason(season)
                val seasons = st.seasons.toMutableList().also { it[index] = loaded }
                _ui.value = st.copy(
                    seasons = seasons, seasonIndex = index, thumb = loaded.thumb ?: st.thumb, loading = false,
                    resume = SeriesSeasons.resume(seasons, marksOf(seasons)),
                )
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                _ui.value = _ui.value.copy(loading = false, error = Errors.list(e))
            }
        }
    }

    fun loadMore() {
        val st = _ui.value
        val season = st.seasons.getOrNull(st.seasonIndex) ?: return
        val pager = pagers[season.url] ?: return
        if (job?.isActive == true || !pager.hasMore) return
        job = viewModelScope.launch {
            _ui.value = st.copy(loadingMore = true)
            try {
                val more = Availability.apply(pager.next())
                val seasons = st.seasons.toMutableList()
                seasons[st.seasonIndex] = season.copy(episodes = season.episodes + more)
                _ui.value = st.copy(seasons = seasons, loadingMore = false, resume = SeriesSeasons.resume(seasons, marksOf(seasons)))
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                android.util.Log.w("Series", "next page failed", e)
                _ui.value = _ui.value.copy(loadingMore = false)
            }
        }
    }

    /** Prefetch remaining pages of the chosen season (and later seasons already loaded) before queuing. */
    suspend fun fillForPlayback(seasonIndex: Int) {
        val season = _ui.value.seasons.getOrNull(seasonIndex) ?: return
        val pager = pagers[season.url] ?: return
        var guard = 0
        while (pager.hasMore && guard++ < 8) {
            val more = runCatching { Availability.apply(pager.next()) }.getOrDefault(emptyList())
            if (more.isEmpty()) break
            val st = _ui.value
            val cur = st.seasons.getOrNull(seasonIndex) ?: break
            val seasons = st.seasons.toMutableList()
            seasons[seasonIndex] = cur.copy(episodes = cur.episodes + more)
            _ui.value = st.copy(seasons = seasons)
        }
    }

    private fun marksOf(seasons: List<SeriesSeasons.Season>) = History.marks(seasons.flatMap { s -> s.episodes.map { it.url } })

    private suspend fun loadChannelPlaylists(channelUrl: String, current: SeriesSeasons.Season): List<SeriesSeasons.Season> =
        withContext(Dispatchers.IO) {
            val pager = Repo.channelTab(channelUrl, ChannelTabs.PLAYLISTS)
            val items = ArrayList<VideoItem>()
            var guard = 0
            do { items += pager.next(); guard++ } while (pager.hasMore && guard < 5)
            items.filter { it.kind == 2 && it.url.isNotBlank() }.map { pl ->
                if (pl.url == current.url) current
                else SeriesSeasons.Season(pl.url, pl.title, SeriesSeasons.parseNumber(pl.title), pl.thumb, emptyList(), pl.count.toInt())
            }
        }

    private suspend fun loadSeason(season: SeriesSeasons.Season): SeriesSeasons.Season = withContext(Dispatchers.IO) {
        val pager = Repo.playlistPager(season.url)
        pagers[season.url] = pager
        val items = Availability.apply(pager.next())
        val pe = pager.extractor as? PlaylistExtractor
        val name = runCatching { pager.extractor.name }.getOrNull() ?: season.title
        val thumb = pe?.let { runCatching { it.thumbnails.pick(720) }.getOrNull() } ?: items.firstOrNull()?.thumb ?: season.thumb
        val count = pe?.let { runCatching { it.streamCount.toInt() }.getOrDefault(-1) }?.takeIf { it > 0 } ?: items.size
        season.copy(title = name, number = SeriesSeasons.parseNumber(name) ?: season.number, thumb = thumb, episodes = items, episodeCount = count)
    }
}
