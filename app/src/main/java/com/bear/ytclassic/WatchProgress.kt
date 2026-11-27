package com.bear.ytclassic

/** Local watch position: resume, the red thumbnail bar (0..1000), and "nearly finished" as watched. */
object WatchProgress {
    const val MIN_RESUME_MS = 5_000L
    const val FINISH_REMAINING_MS = 15_000L

    fun isFinished(positionMs: Long, lengthMs: Long): Boolean =
        lengthMs > 0 && positionMs >= (lengthMs - FINISH_REMAINING_MS).coerceAtLeast(0)

    fun resumeAt(positionMs: Long, lengthMs: Long): Long =
        if (positionMs > MIN_RESUME_MS && !isFinished(positionMs, lengthMs)) positionMs else 0

    fun bar(positionMs: Long, lengthMs: Long): Int = when {
        lengthMs <= 0 || positionMs <= 0 -> 0
        isFinished(positionMs, lengthMs) -> 1000
        else -> (positionMs * 1000 / lengthMs).toInt().coerceIn(1, 999)
    }

    /** Position/length to persist: snap a nearly finished watch to the end. */
    fun storedPosition(positionMs: Long, lengthMs: Long): Pair<Long, Long> =
        if (lengthMs > 0 && isFinished(positionMs, lengthMs)) lengthMs to lengthMs else positionMs to lengthMs
}
