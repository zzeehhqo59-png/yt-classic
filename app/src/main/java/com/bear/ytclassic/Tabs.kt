package com.bear.ytclassic

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
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** การติดตาม tab: subscribed channels row + newest videos from all of them. */
class SubsFragment : Fragment() {
    private val channels = ChannelRowAdapter()
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
        if (subs.isEmpty()) {
            videos.set(emptyList()); refresh.isRefreshing = false; progress.visibility = View.GONE
            text.text = "ยังไม่ได้ติดตามช่องใด\n\nกด \"ติดตาม\" ในหน้าวิดีโอหรือหน้าช่อง\nหรือนำเข้าการติดตามจากไฟล์ในแท็บคลังวิดีโอ"
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
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {}
    }
}

class ChannelRowAdapter : RecyclerView.Adapter<ChannelRowAdapter.VH>() {
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
    }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.avatar)
        val name: TextView = v.findViewById(R.id.name)
    }
}

/** คลังวิดีโอ tab: subscription count + import from file. */
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
    }

    override fun onHiddenChanged(hidden: Boolean) { super.onHiddenChanged(hidden); if (!hidden) refreshCount() }

    private fun refreshCount() {
        view?.findViewById<TextView>(R.id.subsCount)?.text = "ติดตามอยู่ ${Subs.count()} ช่อง"
    }
}
