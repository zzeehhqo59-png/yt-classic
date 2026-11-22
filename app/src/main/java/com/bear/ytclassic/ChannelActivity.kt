package com.bear.ytclassic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs

fun Context.openChannel(url: String?, name: String, avatar: String?) {
    if (url.isNullOrBlank()) return
    startActivity(Intent(this, ChannelActivity::class.java)
        .putExtra("url", url).putExtra("name", name).putExtra("avatar", avatar))
}

class ChannelActivity : AppCompatActivity() {
    private val vm: ChannelViewModel by viewModels()
    private val header = ChannelHeaderAdapter({ vm.ui.value }, onAbout = { showAbout() })
    private val tabs = ChannelTabsAdapter({ vm.ui.value }, onTab = { vm.selectTab(it) })
    private val featured = FeaturedAdapter { openItem(it) }
    private val videos = VideoAdapter(false) { openItem(it) }
    private val shorts = ShortsAdapter { openItem(it) }
    private var boundItems: List<VideoItem> = emptyList()
    private var grid = false

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
        applyLayout(list, shortsTab = false)
        list.onNearEnd { vm.loadMore() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.ui.collect { render(it) } }
        }
        vm.load(url, name, avatar)
    }

    private fun applyLayout(list: RecyclerView, shortsTab: Boolean) {
        grid = shortsTab
        if (shortsTab) {
            val glm = GridLayoutManager(this, 3)
            glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val top = header.itemCount + tabs.itemCount
                    return if (position < top) 3 else 1
                }
            }
            list.layoutManager = glm
            list.adapter = ConcatAdapter(header, tabs, shorts)
        } else {
            list.layoutManager = LinearLayoutManager(this)
            list.adapter = ConcatAdapter(header, tabs, featured, videos)
        }
    }

    private fun render(st: ChannelUiState) {
        findViewById<View>(R.id.progress).visibility = if (st.loading) View.VISIBLE else View.GONE
        val msg = findViewById<TextView>(R.id.message)
        val text = st.error ?: st.emptyMessage
        msg.text = text.orEmpty()
        msg.visibility = if (text != null && !st.loading) View.VISIBLE else View.GONE
        st.channel?.name?.let { findViewById<TextView>(R.id.barTitle).text = it }
        val list = findViewById<RecyclerView>(R.id.list)
        val wantGrid = st.selectedTab == ChannelTabs.SHORTS
        val layoutChanged = wantGrid != grid
        if (layoutChanged) applyLayout(list, wantGrid)
        videos.loadingMore = !wantGrid && st.loadingMore
        shorts.loadingMore = wantGrid && st.loadingMore
        featured.item = if (st.selectedTab == ChannelPage.HOME) st.featured else null
        if (layoutChanged || boundItems != st.items) {
            boundItems = st.items
            if (wantGrid) shorts.set(st.items) else videos.set(st.items)
        }
        header.notifyItemChanged(0)
        tabs.notifyDataSetChanged()
    }

    private fun showAbout() {
        val st = vm.ui.value
        val about = st.about ?: return
        channelAboutSheet(st.channel?.name.orEmpty(), about)
    }
}
