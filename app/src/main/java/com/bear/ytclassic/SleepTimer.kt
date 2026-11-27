package com.bear.ytclassic

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Sleep timer for the player menu. Fires on the main looper so it still pauses
 * during background playback while the foreground service is running.
 */
object SleepTimer {
    sealed interface State
    data object Off : State
    data object EndOfVideo : State
    data class Minutes(val untilElapsed: Long, val minutes: Int = 0) : State

    val MINUTE_OPTIONS = listOf(15, 30, 45, 60)

    var current: State = Off
        private set

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var onFire: (() -> Unit)? = null
    private val fireGo = Runnable { fire() }

    fun init(onFire: () -> Unit) { this.onFire = onFire }

    fun summary(state: State = current, now: Long = SystemClock.elapsedRealtime()): String = when (state) {
        is Off -> "ปิด"
        is EndOfVideo -> "ถึงจบตอนนี้"
        is Minutes -> {
            val left = ((state.untilElapsed - now + 59_999L) / 60_000L).coerceAtLeast(1)
            "อีก $left นาที"
        }
    }

    fun shouldFire(state: State, now: Long): Boolean = state is Minutes && now >= state.untilElapsed

    fun shouldPauseAtEnd(state: State = current): Boolean = state is EndOfVideo

    fun optionIndex(state: State = current): Int = when (state) {
        is Off -> 0
        is Minutes -> 1 + MINUTE_OPTIONS.indexOf(state.minutes).let { if (it >= 0) it else 0 }
        is EndOfVideo -> MINUTE_OPTIONS.size + 1
    }

    fun applyOption(index: Int) {
        when {
            index <= 0 -> clear()
            index > MINUTE_OPTIONS.size -> setEndOfVideo()
            else -> setMinutes(MINUTE_OPTIONS[index - 1])
        }
    }

    fun setMinutes(minutes: Int, now: Long = SystemClock.elapsedRealtime()) {
        handler.removeCallbacks(fireGo)
        current = Minutes(now + minutes * 60_000L, minutes)
        handler.postDelayed(fireGo, minutes * 60_000L)
    }

    fun setEndOfVideo() {
        handler.removeCallbacks(fireGo)
        current = EndOfVideo
    }

    fun clear() {
        handler.removeCallbacks(fireGo)
        current = Off
    }

    /** True if the ended video should pause (and not autoplay). */
    fun onVideoEnded(): Boolean {
        if (!shouldPauseAtEnd()) return false
        fire()
        return true
    }

    private fun fire() {
        handler.removeCallbacks(fireGo)
        current = Off
        onFire?.invoke()
    }
}
