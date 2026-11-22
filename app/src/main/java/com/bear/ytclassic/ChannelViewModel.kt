package com.bear.ytclassic

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

data class ChannelUiState(
    val channel: Channel? = null,
    val banner: String? = null,
    val tabs: List<ChannelTabSpec> = emptyList(),
    val selectedTab: String = ChannelPage.HOME,
    val featured: VideoItem? = null,
    val items: List<VideoItem> = emptyList(),
    val about: ChannelAbout? = null,
    val sortOptions: List<Pair<String, String>> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val emptyMessage: String? = null,
)

class ChannelViewModel : ViewModel() {
    private val _ui = MutableStateFlow(ChannelUiState())
    val ui: StateFlow<ChannelUiState> = _ui
    private var url = ""
    private var handlers = emptyMap<String, ListLinkHandler>()
    private val pagers = HashMap<String, Pager>()
    private val cache = HashMap<String, List<VideoItem>>()
    private var featuredCache: VideoItem? = null
    private var job: Job? = null

    fun load(url: String, name: String, avatar: String?) {
        this.url = url
        handlers = emptyMap()
        pagers.clear()
        cache.clear()
        featuredCache = null
        _ui.value = ChannelUiState(channel = Channel(url, name, avatar), loading = true)
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val info = Repo.channel(url)
                val ch = Channel(url, info.name, info.avatars.pick(176) ?: avatar, info.subscriberCount)
                if (Subs.isSubscribed(url)) Subs.update(ch)
                handlers = info.tabs.mapNotNull { h -> h.contentFilters.firstOrNull()?.let { it to h } }.toMap()
                val tabs = ChannelPage.visibleTabs(handlers.keys.toList())
                val about = ChannelAbout(
                    description = info.description.orEmpty().trim(),
                    subscriberCount = info.subscriberCount,
                    links = ChannelPage.linksFrom(info.description.orEmpty(), info.donationLinks?.toList().orEmpty()),
                )
                val sortOptions = ChannelPage.sortOptions(
                    runCatching { ServiceList.YouTube.channelTabLHFactory.availableSortFilter.toList() }
                        .getOrDefault(emptyList()),
                )
                _ui.value = ChannelUiState(
                    channel = ch,
                    banner = info.banners.pick(400),
                    tabs = tabs,
                    selectedTab = tabs.firstOrNull()?.id ?: ChannelPage.HOME,
                    about = about,
                    sortOptions = sortOptions,
                    loading = true,
                )
                fetchTab(_ui.value.selectedTab)
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                Log.w("Channel", "load failed", e)
                _ui.value = _ui.value.copy(
                    loading = false,
                    error = Errors.list(e).replace("รายการวิดีโอ", "ช่องนี้"),
                )
            }
        }
    }

    fun selectTab(id: String) {
        if (id == _ui.value.selectedTab && !(_ui.value.loading && _ui.value.items.isEmpty() && _ui.value.featured == null)) return
        job?.cancel()
        job = viewModelScope.launch {
            try { fetchTab(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) {
                Log.w("Channel", "tab $id failed", e)
                _ui.value = _ui.value.copy(loading = false, selectedTab = id, error = Errors.list(e).replace("รายการวิดีโอ", "ช่องนี้"))
            }
        }
    }

    private suspend fun fetchTab(id: String) {
        cache[id]?.let { cached ->
            val p = pagers[id]
            _ui.value = _ui.value.copy(
                selectedTab = id,
                featured = if (id == ChannelPage.HOME) featuredCache else null,
                items = cached,
                loading = false,
                loadingMore = false,
                hasMore = p?.hasMore == true,
                error = null,
                emptyMessage = if (cached.isEmpty() && (id != ChannelPage.HOME || featuredCache == null)) emptyText(id) else null,
            )
            return
        }
        _ui.value = _ui.value.copy(selectedTab = id, loading = true, error = null, emptyMessage = null, items = emptyList(), featured = null, hasMore = false)
        if (id == ChannelPage.HOME) {
            val src = homeSource()
            if (src == null) {
                _ui.value = _ui.value.copy(loading = false, emptyMessage = emptyText(id))
                return
            }
            val p = pagerFor(src)
            val items = p.next()
            val featured = items.firstOrNull { PlayQueue.isPlayable(it) } ?: items.firstOrNull()
            featuredCache = featured
            val rest = items.filter { it.url != featured?.url }
            cache[id] = rest
            pagers[id] = p
            _ui.value = _ui.value.copy(
                featured = featured, items = rest, loading = false, hasMore = p.hasMore,
                emptyMessage = if (featured == null && rest.isEmpty()) emptyText(id) else null,
            )
        } else {
            val p = pagerFor(id)
            val items = p.next()
            cache[id] = items
            pagers[id] = p
            _ui.value = _ui.value.copy(
                featured = null, items = items, loading = false, hasMore = p.hasMore,
                emptyMessage = if (items.isEmpty()) emptyText(id) else null,
            )
        }
    }

    fun loadMore() {
        val id = _ui.value.selectedTab
        val p = pagers[id] ?: return
        val st = _ui.value
        if (job?.isActive == true || !p.hasMore || st.loading) return
        job = viewModelScope.launch {
            _ui.value = st.copy(loadingMore = true)
            try {
                val more = p.next()
                val all = (_ui.value.items + more)
                cache[id] = all
                _ui.value = _ui.value.copy(items = all, loadingMore = false, hasMore = p.hasMore)
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                Log.w("Channel", "next page failed", e)
                _ui.value = _ui.value.copy(loadingMore = false)
            }
        }
    }

    private fun homeSource(): String? = when {
        ChannelTabs.VIDEOS in handlers -> ChannelTabs.VIDEOS
        ChannelTabs.LIVESTREAMS in handlers -> ChannelTabs.LIVESTREAMS
        ChannelTabs.SHORTS in handlers -> ChannelTabs.SHORTS
        else -> null
    }

    private fun pagerFor(id: String): Pager {
        val h = handlers[id]
        return if (h != null) Repo.channelTab(h) else Repo.channelTab(url, id)
    }

    private fun emptyText(id: String) = when (id) {
        ChannelTabs.SHORTS -> "ช่องนี้ยังไม่มี Shorts"
        ChannelTabs.PLAYLISTS -> "ช่องนี้ยังไม่มีเพลย์ลิสต์"
        ChannelTabs.LIVESTREAMS -> "ช่องนี้ยังไม่มีไลฟ์สด"
        ChannelTabs.PODCASTS -> "ช่องนี้ยังไม่มีพอดแคสต์"
        else -> "ช่องนี้ยังไม่มีวิดีโอ"
    }
}
