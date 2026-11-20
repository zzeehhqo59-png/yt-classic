package com.bear.ytclassic

/** "เล่นทั้งหมด" queue of a playlist: next / autoplay walk it in order and skip videos that can't be played. */
object PlayQueue {
    private var items: List<VideoItem> = emptyList()
    private var index = -1

    val active get() = index >= 0

    /** Start the queue at [start] (or the first playable video after it); returns the video to open. */
    fun start(list: List<VideoItem>, start: Int = 0): VideoItem? {
        val i = nextPlayableIndex(list, start - 1)
        if (i < 0) { clear(); return null }
        items = list; index = i
        return list[i]
    }

    /** Called for every opened video: keeps the position when it is in the queue, otherwise leaves the queue. */
    fun onOpen(url: String) {
        if (!active) return
        val i = items.indexOfFirst { it.url == url }
        if (i < 0) clear() else index = i
    }

    fun peekNext(): VideoItem? = if (!active) null else nextPlayableIndex(items, index).takeIf { it >= 0 }?.let { items[it] }

    fun clear() { items = emptyList(); index = -1 }

    /** First index after [from] whose video can be played (not deleted / private / members-only / upcoming), or -1. */
    fun nextPlayableIndex(list: List<VideoItem>, from: Int): Int {
        for (i in (from + 1).coerceAtLeast(0) until list.size) if (isPlayable(list[i])) return i
        return -1
    }

    fun isPlayable(v: VideoItem) = v.kind == 0 && v.unplayableBadge() == null && !v.live
}
