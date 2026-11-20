package com.bear.ytclassic

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import coil3.load
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

private fun Context.px(dp: Float) = (dp * resources.displayMetrics.density).toInt()

/** Rounded bottom sheet with a drag handle (YouTube style); [build] adds the rows. */
fun Context.sheet(build: (LinearLayout, BottomSheetDialog) -> Unit): BottomSheetDialog {
    val dlg = BottomSheetDialog(this)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, px(8f), 0, px(16f))
    }
    box.addView(View(this).apply { setBackgroundResource(R.drawable.bg_handle) },
        LinearLayout.LayoutParams(px(36f), px(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(8f) })
    build(box, dlg)
    val scroll = NestedScrollView(this).apply { setBackgroundResource(R.drawable.bg_sheet); addView(box) }
    dlg.setContentView(scroll)
    dlg.setOnShowListener {
        dlg.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
    }
    dlg.window?.setDimAmount(0.6f)
    dlg.behavior.apply { state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true; maxWidth = px(640f) }
    dlg.show()
    return dlg
}

fun LinearLayout.sheetTitle(text: String) {
    addView(TextView(context).apply {
        this.text = text; setTextColor(ContextCompat.getColor(context, R.color.text1)); textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(context.px(20f), context.px(4f), context.px(20f), context.px(12f))
    })
}

/** One sheet row: icon, title (+ optional grey value under it), optional view at the end. */
fun LinearLayout.sheetRow(icon: Int?, title: String, sub: String? = null, end: View? = null, start: View? = null, click: (View) -> Unit): LinearLayout {
    val c = context
    val row = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = c.px(52f); setPadding(c.px(20f), c.px(6f), c.px(12f), c.px(6f))
        val tv = TypedValue(); c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); setBackgroundResource(tv.resourceId)
        setOnClickListener(click)
    }
    if (start != null) row.addView(start)
    else if (icon != null) row.addView(ImageView(c).apply {
        setImageResource(icon); setColorFilter(ContextCompat.getColor(c, R.color.text1))
    }, LinearLayout.LayoutParams(c.px(24f), c.px(24f)))
    val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.px(if (start != null) 14f else 20f), 0, c.px(8f), 0) }
    col.addView(TextView(c).apply { text = title; setTextColor(ContextCompat.getColor(c, R.color.text1)); textSize = 15f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
    if (!sub.isNullOrEmpty()) col.addView(TextView(c).apply { text = sub; setTextColor(ContextCompat.getColor(c, R.color.text2)); textSize = 12f })
    row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    if (end != null) row.addView(end)
    addView(row)
    return row
}

/** ⋯ sheet under the watch page: บันทึก / ดาวน์โหลด / แชร์. */
fun Context.videoActionSheet(v: VideoItem, download: () -> Unit) = sheet { box, dlg ->
    box.sheetRow(R.drawable.ic_bookmark_border, "บันทึก") { dlg.dismiss(); saveToPlaylist(v) }
    if (!v.live && !v.upcoming) box.sheetRow(R.drawable.ic_download, "ดาวน์โหลด") { dlg.dismiss(); download() }
    box.sheetRow(R.drawable.ic_share, "แชร์") { dlg.dismiss(); shareUrl(v.url) }
}

/** Single-choice list in a sheet (quality, speed, captions); the chosen one gets a ✓. */
fun Context.choiceSheet(title: String, options: List<String>, selected: Int, pick: (Int) -> Unit) = sheet { box, dlg ->
    box.sheetTitle(title)
    options.forEachIndexed { i, o ->
        val check = ImageView(this).apply {
            setImageResource(R.drawable.ic_check); setColorFilter(ContextCompat.getColor(this@choiceSheet, R.color.text1))
            visibility = if (i == selected) View.VISIBLE else View.INVISIBLE
        }
        box.sheetRow(null, o, start = FrameLayout(this).apply { addView(check, FrameLayout.LayoutParams(px(24f), px(24f))) }) { dlg.dismiss(); pick(i) }
    }
}

/** "บันทึกไปยัง..." sheet: ดูภายหลัง first, then the user's playlists; bookmark = in it (tap toggles). */
fun Context.saveToPlaylist(v: VideoItem) = sheet { box, dlg ->
    box.sheetTitle("บันทึกไปยัง...")
    val pls = Playlists.all().sortedBy { if (it.id == Playlists.WATCH_LATER) 0 else 1 }
    pls.forEach { pl ->
        val mark = ImageView(this).apply {
            setColorFilter(ContextCompat.getColor(this@saveToPlaylist, R.color.text1)); setPadding(px(10f), px(10f), px(10f), px(10f))
        }
        fun render() { mark.setImageResource(if (Playlists.contains(pl.id, v.url)) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border) }
        render()
        // cover: rounded thumbnail with a thinner card peeking out above it (stacked look)
        val cover = FrameLayout(this).apply {
            addView(View(context).apply { setBackgroundResource(R.drawable.bg_cover_back); alpha = 0.6f },
                FrameLayout.LayoutParams(px(56f), px(36f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(ContextCompat.getColor(context, R.color.placeholder))
                outlineProvider = RoundOutline(px(6f).toFloat()); clipToOutline = true
                if (pl.thumb != null) load(pl.thumb)
                else if (pl.id == Playlists.WATCH_LATER) { setImageResource(R.drawable.ic_watch_later); scaleType = ImageView.ScaleType.CENTER_INSIDE; setColorFilter(ContextCompat.getColor(context, R.color.text2)) }
                else { setImageResource(R.drawable.ic_playlist); scaleType = ImageView.ScaleType.CENTER_INSIDE; setColorFilter(ContextCompat.getColor(context, R.color.text2)) }
            }, FrameLayout.LayoutParams(px(64f), px(38f)).apply { topMargin = px(4f) })
        }
        val name = if (pl.id == Playlists.WATCH_LATER) "ดูภายหลัง" else pl.name
        box.sheetRow(null, name, "ส่วนตัว", end = mark, start = cover) {
            val on = !Playlists.contains(pl.id, v.url)
            if (on) Playlists.add(pl.id, v) else Playlists.remove(pl.id, v.url)
            render()
            Toast.makeText(this, if (on) "บันทึกลงใน $name แล้ว" else "นำออกจาก $name แล้ว", Toast.LENGTH_SHORT).show()
        }
    }
    box.addView(TextView(this).apply {
        text = "+  เพลย์ลิสต์ใหม่"; gravity = Gravity.CENTER; textSize = 15f
        setTextColor(ContextCompat.getColor(context, R.color.text1)); setTypeface(typeface, android.graphics.Typeface.BOLD)
        setBackgroundResource(R.drawable.bg_capsule)
        setOnClickListener {
            dlg.dismiss()
            askName("เพลย์ลิสต์ใหม่", "") { n ->
                val id = Playlists.create(n); Playlists.add(id, v)
                Toast.makeText(this@saveToPlaylist, "บันทึกลงใน $n แล้ว", Toast.LENGTH_SHORT).show()
            }
        }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44f)).apply { setMargins(px(16f), px(12f), px(16f), 0) })
}
