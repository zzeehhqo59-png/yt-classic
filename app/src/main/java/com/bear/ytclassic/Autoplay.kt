package com.bear.ytclassic

/** Countdown before the next video starts when the current one ends. */
object Autoplay {
    const val DELAY_MS = 5_000L

    data class Offer(val next: VideoItem, val startedAtMs: Long, val delayMs: Long = DELAY_MS) {
        fun remainingMs(nowMs: Long): Long = (startedAtMs + delayMs - nowMs).coerceAtLeast(0L)
        fun expired(nowMs: Long): Boolean = remainingMs(nowMs) == 0L
    }

    /**
     * Video to start after the current one ends: the play queue always continues;
     * a related video only continues when autoplay is on. Offline / live never auto-advance.
     */
    fun nextAfterEnd(
        autoplayOn: Boolean,
        offline: Boolean,
        live: Boolean,
        queued: VideoItem?,
        related: VideoItem?,
    ): VideoItem? {
        if (offline || live) return null
        queued?.let { return it }
        return if (autoplayOn) related else null
    }
}
