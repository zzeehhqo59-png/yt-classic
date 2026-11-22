package com.bear.ytclassic

import android.util.TypedValue
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import com.google.android.exoplayer2.ui.StyledPlayerView

/** Captions and audio-track menus for the watch page player. */
class WatchTracks(private val a: AppCompatActivity, private val playerView: StyledPlayerView) {
    private val player get() = Playback.player
    private var applying = false

    fun textGroups() = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
    fun captionsOn() = textGroups().any { it.isSelected } && !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

    fun captionLabel(g: com.google.android.exoplayer2.Tracks.Group): String {
        val f = g.getTrackFormat(0)
        return f.label ?: f.language?.let { Captions.displayLanguage(it) } ?: "คำบรรยาย"
    }

    fun applyFont() {
        playerView.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, Captions.fontSp(Prefs.ccFontSize))
    }

    fun applyPrefs() {
        if (applying) return
        applying = true
        try {
            applyFont()
            val groups = textGroups()
            val tracks = groups.map { g ->
                val f = g.getTrackFormat(0)
                val auto = (f.label ?: "").contains("สร้างอัตโนมัติ") || (f.label ?: "").contains("auto", ignoreCase = true)
                Captions.Track(f.language, captionLabel(g), auto)
            }
            val pick = Captions.pick(tracks, Prefs.ccEnabled, Prefs.ccLanguage)
            if (pick == null) select(null, save = false)
            else groups.getOrNull(tracks.indexOf(pick))?.let { select(it, save = false) }
        } finally { applying = false }
    }

    fun select(g: com.google.android.exoplayer2.Tracks.Group?, save: Boolean = true) {
        val b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (g == null) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
        player.trackSelectionParameters = b.build()
        if (save) {
            Prefs.ccEnabled = g != null
            if (g != null) g.getTrackFormat(0).language?.let { Prefs.ccLanguage = it }
        }
    }

    fun toggle() {
        val groups = textGroups()
        if (groups.isEmpty()) { Toast.makeText(a, "วิดีโอนี้ไม่มีคำบรรยาย", Toast.LENGTH_SHORT).show(); return }
        Prefs.ccEnabled = !Prefs.ccEnabled
        applyPrefs()
        val msg = if (Prefs.ccEnabled) "คำบรรยาย: ${Captions.pick(groups.map { Captions.Track(it.getTrackFormat(0).language, captionLabel(it), false) }, true, Prefs.ccLanguage)?.label ?: "เปิด"}" else "ปิดคำบรรยายแล้ว"
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()
    }

    fun captionsSheet() {
        val groups = textGroups()
        if (groups.isEmpty()) { Toast.makeText(a, "วิดีโอนี้ไม่มีคำบรรยาย", Toast.LENGTH_SHORT).show(); return }
        val sel = if (captionsOn()) groups.indexOfFirst { it.isSelected } + 1 else 0
        a.choiceSheet("คำบรรยาย", listOf("ปิด") + groups.map { captionLabel(it) }, sel) { i ->
            if (i == 0) { Prefs.ccEnabled = false; select(null) } else { Prefs.ccEnabled = true; select(groups[i - 1]) }
        }
    }

    fun captionSummary(): String {
        val groups = textGroups()
        return when {
            groups.isEmpty() -> "ไม่มี"
            captionsOn() -> groups.firstOrNull { it.isSelected }?.let { captionLabel(it) } ?: "เปิด"
            else -> "ปิด"
        }
    }

    fun showAudio(): Boolean = AudioTracks.showMenu(Playback.audioTracks)

    fun audioSummary(): String? = if (!showAudio()) null else Playback.currentAudio?.label

    fun audioSheet() {
        val tracks = Playback.audioTracks
        if (!AudioTracks.showMenu(tracks)) return
        val sel = tracks.indexOfFirst { it.id == Playback.currentAudio?.id }.coerceAtLeast(0)
        a.choiceSheet("แทร็กเสียง", tracks.map { it.label }, sel) { i -> Playback.setAudioTrack(tracks[i].id) }
    }
}
