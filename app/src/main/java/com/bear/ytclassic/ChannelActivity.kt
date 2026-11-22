package com.bear.ytclassic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import kotlinx.coroutines.launch

fun Context.openChannel(url: String?, name: String, avatar: String?) {
    if (url.isNullOrBlank()) return
    startActivity(Intent(this, ChannelActivity::class.java)
        .putExtra("url", url).putExtra("name", name).putExtra("avatar", avatar))
}

class ChannelActivity : AppCompatActivity() {
    private val vm: ChannelViewModel by viewModels()
    private val header = Header()
    private val videos = VideoAdapter(false) { openVideo(it) }
    private var descExpanded = false
    private var boundVideos: List<VideoItem> = emptyList()

    override fun onResume() { super.onResume(); videos.refreshProgress() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(lightBars = true)
        setContentView(R.layout.activity_channel)
        MiniPlayerBar.attach(this)
        findViewById<View>(R.id.root).padForSystemBars()
        val url = intent.getStringExtra("url").orEmpty()
        if (url.isBlank()) { finish(); return }
        val name = intent.getStringExtra("name").orEmpty()
        val avatar = intent.getStringExtra("avatar")
        findViewById<TextView>(R.id.barTitle).text = name
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSearch).setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = ConcatAdapter(header, videos)
        list.onNearEnd { vm.loadMore() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.ui.collect { render(it) } }
        }
        vm.load(url, name, avatar)
    }

    private fun render(st: ChannelUiState) {
        findViewById<View>(R.id.progress).visibility = if (st.loading) View.VISIBLE else View.GONE
        val msg = findViewById<TextView>(R.id.message)
        val text = st.error ?: st.emptyMessage
        msg.text = text.orEmpty()
        msg.visibility = if (text != null) View.VISIBLE else View.GONE
        st.channel?.name?.let { findViewById<TextView>(R.id.barTitle).text = it }
        videos.loadingMore = st.loadingMore
        if (boundVideos != st.videos) { boundVideos = st.videos; videos.set(st.videos) }
        header.notifyItemChanged(0)
    }

    private inner class Header : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_channel_header, parent, false)) {}
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val v = h.itemView
            val st = vm.ui.value
            val c = st.channel
            v.findViewById<ImageView>(R.id.banner).apply {
                if (st.banner != null) { visibility = View.VISIBLE; load(st.banner) } else visibility = View.GONE
            }
            v.findViewById<ImageView>(R.id.avatar).loadAvatar(c?.avatar)
            v.findViewById<TextView>(R.id.name).text = c?.name
            val handle = Fmt.channelLabel(c?.name.orEmpty(), c?.url).takeIf { it.startsWith("@") }
            v.findViewById<TextView>(R.id.handle).apply {
                text = handle.orEmpty(); visibility = if (handle == null) View.GONE else View.VISIBLE
            }
            v.findViewById<TextView>(R.id.subs).text = Fmt.subscribers(c?.subscribers ?: -1)
            v.findViewById<TextView>(R.id.btnSubscribe).bindSubscribe({ c }, filled = true)
            val desc = v.findViewById<TextView>(R.id.description)
            if (st.description.isBlank()) desc.visibility = View.GONE
            else {
                desc.visibility = View.VISIBLE
                desc.maxLines = if (descExpanded) Integer.MAX_VALUE else 2
                desc.ellipsize = if (descExpanded) null else android.text.TextUtils.TruncateAt.END
                desc.text = st.description
                desc.setOnClickListener { descExpanded = !descExpanded; notifyItemChanged(0) }
            }
        }
    }
}
