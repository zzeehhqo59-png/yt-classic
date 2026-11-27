package com.bear.ytclassic

import com.google.android.exoplayer2.Player

/** When the red loading spinner over the video shows. Pure, so it can be unit tested. */
object WatchSpinner {
    /** [Playback.State.loading] while the player is not yet READY; never over an error or a playing (READY) video. */
    fun visible(st: Playback.State, playbackState: Int): Boolean =
        st.loading && st.error == null && playbackState != Player.STATE_READY
}
