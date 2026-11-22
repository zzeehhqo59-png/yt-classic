package com.bear.ytclassic

import android.content.Intent
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.load
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** การติดตาม tab: subscribed channels row + newest videos from all of them. */
class SubsFragment : Fragment() {
    private val channels = ChannelRowAdapter { load(force = false) }
    private val header = SubsHeader()
    private lateinit var videos: VideoAdapter
    private var loadedVersion = -1L
    private var job: Job? = null
    private var root: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_list, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        root = view
        videos = VideoAdapter(true) { requireContext().openVideo(it) }
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = ConcatAdapter(header, videos)
        val refresh = view.findViewById<SwipeRefreshLayout>(R.id.refresh)
        refresh.setColorSchemeColors(0xFFFF0000.toInt())
        refresh.setOnRefreshListener { load(force = true) }
        view.findViewById<View>(R.id.btnRetry).setOnClickListener { load(force = true) }
        load(force = false)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && loadedVersion != Subs.version) load(force = false)
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden && loadedVersion != -1L && loadedVersion != Subs.version) load(force = false)
    }

    private fun load(force: Boolean) {
        val view = root ?: return
        val refresh = view.findViewById<SwipeRefreshLayout>(R.id.refresh)
        val box = view.findViewById<View>(R.id.errorBox)
        val text = view.findViewById<TextView>(R.id.errorText)
        val retry = view.findViewById<View>(R.id.btnRetry)
        val progress = view.findViewById<View>(R.id.progress)
        job?.cancel()
        loadedVersion = Subs.version
        val subs = Subs.all()
        channels.set(subs)
        header.visible = subs.isNotEmpty()
        if (header.visible) header.notifyItemChanged(0)
        if (subs.isEmpty()) {
            videos.set(emptyList()); refresh.isRefreshing = false; progress.visibility = View.GONE
            text.text = "ยังไม่ได้ติดตามช่องใด\n\nกด \"ติดตาม\" ในหน้าวิดีโอหรือหน้าช่อง\nหรือนำเข้าการติดตามจากไฟล์ในแท็บคุณ"
            retry.visibility = View.GONE; box.visibility = View.VISIBLE
            return
        }
        box.visibility = View.GONE
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cached = Feed.cached(requireContext())
                if (cached != null) videos.set(cached.first)
                if (!force && cached != null && cached.second < Feed.MAX_AGE_MS) return@launch
                if (videos.items.isEmpty()) { progress.visibility = View.VISIBLE; text.text = "กำลังโหลดวิดีโอจาก ${subs.size} ช่อง…"; retry.visibility = View.GONE; box.visibility = View.VISIBLE }
                else if (!force) refresh.isRefreshing = true
                val items = Feed.refresh(requireContext()) { done, total ->
                    view.post { if (box.visibility == View.VISIBLE) text.text = "กำลังโหลดวิดีโอ $done/$total ช่อง…" }
                }
                videos.set(items)
                channels.set(Subs.all())
                box.visibility = View.GONE
                if (items.isEmpty()) {
                    text.text = "โหลดวิดีโอจากช่องที่ติดตามไม่ได้"; retry.visibility = View.VISIBLE; box.visibility = View.VISIBLE
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                if (videos.items.isEmpty()) { text.text = Errors.list(e); retry.visibility = View.VISIBLE; box.visibility = View.VISIBLE }
            } finally {
                progress.visibility = View.GONE; refresh.isRefreshing = false
            }
        }
    }

    private fun manageSubs() {
        val ctx = requireContext()
        val list = Subs.all()
        if (list.isEmpty()) return
        AlertDialog.Builder(ctx)
            .setTitle("ช่องที่ติดตาม")
            .setItems(list.map { it.name }.toTypedArray()) { _, w ->
                val ch = list[w]
                AlertDialog.Builder(ctx).setItems(arrayOf("เปิดช่อง", "เลิกติดตาม")) { _, a ->
                    if (a == 0) ctx.openChannel(ch.url, ch.name, ch.avatar)
                    else { Subs.remove(ch.url); load(force = false) }
                }.setNegativeButton("ยกเลิก", null).show()
            }.setNegativeButton("ปิด", null).show()
    }

    private inner class SubsHeader : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var visible = false
            set(v) { if (field != v) { field = v; if (v) notifyItemInserted(0) else notifyItemRemoved(0) } }
        override fun getItemCount() = if (visible) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_subs_header, parent, false)
            v.findViewById<RecyclerView>(R.id.channels).apply {
                layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
                adapter = channels
            }
            v.findViewById<View>(R.id.btnManage).setOnClickListener { manageSubs() }
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val n = Subs.count()
            h.itemView.findViewById<TextView>(R.id.subsTitle).text = "ช่องที่ติดตาม ($n)"
        }
    }
}

class ChannelRowAdapter(private val onChange: () -> Unit = {}) : RecyclerView.Adapter<ChannelRowAdapter.VH>() {
    private val items = ArrayList<Channel>()
    fun set(list: List<Channel>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel_chip, parent, false))
    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.avatar.loadAvatar(c.avatar)
        h.name.text = c.name
        h.itemView.setOnClickListener { it.context.openChannel(c.url, c.name, c.avatar) }
        h.itemView.setOnLongClickListener { v ->
            AlertDialog.Builder(v.context).setMessage("เลิกติดตาม ${c.name} ใช่ไหม")
                .setPositiveButton("เลิกติดตาม") { _, _ -> Subs.remove(c.url); onChange() }
                .setNegativeButton("ยกเลิก", null).show()
            true
        }
    }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.avatar)
        val name: TextView = v.findViewById(R.id.name)
    }
}

