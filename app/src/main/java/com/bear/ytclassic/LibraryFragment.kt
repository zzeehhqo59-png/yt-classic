package com.bear.ytclassic

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** คุณ tab: history row, playlists row, then only features this app actually has. */
class LibraryFragment : Fragment() {
    private val history = YouHistoryAdapter { requireContext().openVideo(it) }
    private val playlists = YouPlaylistAdapter(onClick = { openCard(it) }, onLong = { playlistMenu(it) })
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val msg = try {
                val r = Subs.importFile(ctx, uri)
                "นำเข้าแล้ว ${r.added} ช่อง" + if (r.existing > 0) " (ติดตามอยู่แล้ว ${r.existing} ช่อง)" else ""
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                "นำเข้าไม่ได้: ไฟล์นี้ไม่ใช่ไฟล์การติดตามของ NewPipe (.json) หรือ subscriptions.csv จาก Takeout"
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_library, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        view.findViewById<RecyclerView>(R.id.historyRow).apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = history
        }
        view.findViewById<RecyclerView>(R.id.playlistRow).apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = playlists
        }
        view.findViewById<View>(R.id.historyAll).setOnClickListener {
            startActivity(VideoListActivity.history(requireContext()))
        }
        refresh()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                Downloads.changes.collect { if (!isHidden) refreshDownloads() }
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) { super.onHiddenChanged(hidden); if (!hidden) { refresh(); refreshDownloads() } }
    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val view = view ?: return
        val ctx = requireContext()
        val recent = History.recent(20).map { it.video }
        history.set(recent)
        view.findViewById<RecyclerView>(R.id.historyRow).visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.historyEmpty).visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE

        val cards = ArrayList<YouPlaylistCard>()
        val wl = Playlists.all().firstOrNull { it.id == Playlists.WATCH_LATER }
        cards += YouPlaylistCard(Playlists.WATCH_LATER, "ดูภายหลัง", if (wl != null && wl.count > 0) "${wl.count} วิดีโอ" else "ยังว่าง", wl?.thumb, R.drawable.ic_watch_later)
        val likes = Likes.count()
        val likeThumb = Likes.liked().firstOrNull()?.thumb
        cards += YouPlaylistCard(null, "วิดีโอที่ชอบ", if (likes > 0) "$likes วิดีโอ" else "ยังว่าง", likeThumb, R.drawable.ic_thumb_up)
        Playlists.all().filter { it.id != Playlists.WATCH_LATER }.forEach { pl ->
            cards += YouPlaylistCard(pl.id, pl.name, "${pl.count} วิดีโอ", pl.thumb, R.drawable.ic_playlist)
        }
        cards += YouPlaylistCard(null, "สร้างเพลย์ลิสต์ใหม่", "ในเครื่องนี้", null, R.drawable.ic_add, create = true)
        playlists.set(cards)

        val menu = view.findViewById<LinearLayout>(R.id.menuRows)
        menu.removeAllViews()
        row(menu, R.drawable.ic_settings, "การตั้งค่า", "ธีม: ${Prefs.themeNames[Prefs.theme]}") {
            startActivity(Intent(ctx, SettingsActivity::class.java))
        }
        row(menu, R.drawable.ic_subscriptions, "นำเข้าการติดตามจากไฟล์", "NewPipe (.json) หรือ subscriptions.csv จาก Google Takeout") {
            picker.launch(arrayOf("*/*"))
        }
    }

    private fun openCard(c: YouPlaylistCard) {
        val ctx = requireContext()
        when {
            c.create -> ctx.askName("เพลย์ลิสต์ใหม่", "") { name -> Playlists.create(name); refresh() }
            c.title == "วิดีโอที่ชอบ" -> startActivity(VideoListActivity.liked(ctx))
            c.id != null -> startActivity(VideoListActivity.local(ctx, c.id))
        }
    }

    private fun playlistMenu(c: YouPlaylistCard) {
        val id = c.id ?: return
        if (id == Playlists.WATCH_LATER) return
        val ctx = requireContext()
        AlertDialog.Builder(ctx).setTitle(c.title).setItems(arrayOf("เปลี่ยนชื่อ", "ลบเพลย์ลิสต์")) { _, w ->
            if (w == 0) ctx.askName("เปลี่ยนชื่อเพลย์ลิสต์", c.title) { Playlists.rename(id, it); refresh() }
            else AlertDialog.Builder(ctx).setMessage("ลบเพลย์ลิสต์ ${c.title} ใช่ไหม")
                .setPositiveButton("ลบ") { _, _ -> Playlists.delete(id); refresh() }.setNegativeButton("ยกเลิก", null).show()
        }.show()
    }

    private fun row(parent: LinearLayout, icon: Int, title: String, sub: String?, onClick: () -> Unit) {
        val v = layoutInflater.inflate(R.layout.item_library_row, parent, false)
        v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        v.findViewById<TextView>(R.id.title).text = title
        v.findViewById<TextView>(R.id.subtitle).apply { text = sub; visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE }
        v.setOnClickListener { onClick() }
        parent.addView(v)
    }

    private fun refreshDownloads() {
        val view = view ?: return
        val ctx = requireContext()
        val list = Downloads.all()
        val box = view.findViewById<LinearLayout>(R.id.downloads)
        view.findViewById<TextView>(R.id.downloadsTitle).text = if (list.isEmpty()) "การดาวน์โหลด" else "การดาวน์โหลด (${list.size})"
        val ids = list.map { it.id }
        val same = box.childCount == ids.size && ids.indices.all { (box.getChildAt(it).tag as? Downloads.Dl)?.id == ids[it] }
        if (!same) box.removeAllViews()
        if (list.isEmpty()) {
            val t = TextView(ctx).apply {
                text = "ยังไม่มีวิดีโอที่ดาวน์โหลด\nกดปุ่ม \"ดาวน์โหลด\" ในหน้าวิดีโอหรือในเมนู ⋮ ของวิดีโอ"
                setTextColor(ContextCompat.getColor(ctx, R.color.text2)); textSize = 13f
                val p = (16 * resources.displayMetrics.density).toInt(); setPadding(p, 0, p, p / 2)
            }
            box.addView(t); return
        }
        list.forEachIndexed { i, d ->
            val v = if (same) box.getChildAt(i) else layoutInflater.inflate(R.layout.item_download, box, false).also { nv ->
                nv.findViewById<ImageView>(R.id.thumb).load(d.thumb)
                nv.setOnClickListener {
                    val cur = nv.tag as Downloads.Dl
                    when (cur.status) {
                        Downloads.DONE -> ctx.openDownloaded(cur)
                        Downloads.PAUSED, Downloads.FAILED -> DownloadService.send(ctx, DownloadService.ACTION_START, cur.id)
                        else -> downloadMenu(it, cur)
                    }
                }
                nv.findViewById<View>(R.id.more).setOnClickListener { downloadMenu(it, nv.tag as Downloads.Dl) }
                box.addView(nv)
            }
            v.tag = d
            v.findViewById<TextView>(R.id.title).text = d.title
            v.findViewById<TextView>(R.id.badge).apply { text = if (d.audioOnly) "เสียง" else "${d.height}p" }
            val bar = v.findViewById<android.widget.ProgressBar>(R.id.progress)
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
    }

    private fun downloadMenu(anchor: View, d: Downloads.Dl) {
        val ctx = requireContext()
        val m = android.widget.PopupMenu(ctx, anchor)
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
