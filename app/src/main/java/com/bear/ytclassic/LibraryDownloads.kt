package com.bear.ytclassic

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import coil3.load

/** Download rows on the คุณ page. No ⋮ button: tap plays/resumes, long-press opens the menu. */
class LibraryDownloads(private val ctx: Context, private val box: LinearLayout) {
    fun render(list: List<Downloads.Dl>) {
        val ids = list.map { it.id }
        val same = box.childCount == ids.size && ids.indices.all { (box.getChildAt(it).tag as? Downloads.Dl)?.id == ids[it] }
        if (!same) box.removeAllViews()
        list.forEachIndexed { i, d ->
            val v = if (same) box.getChildAt(i) else inflate(d)
            v.tag = d
            bind(v, d)
        }
    }

    private fun inflate(d: Downloads.Dl): View {
        val nv = LayoutInflater.from(ctx).inflate(R.layout.item_download, box, false)
        nv.findViewById<ImageView>(R.id.thumb).load(d.thumb)
        nv.setOnClickListener {
            val cur = nv.tag as? Downloads.Dl ?: return@setOnClickListener
            when (cur.status) {
                Downloads.DONE -> ctx.openDownloaded(cur)
                Downloads.PAUSED, Downloads.FAILED -> DownloadService.send(ctx, DownloadService.ACTION_START, cur.id)
                else -> menu(nv, cur)
            }
        }
        nv.setOnLongClickListener { (nv.tag as? Downloads.Dl)?.let { menu(nv, it) }; true }
        box.addView(nv)
        return nv
    }

    private fun bind(v: View, d: Downloads.Dl) {
        v.findViewById<TextView>(R.id.title).text = d.title
        v.findViewById<TextView>(R.id.badge).text = if (d.audioOnly) "เสียง" else "${d.height}p"
        val bar = v.findViewById<ProgressBar>(R.id.progress)
        bar.isIndeterminate = d.status == Downloads.MERGING
        bar.progress = d.percent
        bar.visibility = if (d.status == Downloads.DONE) View.GONE else View.VISIBLE
        v.findViewById<TextView>(R.id.status).text = when (d.status) {
            Downloads.QUEUED -> "รอคิวดาวน์โหลด"
            Downloads.RUNNING -> "กำลังดาวน์โหลด ${d.percent}% · ${Downloads.mb(d.done)} / ${Downloads.mb(d.total)}"
            Downloads.MERGING -> "กำลังรวมวิดีโอและเสียง…"
            Downloads.PAUSED -> "หยุดชั่วคราว ${d.percent}% · แตะเพื่อดาวน์โหลดต่อ"
            Downloads.FAILED -> "ดาวน์โหลดไม่สำเร็จ: ${d.error ?: ""} · แตะเพื่อลองใหม่"
            else -> "ดาวน์โหลดแล้ว · ${d.quality} · ${Downloads.mb(d.total)} · ดูได้แบบออฟไลน์"
        }
    }

    private fun menu(anchor: View, d: Downloads.Dl) {
        val m = PopupMenu(ctx, anchor)
        val acts = ArrayList<() -> Unit>()
        fun add(t: String, a: () -> Unit) { m.menu.add(0, acts.size, acts.size, t); acts.add(a) }
        when (d.status) {
            Downloads.DONE -> add("เล่น") { ctx.openDownloaded(d) }
            Downloads.RUNNING, Downloads.QUEUED -> add("หยุดชั่วคราว") { DownloadService.send(ctx, DownloadService.ACTION_PAUSE, d.id) }
            Downloads.PAUSED -> add("ดาวน์โหลดต่อ") { DownloadService.send(ctx, DownloadService.ACTION_START, d.id) }
            Downloads.FAILED -> add("ลองอีกครั้ง") { DownloadService.send(ctx, DownloadService.ACTION_START, d.id) }
        }
        if (d.status == Downloads.DONE) add("ลบไฟล์ที่ดาวน์โหลด") {
            AlertDialog.Builder(ctx).setMessage("ลบ \"${d.title}\" ออกจากเครื่องใช่ไหม")
                .setPositiveButton("ลบ") { _, _ -> Downloads.delete(ctx, d.id) }.setNegativeButton("ยกเลิก", null).show()
        } else if (d.status == Downloads.FAILED) add("ลบรายการนี้") {
            Downloads.delete(ctx, d.id)
        } else if (d.status != Downloads.MERGING) add("ยกเลิกการดาวน์โหลด") {
            DownloadService.send(ctx, DownloadService.ACTION_CANCEL, d.id)
        }
        add("เปิดหน้าวิดีโอออนไลน์") { ctx.openVideo(d.video()) }
        m.setOnMenuItemClickListener { acts[it.itemId](); true }
        m.show()
    }
}
