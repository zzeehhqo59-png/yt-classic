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
        handleTab(intent)
        lifecycleScope.launch {
            val n = Subs.importOldAppOnce(applicationContext)
            if (n != null && n > 0) Toast.makeText(this@MainActivity, "นำเข้าช่องที่ติดตามจากแอปเดิมแล้ว $n ช่อง", Toast.LENGTH_LONG).show()
        }
    }

    private lateinit var miniBar: DragFrame
    private lateinit var miniPlayer: StyledPlayerView
    private lateinit var miniProgress: ProgressBar
    private lateinit var miniPlay: ImageButton
    private lateinit var miniClose: View
    private lateinit var miniOutline: RoundOutline
    private var miniAnim: android.animation.ValueAnimator? = null
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
        miniClose = findViewById(R.id.miniClose)
        val w = resources.displayMetrics.widthPixels.coerceAtMost(resources.displayMetrics.heightPixels)
        (miniBar.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            width = MiniGeom.width(this@MainActivity, w); height = MiniGeom.height(this@MainActivity, w)
            marginEnd = MiniGeom.marginEnd(this@MainActivity); bottomMargin = MiniGeom.marginBottom(this@MainActivity)
        }
        miniOutline = RoundOutline(MiniGeom.radius(this))
        miniBar.outlineProvider = miniOutline
        miniBar.clipToOutline = true
        miniBar.setOnClickListener { Playback.state.value.item?.let { openVideo(it, expandFrom = 1f) } }
        setupMiniDrag()
        miniPlay.setOnClickListener {
            val p = Playback.player
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.playWhenReady = !p.playWhenReady
        }
        miniClose.setOnClickListener { Playback.close() }
        var lastHidden = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(Playback.state, Playback.pip, Playback.watchActive) { a, b, c -> Triple(a, b, c) }.collect { (st, pip, watch) ->
                    val it = st.item
                    val hidden = pip || watch || it == null
                    if (hidden) {
                        // the watch page is expanding over us: keep the card a moment so there is no gap, then let go of the video surface
                        if (!lastHidden && watch && it != null) miniBar.postDelayed({ if (!miniAllowed()) { miniBar.visibility = View.GONE; miniPlayer.player = null } }, 180)
                        else { miniBar.visibility = View.GONE; miniPlayer.player = null }
                    } else {
                        miniAnim?.cancel(); resetCard()
                        if (miniPlayer.player == null) miniPlayer.player = Playback.player
                        miniBar.visibility = View.VISIBLE
                    }
                    lastHidden = hidden
                    if (it != null) {
                        miniBar.contentDescription = it.title
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

    private fun miniAllowed() = !Playback.pip.value && !Playback.watchActive.value && Playback.state.value.item != null

    override fun onResume() {
        super.onResume()
        // take the video surface back from the watch page that just closed
        if (miniAllowed()) { miniPlayer.player = null; miniPlayer.player = Playback.player }
    }

    private val root by lazy { findViewById<View>(R.id.root) }

    private fun resetCard() {
        miniBar.scaleX = 1f; miniBar.scaleY = 1f; miniBar.translationX = 0f; miniBar.translationY = 0f; miniBar.alpha = 1f
        miniOutline.radius = MiniGeom.radius(this); miniBar.invalidateOutline()
        miniPlay.alpha = 1f; miniClose.alpha = 1f; miniProgress.alpha = 1f
    }

    /** 0 = card in its corner, 1 = grown to the full-width player at the top (same geometry the watch page uses). */
    private fun setCardProgress(f: Float) {
        val cw = miniBar.width.toFloat().takeIf { it > 0 } ?: return
        val scale = 1f + f * (root.width / cw - 1f)
        miniBar.pivotX = 0f; miniBar.pivotY = 0f
        miniBar.scaleX = scale; miniBar.scaleY = scale
        miniBar.translationX = -miniBar.left * f
        miniBar.translationY = (root.paddingTop - miniBar.top) * f
        miniOutline.radius = MiniGeom.radius(this) * (1f - f) / scale
        miniBar.invalidateOutline()
        val a = (1f - f * 3f).coerceIn(0f, 1f)
        miniPlay.alpha = a; miniClose.alpha = a; miniProgress.alpha = a
    }

    /** Card: drag up (follows the finger) to open the watch page, swipe sideways to dismiss, tap to open. */
    private fun setupMiniDrag() {
        val d = resources.displayMetrics.density
        miniBar.direction = -1
        miniBar.horizontal = true
        fun range() = (miniBar.top - root.paddingTop).toFloat().coerceAtLeast(200 * d)
        var f = 0f
        fun animate(from: Float, to: Float, update: (Float) -> Unit, end: () -> Unit = {}) {
            miniAnim?.cancel()
            miniAnim = android.animation.ValueAnimator.ofFloat(from, to).apply {
                duration = 220
                interpolator = android.view.animation.DecelerateInterpolator(1.6f)
                addUpdateListener { update(it.animatedValue as Float) }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    var cancelled = false
                    override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                    override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) end() }
                })
                start()
            }
        }
        miniBar.listener = object : DragFrame.Listener {
            override fun onDragStart() { miniAnim?.cancel() }
            override fun onDrag(dy: Float) { f = (-dy / range()).coerceIn(0f, 1f); setCardProgress(f) }
            override fun onDragEnd(velocityY: Float) {
                val item = Playback.state.value.item
                if (item != null && (f > 0.5f || velocityY < -600 * d)) {
                    openVideo(item, expandFrom = (1f - f).coerceIn(0.02f, 1f))
                } else animate(f, 0f, { f = it; setCardProgress(it) })
            }
            override fun onDragX(dx: Float) {
                miniAnim?.cancel()
                miniBar.translationX = dx
                miniBar.alpha = (1f - kotlin.math.abs(dx) / (miniBar.width * 1.1f)).coerceIn(0.1f, 1f)
            }
            override fun onDragXEnd(velocityX: Float) {
                val dx = miniBar.translationX
                val w = miniBar.width.toFloat()
                val away = kotlin.math.abs(dx) > w * 0.35f || kotlin.math.abs(velocityX) > 800 * d
                if (away) {
                    val sign = if (dx != 0f) kotlin.math.sign(dx) else kotlin.math.sign(velocityX)
                    animate(dx, sign * (w + miniBar.left), { miniBar.translationX = it; miniBar.alpha = (1f - kotlin.math.abs(it) / (w * 1.1f)).coerceIn(0f, 1f) }) {
                        Playback.close()
                    }
                } else animate(dx, 0f, { miniBar.translationX = it; miniBar.alpha = (1f - kotlin.math.abs(it) / (w * 1.1f)).coerceIn(0.1f, 1f) })
            }
        }
    }

    override fun onStop() {
        super.onStop()
        miniProgress.removeCallbacks(ticker)
        Playback.player.removeListener(miniListener)
        miniPlayer.player = null
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleTab(intent) }

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
    val f = d.file?.let { java.io.File(it) }
    if (f == null || !f.exists()) { Toast.makeText(this, "ไม่พบไฟล์ที่ดาวน์โหลดไว้ (อาจถูกลบไปแล้ว)", Toast.LENGTH_SHORT).show(); return }
    startActivity(Intent(this, WatchActivity::class.java).putExtra(WatchActivity.EXTRA_ITEM, d.video()).putExtra(WatchActivity.EXTRA_FILE, f.path)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
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

