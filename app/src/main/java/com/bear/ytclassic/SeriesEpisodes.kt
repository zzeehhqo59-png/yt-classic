package com.bear.ytclassic

/**
 * Episode logic for the playlist ("series") page. NewPipeExtractor 0.27.1 has no season / show
 * APIs, so a series is just one playlist and an episode number is its 1-based position in it.
 * (The guessed season system was removed in 6.0.4.)
 */
object SeriesEpisodes {
    data class WatchMark(val url: String, val positionMs: Long, val lengthMs: Long, val watchedAt: Long)

    fun episodeLabel(episodeIndex: Int): String = "ตอนที่ ${episodeIndex + 1}"

    fun unfinished(mark: WatchMark): Boolean =
        mark.positionMs > 5_000 && (mark.lengthMs <= 0 || mark.positionMs < mark.lengthMs - 15_000)

    /** Latest unfinished episode, or null to start at the first playable one. */
    fun resume(episodes: List<VideoItem>, marks: List<WatchMark>): VideoItem? {
        val open = marks.filter { unfinished(it) }.associateBy { it.url }
        var bestAt = Long.MIN_VALUE
        var best: VideoItem? = null
        for (ep in episodes) {
            val m = open[ep.url] ?: continue
            if (best == null || m.watchedAt >= bestAt) {
                bestAt = m.watchedAt
                best = ep
            }
        }
        return best
    }

    fun playLabel(resume: VideoItem?): String = if (resume != null) "ดูต่อ" else "เล่นตอน"

    /** Index the play button starts from: the resume episode if it is in the list, else 0. */
    fun startIndex(episodes: List<VideoItem>, resume: VideoItem?): Int =
        resume?.let { r -> episodes.indexOfFirst { it.url == r.url } }?.takeIf { it >= 0 } ?: 0

    /** Remaining playable episodes from [episodeIndex] so autoplay continues to the next one. */
    fun queueFrom(episodes: List<VideoItem>, episodeIndex: Int): List<VideoItem> {
        val start = episodeIndex.coerceAtLeast(0)
        return episodes.filterIndexed { i, ep -> i >= start && PlayQueue.isPlayable(ep) }
    }
}
