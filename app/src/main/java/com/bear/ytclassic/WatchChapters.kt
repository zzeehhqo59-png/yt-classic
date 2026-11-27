package com.bear.ytclassic

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil3.load
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Chapters of the playing video: the seek bar gaps, the landscape chapter pill and the "current chapter"
 * row under the title. Everything stays hidden when the video has no chapters.
 */
class WatchChapters(private val c: Context, private val seek: YtSeekBar, private val pill: TextView, private val onSeek: (Long) -> Unit) {
    var chapters: List<Chapters.Chapter> = emptyList()
        private set
    var current = -1
        private set
    /** The current chapter changed (or chapters appeared / went away): the header row follows it. */
    var onChange: (() -> Unit)? = null
    private var land = false

    init { pill.setOnClickListener { showSheet() } }

    fun bind(info: StreamInfo?) {
        chapters = Chapters.from(info?.streamSegments)
        seek.chapterStarts = Chapters.gapStarts(chapters, (info?.duration ?: 0L) * 1000L)
        current = -1
        tick(Playback.player.currentPosition)
        updatePill()
        onChange?.invoke()
    }

    fun tick(posMs: Long) {
        val i = if (chapters.isEmpty()) -1 else Chapters.indexAt(chapters, posMs)
        if (i == current) return
        current = i
        updatePill()
        onChange?.invoke()
    }

    fun currentTitle(): String? = chapters.getOrNull(current)?.let { it.title.ifBlank { "บทที่ ${current + 1}" } }

    fun layout(land: Boolean) { this.land = land; updatePill() }

    private fun updatePill() {
        val t = currentTitle()
        pill.visibility = if (land && t != null) View.VISIBLE else View.GONE
        pill.text = t?.let { "•  $it  ›" }
    }

    fun showSheet() {
        if (chapters.isNotEmpty()) c.chapterSheet(chapters, current) { onSeek(it) }
    }
}

fun fmtChapterTime(ms: Long): String {
    val sec = ms / 1000; val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.US, "%d:%02d", m, s)
}

/** บท: thumbnail (when the extractor has one), title and start time; the current one is highlighted. */
fun Context.chapterSheet(chapters: List<Chapters.Chapter>, current: Int, onSeek: (Long) -> Unit) = sheet { box, dlg ->
    box.sheetTitle("บท")
    val d = resources.displayMetrics.density
    chapters.forEachIndexed { i, ch ->
        val thumb = ch.thumb?.let { u ->
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((96 * d).toInt(), (54 * d).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundResource(R.drawable.bg_card_round); clipToOutline = true
                load(u)
            }
        }
        val title = ch.title.ifBlank { "บทที่ ${i + 1}" }
        val row = box.sheetRow(if (thumb == null) R.drawable.ic_playlist else null, title, fmtChapterTime(ch.startMs), start = thumb) {
            dlg.dismiss(); onSeek(ch.startMs)
        }
        if (i == current) {
            row.setBackgroundColor(ContextCompat.getColor(this, R.color.chip_bg))
            (row.getChildAt(1) as? LinearLayout)?.let { col ->
                (col.getChildAt(0) as? TextView)?.setTypeface(null, android.graphics.Typeface.BOLD)
            }
        }
    }
}
