package com.bear.ytclassic

import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Opens a list item: video -> watch page, channel -> channel page, playlist -> playlist page. */
fun Context.openItem(v: VideoItem) = when (v.kind) {
    1 -> openChannel(v.url, v.title, v.avatar)
    2 -> startActivity(VideoListActivity.remote(this, v.url, v.title))
    else -> openVideo(v)
}

/** ⋮ menu of a video row. [extra] adds screen-specific actions (e.g. remove from history). */
fun View.showVideoMenu(v: VideoItem, extra: List<Pair<String, () -> Unit>> = emptyList()) {
    val c = context
    val m = PopupMenu(c, this)
    val actions = ArrayList<() -> Unit>()
    fun add(title: String, a: () -> Unit) { m.menu.add(0, actions.size, actions.size, title); actions.add(a) }
    if (v.kind == 0) {
        val inWl = Playlists.contains(Playlists.WATCH_LATER, v.url)
        if (!inWl) add("บันทึกลงในดูภายหลัง") {
            Playlists.add(Playlists.WATCH_LATER, v); Toast.makeText(c, "บันทึกลงในดูภายหลังแล้ว", Toast.LENGTH_SHORT).show()
        }
        add("บันทึกลงในเพลย์ลิสต์…") { c.saveToPlaylist(v) }
    }
    extra.forEach { (t, a) -> add(t, a) }
    add("แชร์") { c.shareUrl(v.url) }
    m.setOnMenuItemClickListener { actions[it.itemId](); true }
    m.show()
}

fun Context.shareUrl(url: String) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "แชร์"))
}

/** Checkbox dialog: tick = in that playlist. Includes "สร้างเพลย์ลิสต์ใหม่". */
fun Context.saveToPlaylist(v: VideoItem) {
    val pls = Playlists.all()
    val names = pls.map { it.name }.toTypedArray()
    val checked = pls.map { Playlists.contains(it.id, v.url) }.toBooleanArray()
    AlertDialog.Builder(this)
        .setTitle("บันทึกลงใน…")
        .setMultiChoiceItems(names, checked) { _, i, on ->
            if (on) Playlists.add(pls[i].id, v) else Playlists.remove(pls[i].id, v.url)
            Toast.makeText(this, if (on) "บันทึกลงใน ${pls[i].name} แล้ว" else "นำออกจาก ${pls[i].name} แล้ว", Toast.LENGTH_SHORT).show()
        }
        .setNeutralButton("สร้างเพลย์ลิสต์ใหม่") { _, _ ->
            askName("เพลย์ลิสต์ใหม่", "") { name ->
                val id = Playlists.create(name); Playlists.add(id, v)
                Toast.makeText(this, "บันทึกลงใน $name แล้ว", Toast.LENGTH_SHORT).show()
            }
        }
        .setPositiveButton("เสร็จสิ้น", null)
        .show()
}

fun Context.askName(title: String, initial: String, done: (String) -> Unit) {
    val edit = EditText(this).apply {
        setText(initial); setSelection(initial.length); hint = "ชื่อเพลย์ลิสต์"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isSingleLine = true
    }
    val pad = (20 * resources.displayMetrics.density).toInt()
    val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(edit) }
    AlertDialog.Builder(this).setTitle(title).setView(box)
        .setPositiveButton("ตกลง") { _, _ -> edit.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }
        .setNegativeButton("ยกเลิก", null).show()
}
