package com.bear.ytclassic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs

fun Context.openChannel(url: String?, name: String, avatar: String?) {
    if (url.isNullOrBlank()) return
    startActivity(Intent(this, ChannelActivity::class.java)
        .putExtra("url", url).putExtra("name", name).putExtra("avatar", avatar))
}

class ChannelActivity : YtActivity() {
    private val vm: ChannelViewModel by viewModels()
    private lateinit var pager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private var mediator: TabLayoutMediator? = null
    private var pagerAdapter: ChannelPagerAdapter? = null
    private var boundTabs: List<ChannelTabSpec> = emptyList()
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(lightBars = true)
        setContentView(R.layout.activity_channel)
        attachPlayerUi()
        findViewById<View>(R.id.root).padForSystemBars()
        val url = intent.getStringExtra("url").orEmpty()
        if (url.isBlank()) { finish(); return }
        findViewById<TextView>(R.id.barTitle).text = intent.getStringExtra("name").orEmpty()
        findViewById<View>(R.id.btnBack).setOnClickListener { if (!WatchLayer.consumeBack(this)) finish() }
        findViewById<View>(R.id.btnSearch).setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        tabLayout = findViewById(R.id.tabLayout)
        pager = findViewById(R.id.pager)
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (syncing) return
                ChannelSwipe.tabFor(vm.ui.value.tabs, position)?.let { vm.selectTab(it) }
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.ui.collect { render(it) } }
        }
        vm.load(url, intent.getStringExtra("name").orEmpty(), intent.getStringExtra("avatar"))
    }

    override fun onResume() {
        super.onResume()
        pagerAdapter?.bindVisible(vm.ui.value)
    }

    private fun render(st: ChannelUiState) {
        findViewById<View>(R.id.progress).visibility = if (st.loading && st.tabs.isEmpty()) View.VISIBLE else View.GONE
        val msg = findViewById<TextView>(R.id.message)
        val text = st.error ?: st.emptyMessage
        msg.text = text.orEmpty()
        msg.visibility = if (text != null && !st.loading) View.VISIBLE else View.GONE
        st.channel?.name?.let { findViewById<TextView>(R.id.barTitle).text = it }
        findViewById<View>(R.id.header).bindChannelHeader(st) { showAbout() }
        if (st.tabs != boundTabs) {
            boundTabs = st.tabs
            bindPager(st.tabs)
        }
        val page = ChannelSwipe.pageFor(st.tabs, st.selectedTab)
        if (page >= 0 && pager.currentItem != page) {
            syncing = true
            pager.setCurrentItem(page, false)
            syncing = false
        }
        pagerAdapter?.bindVisible(st)
    }

    private fun bindPager(tabList: List<ChannelTabSpec>) {
        mediator?.detach()
        val adapter = ChannelPagerAdapter(tabList) { list, id -> bindPage(list, id, vm.ui.value) }
        pagerAdapter = adapter
        pager.adapter = adapter
        mediator = TabLayoutMediator(tabLayout, pager) { tab, pos -> tab.text = tabList[pos].label }.also { it.attach() }
        tabLayout.visibility = if (tabList.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun bindPage(list: RecyclerView, tabId: String, st: ChannelUiState) {
        val shorts = tabId == ChannelTabs.SHORTS
        if (shorts) {
            if (list.layoutManager !is GridLayoutManager) list.layoutManager = GridLayoutManager(this, 3)
            val shortsAdapter = (list.adapter as? ShortsAdapter) ?: ShortsAdapter { openItem(it) }.also { list.adapter = it }
            shortsAdapter.loadingMore = st.selectedTab == tabId && st.loadingMore
            if (st.selectedTab == tabId) shortsAdapter.set(st.items)
        } else {
            val needSetup = list.layoutManager !is LinearLayoutManager || list.layoutManager is GridLayoutManager || list.adapter !is ConcatAdapter
            if (needSetup) {
                list.layoutManager = LinearLayoutManager(this)
                list.adapter = ConcatAdapter(FeaturedAdapter { openItem(it) }, VideoAdapter(false) { openItem(it) })
            }
            val concat = list.adapter as ConcatAdapter
            val featured = concat.adapters[0] as FeaturedAdapter
            val videos = concat.adapters[1] as VideoAdapter
            featured.item = if (tabId == ChannelPage.HOME && st.selectedTab == tabId) st.featured else null
            videos.loadingMore = st.selectedTab == tabId && st.loadingMore
            if (st.selectedTab == tabId) videos.set(st.items)
            videos.refreshProgress()
            featured.applyProgress(videos.progress)
        }
        if (list.getTag(R.id.pager) != tabId) {
            list.clearOnScrollListeners()
            list.onNearEnd { if (vm.ui.value.selectedTab == tabId) vm.loadMore() }
            list.setTag(R.id.pager, tabId)
        }
    }

    private fun showAbout() {
        val st = vm.ui.value
        val about = st.about ?: return
        channelAboutSheet(st.channel?.name.orEmpty(), about)
    }
}

class ChannelPagerAdapter(
    private val tabs: List<ChannelTabSpec>,
    private val bind: (RecyclerView, String) -> Unit,
) : RecyclerView.Adapter<ChannelPagerAdapter.Page>() {
    class Page(val list: RecyclerView) : RecyclerView.ViewHolder(list)
    private val bound = HashMap<Int, Page>()
    override fun getItemCount() = tabs.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Page {
        val list = RecyclerView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        return Page(list)
    }
    override fun onBindViewHolder(holder: Page, position: Int) {
        bound[position] = holder
        val id = tabs.getOrNull(position)?.id ?: return
        bind(holder.list, id)
    }
    override fun onViewRecycled(holder: Page) { bound.entries.removeAll { it.value === holder } }
    fun bindVisible(st: ChannelUiState) {
        bound.forEach { (pos, page) -> tabs.getOrNull(pos)?.id?.let { bind(page.list, it) } }
    }
}
