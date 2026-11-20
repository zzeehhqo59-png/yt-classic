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
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        setupMini()
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { show(it.itemId); true }
        nav.setOnItemReselectedListener { if (it.itemId == R.id.nav_home) (supportFragmentManager.findFragmentByTag("home") as? HomeFragment)?.scrollTop() }
        if (savedInstanceState == null) show(R.id.nav_home)
        lifecycleScope.launch {
            val n = Subs.importOldAppOnce(applicationContext)
            if (n != null && n > 0) Toast.makeText(this@MainActivity, "นำเข้าช่องที่ติดตามจากแอปเดิมแล้ว $n ช่อง", Toast.LENGTH_LONG).show()
        }
    }

    private lateinit var miniBar: DragFrame
    private lateinit var miniPlayer: StyledPlayerView
    private lateinit var miniProgress: ProgressBar
    private lateinit var miniPlay: ImageButton
    private val ticker = object : Runnable {
        override fun run() {
            val p = Playback.player
            if (p.duration > 0) miniProgress.progress = (p.currentPosition * 1000 / p.duration).toInt()
            miniProgress.postDelayed(this, 500)
        }
    }
    private val miniListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayIcon()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updatePlayIcon()
    }
    private fun updatePlayIcon() { miniPlay.setImageResource(if (Playback.player.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play) }

    private fun setupMini() {
        miniBar = findViewById(R.id.miniBar)
        miniPlayer = findViewById(R.id.miniPlayer)
        miniProgress = findViewById(R.id.miniProgress)
        miniPlay = findViewById(R.id.miniPlay)
        miniBar.setOnClickListener { Playback.state.value.item?.let { openVideo(it, expandFrom = 1f) } }
        setupMiniDrag()
        miniPlay.setOnClickListener {
            val p = Playback.player
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.playWhenReady = !p.playWhenReady
        }
        findViewById<View>(R.id.miniClose).setOnClickListener { Playback.close() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(Playback.state, Playback.pip, Playback.watchActive) { a, b, c -> Triple(a, b, c) }.collect { (st, pip, watch) ->
                    val it = st.item
                    val hidden = pip || watch
                    val vis = if (it == null || hidden) View.GONE else View.VISIBLE
                    if (hidden) miniPlayer.player = null else if (miniPlayer.player == null) miniPlayer.player = Playback.player
                    if (!hidden) { miniBar.translationY = 0f; miniBar.alpha = 1f }
                    miniBar.visibility = vis; miniProgress.visibility = vis
                    if (it != null) {
                        findViewById<TextView>(R.id.miniTitle).text = it.title
                        findViewById<TextView>(R.id.miniChannel).text = st.error?.lineSequence()?.firstOrNull() ?: it.channel
                        val thumb = findViewById<ImageView>(R.id.miniThumb)
                        if (thumb.tag != it.thumb) { thumb.tag = it.thumb; thumb.load(it.thumb) }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (miniAllowed()) miniPlayer.player = Playback.player
        Playback.player.addListener(miniListener)
        updatePlayIcon()
        miniProgress.post(ticker)
    }

    private fun miniAllowed() = !Playback.pip.value && !Playback.watchActive.value

    override fun onResume() {
        super.onResume()
        // take the video surface back from the watch page that just closed
        if (miniAllowed() && Playback.state.value.item != null) { miniPlayer.player = null; miniPlayer.player = Playback.player }
    }

    /** Drag the mini player up to open the full watch page (snaps open past ~30% or on an upward fling). */
    private fun setupMiniDrag() {
        val d = resources.displayMetrics.density
        miniBar.direction = -1
        fun range() = miniBar.top.toFloat().coerceAtLeast(200 * d)
        var anim: android.animation.ValueAnimator? = null
        miniBar.listener = object : DragFrame.Listener {
            override fun onDragStart() { anim?.cancel() }
            override fun onDrag(dy: Float) {
                val t = dy.coerceIn(-range(), 0f)
                miniBar.translationY = t
                miniBar.alpha = 1f - 0.4f * (-t / range())
            }
            override fun onDragEnd(velocityY: Float) {
                val f = -miniBar.translationY / range()
                val item = Playback.state.value.item
                if (item != null && (f > 0.3f || velocityY < -600 * d)) {
                    openVideo(item, expandFrom = (1f - f).coerceIn(0.05f, 1f))
                } else {
                    anim = android.animation.ValueAnimator.ofFloat(miniBar.translationY, 0f).apply {
                        duration = 200
                        addUpdateListener { miniBar.translationY = it.animatedValue as Float; miniBar.alpha = 1f - 0.4f * (-miniBar.translationY / range()) }
                        start()
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        miniProgress.removeCallbacks(ticker)
        Playback.player.removeListener(miniListener)
        miniPlayer.player = null
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

fun android.content.Context.openVideo(v: VideoItem, expandFrom: Float = -1f) {
    val i = Intent(this, WatchActivity::class.java).putExtra(WatchActivity.EXTRA_ITEM, v)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (expandFrom > 0f) i.putExtra(WatchActivity.EXTRA_EXPAND_FROM, expandFrom)
    startActivity(i)
    @Suppress("DEPRECATION")
    if (expandFrom > 0f) (this as? android.app.Activity)?.overridePendingTransition(0, 0)
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

    override fun onResume() { super.onResume(); if (::adapter.isInitialized) adapter.refreshProgress() }
    override fun onHiddenChanged(hidden: Boolean) { if (!hidden && ::adapter.isInitialized) adapter.refreshProgress() }

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
            catch (_: Throwable) { }
            finally { adapter.loadingMore = false }
        }
    }
}