/** คุณ tab (เดิมคือคลังวิดีโอ): subscription count + import from file. */
class LibraryFragment : Fragment() {
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
            refreshCount()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_library, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        view.findViewById<View>(R.id.btnImport).setOnClickListener { picker.launch(arrayOf("*/*")) }
        refreshCount()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                Downloads.changes.collect { if (!isHidden) refreshDownloads() }
            }
        }
    }

    /** การดาวน์โหลด section: every download with its progress; tap a finished one to play it offline. */
    private fun refreshDownloads() {
        val view = view ?: return
        val ctx = requireContext()
        val list = Downloads.all()
        val box = view.findViewById<LinearLayout>(R.id.downloads)
        view.findViewById<TextView>(R.id.downloadsTitle).text = if (list.isEmpty()) "การดาวน์โหลด" else "การดาวน์โหลด (${list.size})"
        // Progress ticks arrive often: update existing rows in place so an open ⋮ menu or a tap isn't lost.
        val ids = list.map { it.id }
        val same = box.childCount == ids.size && ids.indices.all { (box.getChildAt(it).tag as? Downloads.Dl)?.id == ids[it] }
        if (!same) box.removeAllViews()
        if (list.isEmpty()) {
            val t = TextView(ctx).apply {
                text = "ยังไม่มีวิดีโอที่ดาวน์โหลด\nกดปุ่ม \"ดาวน์โหลด\" ในหน้าวิดีโอหรือในเมนู ⋮ ของวิดีโอ"
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text2)); textSize = 13f
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

    override fun onHiddenChanged(hidden: Boolean) { super.onHiddenChanged(hidden); if (!hidden) { refreshCount(); refreshDownloads() } }
    override fun onResume() { super.onResume(); refreshCount() }

    private fun row(parent: LinearLayout, icon: Int, title: String, sub: String?, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
        val v = layoutInflater.inflate(R.layout.item_library_row, parent, false)
        v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        v.findViewById<TextView>(R.id.title).text = title
        v.findViewById<TextView>(R.id.subtitle).apply { text = sub; visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE }
        v.setOnClickListener { onClick() }
        onLong?.let { l -> v.setOnLongClickListener { l(); true } }
        parent.addView(v)
    }

    private fun refreshCount() {
        val view = view ?: return
        val ctx = requireContext()
        view.findViewById<TextView>(R.id.subsCount).text = "ติดตามอยู่ ${Subs.count()} ช่อง"
        val top = view.findViewById<LinearLayout>(R.id.topRows); top.removeAllViews()
        val h = History.count()
        row(top, R.drawable.ic_history, "ประวัติการดู", if (h > 0) "$h วิดีโอ" else "ยังไม่มีวิดีโอที่ดู") { startActivity(VideoListActivity.history(ctx)) }
        val pls = Playlists.all()
        pls.firstOrNull { it.id == Playlists.WATCH_LATER }?.let { wl ->
            row(top, R.drawable.ic_watch_later, wl.name, "${wl.count} วิดีโอ") { startActivity(VideoListActivity.local(ctx, wl.id)) }
        }
        val likes = Likes.count()
        row(top, R.drawable.ic_thumb_up, "วิดีโอที่ชอบ", if (likes > 0) "$likes วิดีโอ" else "ยังไม่มีวิดีโอที่ชอบ") { startActivity(VideoListActivity.liked(ctx)) }
        val box = view.findViewById<LinearLayout>(R.id.playlists); box.removeAllViews()
        row(box, R.drawable.ic_add, "สร้างเพลย์ลิสต์ใหม่", null) {
            ctx.askName("เพลย์ลิสต์ใหม่", "") { name -> Playlists.create(name); refreshCount() }
        }
        pls.filter { it.id != Playlists.WATCH_LATER }.forEach { pl ->
            row(box, R.drawable.ic_playlist, pl.name, "${pl.count} วิดีโอ", onLong = { playlistMenu(pl) }) { startActivity(VideoListActivity.local(ctx, pl.id)) }
        }
        val bottom = view.findViewById<LinearLayout>(R.id.bottomRows); bottom.removeAllViews()
        row(bottom, R.drawable.ic_settings, "การตั้งค่า", "ธีม: ${Prefs.themeNames[Prefs.theme]}") { startActivity(Intent(ctx, SettingsActivity::class.java)) }
    }

    private fun playlistMenu(pl: Playlists.Pl) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx).setTitle(pl.name).setItems(arrayOf("เปลี่ยนชื่อ", "ลบเพลย์ลิสต์")) { _, w ->
            if (w == 0) ctx.askName("เปลี่ยนชื่อเพลย์ลิสต์", pl.name) { Playlists.rename(pl.id, it); refreshCount() }
            else AlertDialog.Builder(ctx).setMessage("ลบเพลย์ลิสต์ ${pl.name} ใช่ไหม")
                .setPositiveButton("ลบ") { _, _ -> Playlists.delete(pl.id); refreshCount() }.setNegativeButton("ยกเลิก", null).show()
        }.show()
    }
}
