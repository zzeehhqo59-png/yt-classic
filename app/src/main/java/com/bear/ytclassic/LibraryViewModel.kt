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

data class LibraryUi(
    val snapshot: LibraryModel.Snapshot = LibraryModel.Snapshot(),
    val downloads: List<Downloads.Dl> = emptyList(),
    val filter: LibraryModel.Filter = LibraryModel.Filter.ALL,
    val sort: LibraryModel.Sort = LibraryModel.Sort.RECENT,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
) {
    val filters get() = LibraryModel.filters(snapshot)
    val rows get() = LibraryModel.rows(snapshot, filter, sort)
    val shownDownloads: List<Downloads.Dl> get() {
        val ids = LibraryModel.downloads(snapshot, filter).map { it.id }.toSet()
        return downloads.filter { it.id in ids }
    }
}

/** State of the คุณ page; every read of the local database happens on the IO dispatcher. */
class LibraryViewModel : ViewModel() {
    private val _ui = MutableStateFlow(LibraryUi())
    val ui: StateFlow<LibraryUi> = _ui
    private var job: Job? = null

    fun refresh(userPulled: Boolean = false) {
        job?.cancel()
        if (userPulled) _ui.value = _ui.value.copy(refreshing = true)
        job = viewModelScope.launch {
            try {
                val (snap, dls) = withContext(Dispatchers.IO) { load() }
                val sort = withContext(Dispatchers.IO) { LibraryModel.Sort.fromKey(Prefs.librarySort) }
                val cur = _ui.value
                _ui.value = cur.copy(snapshot = snap, downloads = dls, filter = LibraryModel.effective(snap, cur.filter), sort = sort,
                    refreshing = false, loaded = true, error = null)
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                android.util.Log.w("Library", "load failed", e)
                _ui.value = _ui.value.copy(refreshing = false, error = "โหลดคลังวิดีโอไม่สำเร็จ")
            }
        }
    }

    fun select(filter: LibraryModel.Filter) {
        val cur = _ui.value
        _ui.value = cur.copy(filter = LibraryModel.effective(cur.snapshot, filter))
    }

    /** Picking a sort shows all collections in that order and remembers the choice. */
    fun setSort(sort: LibraryModel.Sort) {
        _ui.value = _ui.value.copy(sort = sort, filter = LibraryModel.Filter.ALL)
        viewModelScope.launch(Dispatchers.IO) { Prefs.librarySort = sort.name }
    }

    fun renamePlaylist(id: Long, name: String) = write { Playlists.rename(id, name) }
    fun deletePlaylist(id: Long) = write { Playlists.delete(id) }
    fun createPlaylist(name: String) = write { Playlists.create(name) }

    private fun write(block: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { block() }
            refresh()
        }
    }

    private fun load(): Pair<LibraryModel.Snapshot, List<Downloads.Dl>> {
        val all = Playlists.all().map { LibraryModel.PlaylistInfo(it.id, it.name, it.count, it.thumb, it.latest) }
        val dls = Downloads.all()
        val snap = LibraryModel.Snapshot(
            history = History.recent(HISTORY_ROW).map { it.video },
            watchLater = all.firstOrNull { it.id == Playlists.WATCH_LATER },
            likedCount = Likes.count(),
            likedCover = Likes.liked().firstOrNull()?.thumb,
            likedLatest = Likes.latest(),
            playlists = all.filter { it.id != Playlists.WATCH_LATER },
            downloads = dls.map { LibraryModel.DownloadInfo(it.id, it.thumb, it.audioOnly, it.created) },
            subscriptions = Subs.count(),
            watchedCount = History.count(),
        )
        return snap to dls
    }

    private companion object { const val HISTORY_ROW = 20 }
}
