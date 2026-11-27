package com.bear.ytclassic

import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.exoplayer2.Player

/**
 * Keeps the screen on while a video is actually playing, on the window of the activity that hosts the
 * watch overlay / mini bar, so it works the same in portrait, fullscreen, landscape, PiP and the mini bar.
 * Before 6.0.6 nothing set keepScreenOn or FLAG_KEEP_SCREEN_ON anywhere (ExoPlayer 2's StyledPlayerView
 * does not do it by itself), so the normal screen timeout turned the screen off mid-video.
 */
object ScreenAwake {
    fun shouldKeepOn(playWhenReady: Boolean, playbackState: Int, suppressed: Boolean, hasError: Boolean): Boolean =
        playWhenReady && !suppressed && !hasError &&
            (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

    fun bind(activity: AppCompatActivity) {
        activity.lifecycle.addObserver(Binder(activity))
    }

    private class Binder(private val activity: AppCompatActivity) : DefaultLifecycleObserver, Player.Listener {
        override fun onStart(owner: LifecycleOwner) {
            Playback.player.addListener(this)
            apply(Playback.player)
        }

        override fun onStop(owner: LifecycleOwner) {
            Playback.player.removeListener(this)
            set(false)
        }

        override fun onDestroy(owner: LifecycleOwner) { owner.lifecycle.removeObserver(this) }

        override fun onEvents(player: Player, events: Player.Events) { apply(player) }

        private fun apply(p: Player) = set(shouldKeepOn(
            p.playWhenReady, p.playbackState,
            p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE, p.playerError != null,
        ))

        private fun set(on: Boolean) {
            val w = activity.window ?: return
            if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
