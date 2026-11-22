package com.bear.ytclassic

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ChannelUiState(
    val channel: Channel? = null,
    val banner: String? = null,
    val description: String = "",
    val videos: List<VideoItem> = emptyList(),
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
    private var pager: Pager? = null
    private var job: Job? = null

    fun load(url: String, name: String, avatar: String?) {
        this.url = url
        _ui.value = ChannelUiState(channel = Channel(url, name, avatar), loading = true)
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val info = Repo.channel(url)
                val ch = Channel(url, info.name, info.avatars.pick(176) ?: avatar, info.subscriberCount)
                if (Subs.isSubscribed(url)) Subs.update(ch)
                val p = Repo.channelVideos(url)
                pager = p
                val videos = p.next()
                _ui.value = ChannelUiState(
                    channel = ch,
                    banner = info.banners.pick(400),
                    description = info.description.orEmpty().trim(),
                    videos = videos,
                    hasMore = p.hasMore,
                    emptyMessage = if (videos.isEmpty()) "ช่องนี้ยังไม่มีวิดีโอ" else null,
                )
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

    fun loadMore() {
        val p = pager ?: return
        val st = _ui.value
        if (job?.isActive == true || !p.hasMore || st.loading) return
        job = viewModelScope.launch {
            _ui.value = st.copy(loadingMore = true)
            try {
                val more = p.next()
                _ui.value = _ui.value.copy(videos = _ui.value.videos + more, loadingMore = false, hasMore = p.hasMore)
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                Log.w("Channel", "next page failed", e)
                _ui.value = _ui.value.copy(loadingMore = false)
            }
        }
    }
}
