package com.bear.ytclassic

/** ⏮: restart the current video, or go back to the previous one. */
object PlaybackNav {
    const val RESTART_AFTER_MS = 5_000L
    fun shouldRestart(positionMs: Long, hasPrev: Boolean): Boolean =
        positionMs > RESTART_AFTER_MS || !hasPrev
}
