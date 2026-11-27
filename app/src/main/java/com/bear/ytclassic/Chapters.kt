package com.bear.ytclassic

import org.schabi.newpipe.extractor.stream.StreamSegment

/** Video chapters from StreamInfo.streamSegments (NewPipeExtractor 0.27.1) and description timestamps. Pure logic. */
object Chapters {
    data class Chapter(val title: String, val startMs: Long, val thumb: String?)
    data class Stamp(val start: Int, val end: Int, val ms: Long)

    fun from(segments: List<StreamSegment>?): List<Chapter> =
        of(segments.orEmpty().map { Triple(it.title.orEmpty(), it.startTimeSeconds * 1000L, it.previewUrl) })

    /** Sorted, de-duplicated by start; fewer than 2 chapters means "no chapters" (nothing is shown). */
    fun of(raw: List<Triple<String, Long, String?>>): List<Chapter> {
        val list = raw.filter { it.second >= 0 }.distinctBy { it.second }.sortedBy { it.second }
            .map { (t, ms, thumb) -> Chapter(t.trim(), ms, thumb?.takeIf { it.isNotBlank() }) }
        return if (list.size >= 2) list else emptyList()
    }

    fun indexAt(chapters: List<Chapter>, posMs: Long): Int = chapters.indexOfLast { it.startMs <= posMs }.coerceAtLeast(0)

    /** Chapter start positions (ms) where the seek bar gets a gap; the first chapter (at 0) has none. */
    fun gapStarts(chapters: List<Chapter>, durationMs: Long): List<Long> =
        chapters.map { it.startMs }.filter { it > 0 && (durationMs <= 0 || it < durationMs) }

    /** [from, to) ranges of the bar between gaps. */
    fun ranges(gaps: List<Long>, durationMs: Long): List<LongRange> {
        if (durationMs <= 0) return emptyList()
        val cuts = listOf(0L) + gaps.filter { it in 1 until durationMs }.sorted().distinct() + durationMs
        return cuts.zipWithNext { a, b -> a until b }
    }

    private val STAMP = Regex("""(?<![\d:])(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?![\d:])""")

    /** Timestamps like 12:30 or 1:02:03 in a description; ones past [durationMs] (if known) are skipped. */
    fun timestamps(text: CharSequence, durationMs: Long = 0): List<Stamp> = STAMP.findAll(text).mapNotNull { m ->
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        if (sec >= 60 || (m.groupValues[1].isNotEmpty() && min >= 60)) return@mapNotNull null
        val ms = ((h * 60 + min) * 60 + sec) * 1000
        if (durationMs > 0 && ms > durationMs) null else Stamp(m.range.first, m.range.last + 1, ms)
    }.toList()
}
