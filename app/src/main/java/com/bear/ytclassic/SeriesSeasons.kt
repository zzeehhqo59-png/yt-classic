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
        val episodeCount: Int = 0,
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


    private val BRACKETS = Regex("""[\[【(（][^\]】)）]*[\]】)）]""")
    private val EPISODE = Regex("""(?:ตอนที่?|episode|ep\.?)\s*\d+""", RegexOption.IGNORE_CASE)
    private val SEASON_STRIP = Regex(
        """(?:ซีซั[่้]*[น่น][่้]*\s*(?:ที่)?\s*\d+|season\s*\d+|(?<!\w)s\d{1,2}(?!\d))""",
        RegexOption.IGNORE_CASE,
    )
    private val EDITION_WORDS = Regex("""พากย์ไทย|ซับไทย|พากย์|ซับ|\bdub\b|\bsub\b""", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

    fun editionOf(title: String): String {
        val t = title.lowercase()
        return when {
            "พากย์" in t || Regex("""\bdub\b""", RegexOption.IGNORE_CASE).containsMatchIn(title) -> "dub"
            "ซับ" in t || Regex("""\bsub\b""", RegexOption.IGNORE_CASE).containsMatchIn(title) -> "sub"
            else -> ""
        }
    }

    fun baseTitle(title: String): String {
        var s = title
        s = BRACKETS.replace(s, " ")
        s = SEASON_STRIP.replace(s, " ")
        s = EPISODE.replace(s, " ")
        s = EDITION_WORDS.replace(s, " ")
        return NON_ALNUM.replace(s, "").lowercase()
    }

    fun sameSeries(a: String, b: String): Boolean {
        val left = baseTitle(a)
        val right = baseTitle(b)
        if (left.isBlank() || right.isBlank()) return false
        return left == right && editionOf(a) == editionOf(b)
    }

    /** Playlists of the same series (and same dub/sub), missing numbers count as season 1, duplicates dropped. */
    fun ofCurrent(current: Season, candidates: List<Season>): List<Season> {
        val preferred = current.url
        val pool = LinkedHashMap<String, Season>()
        fun keep(s: Season) {
            val count = if (s.episodeCount > 0) s.episodeCount else s.episodes.size
            val next = if (count != s.episodeCount) s.copy(episodeCount = count) else s
            val prev = pool[next.url]
            if (prev == null || (prev.episodes.isEmpty() && next.episodes.isNotEmpty()) || next.episodeCount > prev.episodeCount) {
                pool[next.url] = next
            }
        }
        keep(current)
        candidates.forEach { keep(it) }
        val matched = pool.values.filter { it.url == preferred || sameSeries(current.title, it.title) }
            .map { it.copy(number = parseNumber(it.title) ?: 1) }
        val byNum = LinkedHashMap<Int, Season>()
        for (s in matched) {
            val n = s.number ?: 1
            val prev = byNum[n]
            byNum[n] = when {
                prev == null -> s
                s.url == preferred -> s
                prev.url == preferred -> prev
                s.episodes.isNotEmpty() && prev.episodes.isEmpty() -> s
                s.episodeCount > prev.episodeCount -> s
                else -> prev
            }
        }
        return sort(byNum.values.toList())
    }

    fun showSeasonPicker(seasons: List<Season>): Boolean = seasons.size > 1

    fun sheetLabel(season: Season): String {
        val name = seasonLabel(season.number, season.title)
        val n = if (season.episodeCount > 0) season.episodeCount else season.episodes.size
        return if (n > 0) "$name · $n ตอน" else name
    }
}
