package com.bear.ytclassic

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.load
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : YtActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(lightBars = true)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).padForSystemBars()
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        attachPlayerUi()
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { show(it.itemId); true }
        nav.setOnItemReselectedListener { if (it.itemId == R.id.nav_home) (supportFragmentManager.findFragmentByTag("home") as? HomeFragment)?.scrollTop() }
        if (savedInstanceState == null) show(R.id.nav_home)
        handleTab(intent)
        WatchNav.consumeIntent(this, intent)
        lifecycleScope.launch {
            val n = Subs.importOldAppOnce(applicationContext)
            if (n != null && n > 0) Toast.makeText(this@MainActivity, "นำเข้าช่องที่ติดตามจากแอปเดิมแล้ว $n ช่อง", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleTab(intent); WatchNav.consumeIntent(this, intent) }

    /** Download notifications open คลังวิดีโอ. */
    private fun handleTab(i: Intent?) {
        if (i?.getStringExtra("tab") == "library") findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_library
    }

    private fun show(id: Int) {
        val tag = when (id) { R.id.nav_subs -> "subs"; R.id.nav_library -> "library"; else -> "home" }
        val fm = supportFragmentManager
        fm.executePendingTransactions()   // a just-added tab (e.g. home on a cold start from a notification) must be hidden too
        val tx = fm.beginTransaction()
        fm.fragments.forEach { tx.hide(it) }
        val existing = fm.findFragmentByTag(tag)
        if (existing != null) tx.show(existing) else tx.add(R.id.container, when (tag) {
            "subs" -> SubsFragment()
            "library" -> LibraryFragment()
            else -> HomeFragment()
        }, tag)
        tx.commit()
    }
}

/** Play a finished download offline in the app player. */
fun android.content.Context.openDownloaded(d: Downloads.Dl) {
    if (!DeviceSave.exists(this, d.file)) { Toast.makeText(this, "ไม่พบไฟล์ที่ดาวน์โหลดไว้ (อาจถูกลบไปแล้ว)", Toast.LENGTH_SHORT).show(); return }
    val uri = DeviceSave.playUri(d.file) ?: return
    WatchNav.open(this, d.video(), uri = uri)
}

fun android.content.Context.openVideo(v: VideoItem, expandFrom: Float = -1f) {
    WatchNav.open(this, v, expandFrom = expandFrom)
}

class HomeFragment : Fragment() {
    private lateinit var adapter: VideoAdapter
    private lateinit var list: RecyclerView
    private var pager: Pager? = null
    private var job: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_list, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        list = view.findViewById(R.id.list)
        val hide = HomeHide(this) { adapter }
        adapter = VideoAdapter(true, onMore = { anchor, v -> hide.menu(anchor, v) }) { requireContext().openVideo(it) }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.onNearEnd { loadMore() }
        val refresh = view.findViewById<SwipeRefreshLayout>(R.id.refresh)
        refresh.setColorSchemeColors(0xFFFF0000.toInt())
        refresh.setOnRefreshListener { reload(view, fromSwipe = true) }
        view.findViewById<View>(R.id.btnRetry).setOnClickListener { reload(view) }
        view.findViewById<View>(R.id.btnEmptySearch).setOnClickListener { startActivity(Intent(requireContext(), SearchActivity::class.java)) }
        reload(view)
    }

    override fun onResume() { super.onResume(); if (::adapter.isInitialized) adapter.refreshProgress(); recheckEmpty() }
    override fun onHiddenChanged(hidden: Boolean) { if (!hidden && ::adapter.isInitialized) { adapter.refreshProgress(); recheckEmpty() } }

    /** New user: no history and no subscriptions yet. */
    private fun isNewUser() = History.count() == 0 && Subs.count() == 0
    private var emptyShown = false
    /** Leave the "ลองค้นหา" card as soon as there is something to recommend from. */
    private fun recheckEmpty() { val v = view ?: return; if (emptyShown && !isNewUser()) reload(v) }

    fun scrollTop() { if (::list.isInitialized) list.smoothScrollToPosition(0) }

    private fun reload(view: View, fromSwipe: Boolean = false) {
        job?.cancel()
        val progress = view.findViewById<View>(R.id.progress)
        val errorBox = view.findViewById<View>(R.id.errorBox)
        val refresh = view.findViewById<SwipeRefreshLayout>(R.id.refresh)
        errorBox.visibility = View.GONE
        emptyShown = isNewUser()
        view.findViewById<View>(R.id.emptyCard).visibility = if (emptyShown) View.VISIBLE else View.GONE
        if (emptyShown) { adapter.set(emptyList()); refresh.isRefreshing = false; progress.visibility = View.GONE; return }
        if (!fromSwipe) progress.visibility = View.VISIBLE
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val p = Recommender.homePager(requireContext().applicationContext, force = fromSwipe)
                pager = p
                val items = p.next()
                adapter.set(items)
                if (items.isEmpty()) throw IllegalStateException("empty")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) {
                if (adapter.items.isEmpty()) {
                    view.findViewById<TextView>(R.id.errorText).text = Errors.list(e)
                    errorBox.visibility = View.VISIBLE
                }
            } finally {
                progress.visibility = View.GONE
                refresh.isRefreshing = false
            }
        }
    }

    private fun loadMore() {
        val p = pager ?: return
        if (job?.isActive == true || !p.hasMore) return
        adapter.loadingMore = true
        job = viewLifecycleOwner.lifecycleScope.launch {
            try { val more = p.next(); adapter.loadingMore = false; adapter.add(more) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { android.util.Log.w("HomeFragment", "next page failed", e) }
            finally { adapter.loadingMore = false }
        }
    }
}

