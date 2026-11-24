package com.bear.ytclassic

/**
 * NewPipeExtractor 0.27.1 has no season / episode / show APIs (PlaylistType is only
 * NORMAL and MIX_*). A "season" here is a channel playlist; episode numbers are
 * 1-based positions in that playlist.
 */
object SeriesSeasons {
    private val PATTERNS = listOf(
        Regex("""ซีซั[่้]*[น่น][่้]*\s*(?:ที่)?\s*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""season\s*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""(?<!\w)s(\d{1,2})(?!\d)""", RegexOption.IGNORE_CASE),
    )

    data class Season(
        val url: String,
        val title: String,
        val number: Int?,
        val thumb: String?,
        val episodes: List<VideoItem>,
    )

    data class WatchMark(val url: String, val positionMs: Long, val lengthMs: Long, val watchedAt: Long)

    fun parseNumber(title: String): Int? {
        for (p in PATTERNS) {
            val m = p.find(title) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n in 1..99) return n
        }
        return null
    }

    fun seasonLabel(number: Int?, title: String): String = if (number != null) "ซีซัน $number" else title

    fun episodeLabel(season: Int?, episodeIndex: Int): String {
        val ep = episodeIndex + 1
        return if (season != null) "ซีซัน $season ตอน $ep" else "ตอน $ep"
    }

    fun sort(seasons: List<Season>): List<Season> {
        val numbered = seasons.filter { it.number != null }.sortedBy { it.number }
        val rest = seasons.filter { it.number == null }
        return numbered + rest
    }

    fun unfinished(mark: WatchMark): Boolean =
        mark.positionMs > 5_000 && (mark.lengthMs <= 0 || mark.positionMs < mark.lengthMs - 15_000)

    /** Latest unfinished episode across [seasons], or null to start at the first playable one. */
    fun resume(seasons: List<Season>, marks: List<WatchMark>): VideoItem? {
        val open = marks.filter { unfinished(it) }.associateBy { it.url }
        var bestAt = Long.MIN_VALUE
        var best: VideoItem? = null
        for (s in seasons) for (ep in s.episodes) {
            val m = open[ep.url] ?: continue
            if (best == null || m.watchedAt >= bestAt) {
                bestAt = m.watchedAt
                best = ep
            }
        }
        return best
    }

    fun playLabel(resume: VideoItem?): String = if (resume != null) "ดูต่อ" else "เล่นตอน"

    /** Remaining playable episodes from [episodeIndex] of [seasonIndex], then later seasons. */
    fun queueFrom(seasons: List<Season>, seasonIndex: Int, episodeIndex: Int): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        seasons.forEachIndexed { si, s ->
            val start = when {
                si < seasonIndex -> Int.MAX_VALUE
                si == seasonIndex -> episodeIndex.coerceAtLeast(0)
                else -> 0
            }
            s.episodes.forEachIndexed { ei, ep -> if (ei >= start && PlayQueue.isPlayable(ep)) out += ep }
        }
        return out
    }
}
