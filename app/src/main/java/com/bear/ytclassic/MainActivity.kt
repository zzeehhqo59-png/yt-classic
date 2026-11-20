package com.bear.ytclassic

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(lightBars = true)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).padForSystemBars()
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnAccount).setOnClickListener {
            Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show()
        }
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { show(it.itemId); true }
        nav.setOnItemReselectedListener { if (it.itemId == R.id.nav_home) (supportFragmentManager.findFragmentByTag("home") as? HomeFragment)?.scrollTop() }
        if (savedInstanceState == null) show(R.id.nav_home)
        lifecycleScope.launch {
            val n = Subs.importOldAppOnce(applicationContext)
            if (n != null && n > 0) Toast.makeText(this@MainActivity, "นำเข้าช่องที่ติดตามจากแอปเดิมแล้ว $n ช่อง", Toast.LENGTH_LONG).show()
        }
    }

    private fun show(id: Int) {
        val tag = when (id) { R.id.nav_subs -> "subs"; R.id.nav_library -> "library"; else -> "home" }
        val fm = supportFragmentManager
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

fun android.content.Context.openVideo(v: VideoItem) {
    startActivity(Intent(this, WatchActivity::class.java).putExtra(WatchActivity.EXTRA_ITEM, v))
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
        adapter = VideoAdapter(true) { requireContext().openVideo(it) }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.onNearEnd { loadMore() }
        val refresh = view.findViewById<SwipeRefreshLayout>(R.id.refresh)
        refresh.setColorSchemeColors(0xFFFF0000.toInt())
        refresh.setOnRefreshListener { reload(view, fromSwipe = true) }
        view.findViewById<View>(R.id.btnRetry).setOnClickListener { reload(view) }
        reload(view)
    }

    fun scrollTop() { if (::list.isInitialized) list.smoothScrollToPosition(0) }

    private fun reload(view: View, fromSwipe: Boolean = false) {
        job?.cancel()
        val progress = view.findViewById<View>(R.id.progress)
        val errorBox = view.findViewById<View>(R.id.errorBox)
        val refresh = view.findViewById<SwipeRefreshLayout>(R.id.refresh)
        errorBox.visibility = View.GONE
        if (!fromSwipe) progress.visibility = View.VISIBLE
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val p = Repo.homePager()
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
            catch (_: Throwable) { }
            finally { adapter.loadingMore = false }
        }
    }
}

class PlaceholderFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_placeholder, container, false)
        v.findViewById<ImageView>(R.id.phIcon).setImageResource(requireArguments().getInt("icon"))
        v.findViewById<TextView>(R.id.phDesc).setText(requireArguments().getInt("desc"))
        return v
    }
    companion object {
        fun new(icon: Int, desc: Int) = PlaceholderFragment().apply {
            arguments = Bundle().apply { putInt("icon", icon); putInt("desc", desc) }
        }
    }
}
