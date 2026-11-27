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
        if (::videos.isInitialized) videos.refreshProgress()
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
            text.text = "ยังไม่ได้ติดตามช่องใด\n\nกด \"ติดตาม\" ในหน้าวิดีโอหรือหน้าช่อง\nหรือนำเข้าการติดตามจากไฟล์ในการตั้งค่า (ไอคอนเฟืองมุมขวาบน)"
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
