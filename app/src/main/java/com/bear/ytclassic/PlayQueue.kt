package com.bear.ytclassic

/**
 * Playback queue: a "เล่นทั้งหมด" playlist walk plus videos the user added
 * with "เพิ่มลงในคิว" / "เล่นเป็นเพลงถัดไป". The user queue is always tried first.
 */
object PlayQueue {
    private var items: List<VideoItem> = emptyList()
    private var index = -1
    private val user = ArrayList<VideoItem>()

    val active get() = index >= 0 || user.isNotEmpty()

    /** Start the queue at [start] (or the first playable video after it); returns the video to open. */
    fun start(list: List<VideoItem>, start: Int = 0): VideoItem? {
        user.clear()
        val i = nextPlayableIndex(list, start - 1)
        if (i < 0) { clear(); return null }
        items = list; index = i
        return list[i]
    }

    /** Called for every opened video: keeps the playlist position, or leaves the playlist; user items stay unless opened. */
    fun onOpen(url: String) {
        val ui = user.indexOfFirst { it.url == url }
        if (ui >= 0) {
            repeat(ui + 1) { if (user.isNotEmpty()) user.removeAt(0) }
            return // a user-queue item sits between playlist videos; keep the playlist position
        }
        if (index >= 0) {
            val i = items.indexOfFirst { it.url == url }
            if (i < 0) { items = emptyList(); index = -1 } else index = i
        }
    }

    fun peekNext(): VideoItem? {
        user.firstOrNull { isPlayable(it) }?.let { return it }
        if (index < 0) return null
        return nextPlayableIndex(items, index).takeIf { it >= 0 }?.let { items[it] }
    }

    fun extras(): List<VideoItem> = user.toList()

    fun add(v: VideoItem): Boolean {
        if (!isPlayable(v)) return false
        if (user.any { it.url == v.url }) return true
        user.add(v)
        return true
    }

    fun playNext(v: VideoItem): Boolean {
        if (!isPlayable(v)) return false
        user.removeAll { it.url == v.url }
        user.add(0, v)
        return true
    }

    fun removeExtra(url: String) { user.removeAll { it.url == url } }

    fun clear() { items = emptyList(); index = -1; user.clear() }

    /** First index after [from] whose video can be played (not deleted / private / members-only / upcoming), or -1. */
    fun nextPlayableIndex(list: List<VideoItem>, from: Int): Int {
        for (i in (from + 1).coerceAtLeast(0) until list.size) if (isPlayable(list[i])) return i
        return -1
    }

    fun isPlayable(v: VideoItem) = v.kind == 0 && v.unplayableBadge() == null && !v.live
}
