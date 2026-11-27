package com.bear.ytclassic

import com.google.android.exoplayer2.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Features606Test {
    private fun keep(playWhenReady: Boolean = true, state: Int = Player.STATE_READY, suppressed: Boolean = false, error: Boolean = false) =
        ScreenAwake.shouldKeepOn(playWhenReady, state, suppressed, error)

    @Test fun screenStaysOnOnlyWhilePlaybackIsActive() {
        assertTrue(keep())                                   // playing (portrait, fullscreen, landscape or mini: same window)
        assertTrue(keep(state = Player.STATE_BUFFERING))     // short rebuffer mid-play must not let the screen dim
        assertFalse(keep(playWhenReady = false))             // paused
        assertFalse(keep(playWhenReady = false, state = Player.STATE_BUFFERING))
        assertFalse(keep(state = Player.STATE_ENDED))        // ended
        assertFalse(keep(state = Player.STATE_IDLE))         // closed / stopped / failed to idle
        assertFalse(keep(error = true))                      // error
        assertFalse(keep(suppressed = true))                 // lost audio focus (e.g. a call)
    }
}
