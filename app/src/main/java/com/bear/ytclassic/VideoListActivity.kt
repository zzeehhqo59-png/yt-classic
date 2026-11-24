package com.bear.ytclassic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Watch history, liked videos, a local playlist (incl. ดูภายหลัง) or a YouTube playlist. */
class VideoListActivity : YtActivity() {
    companion object {
        fun history(c: Context) = Intent(c, VideoListActivity::class.java).putExtra("mode", "history")
        fun local(c: Context, id: Long) = Intent(c, VideoListActivity::class.java).putExtra("mode", "local").putExtra("id", id)
        fun liked(c: Context) = Intent(c, VideoListActivity::class.java).putExtra("mode", "liked")
        fun remote(c: Context, url: String, title: String) = Intent(c, VideoListActivity::class.java).putExtra("mode", "remote").putExtra("url", url).putExtra("title", title)
    }

    private lateinit var mode: String
    private var plId = 0L
    private lateinit var adapter: VideoAdapter
    private val header = PlaylistHeaderAdapter({ playFrom(0) }, { setFilter(it) })
    private var allItems: List<VideoItem> = emptyList()
    private var filter = PlaylistFilter.ALL
    private var remoteOwner: String? = null
    private var remoteCount = -1
    private lateinit var message: TextView
    private lateinit var progress: View
    private var pager: ListPager? = null
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(lightBars = true)
        setContentView(R.layout.activity_list)
        attachPlayerUi()
        findViewById<View>(R.id.root).padForSystemBars()
        mode = intent.getStringExtra("mode") ?: "history"
        plId = intent.getLongExtra("id", 0)
        message = findViewById(R.id.message)
        progress = findViewById(R.id.progress)
        findViewById<View>(R.id.btnBack).setOnClickListener { if (!WatchLayer.consumeBack(this)) finish() }
        adapter = VideoAdapter(false, onMore = { v, item -> moreFor(v, item) }, canHide = mode == "history") { v ->
            if (mode == "history") openVideo(v) else playFrom(adapter.items.indexOfFirst { it.url == v.url })
        }
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = if (mode == "history") adapter else ConcatAdapter(header, adapter)
        val menu = findViewById<View>(R.id.btnMenu)
        when (mode) {
            "history" -> { setTitleText("ประวัติการดู"); menu.visibility = View.VISIBLE; menu.setOnClickListener { topMenu(it) } }
            "local", "liked" -> { menu.visibility = View.VISIBLE; menu.setOnClickListener { topMenu(it) } }
            else -> { setTitleText(intent.getStringExtra("title").orEmpty()); list.onNearEnd { loadMore() }; loadRemote() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (mode != "remote") reloadLocal() else adapter.refreshProgress()
    }

    private fun setTitleText(t: String, sub: String? = null) {
        findViewById<TextView>(R.id.barTitle).text = t
        findViewById<TextView>(R.id.barSub).apply { text = sub; visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE }
    }

    private fun reloadLocal() {
        val items: List<VideoItem>
        if (mode == "history") {
            items = History.recent(1000).map { it.video.copy(age = "ดูเมื่อ " + Fmt.ageMs(it.watchedAt, "")) }
            setTitleText("ประวัติการดู", if (items.isNotEmpty()) "${items.size} วิดีโอ" else null)
            message.text = "ยังไม่มีวิดีโอที่ดู\nวิดีโอที่คุณดูจะแสดงที่นี่"
        } else if (mode == "liked") {
            items = Availability.apply(Likes.liked())
            setTitleText("วิดีโอที่ชอบ", "${items.size} วิดีโอ")
            message.text = "ยังไม่มีวิดีโอที่ชอบ\nกด 👍 ในหน้าวิดีโอเพื่อเก็บไว้ที่นี่"
            setHeader("วิดีโอที่ชอบ", null, items)
        } else {
            val pl = Playlists.get(plId) ?: run { finish(); return }
            items = Availability.apply(Playlists.items(plId))
            setTitleText(pl.name, "${items.size} วิดีโอ")
            message.text = if (plId == Playlists.WATCH_LATER) "ยังไม่มีวิดีโอในดูภายหลัง\nกด ⋮ ที่วิดีโอแล้วเลือก \"บันทึกลงในดูภายหลัง\"" else "เพลย์ลิสต์นี้ยังว่างอยู่"
            setHeader(pl.name, null, items)
        }
        adapter.progress = History.progressMap()
        showItems(items.map { if (mode == "history") it.copy(uploadedMs = 0) else it })
    }

    private fun setFilter(id: Int) {
        if (filter == id) return
        filter = id
        showItems(allItems)
        if (mode != "history") {
            val title = findViewById<TextView>(R.id.barTitle).text.toString()
            setHeader(title, remoteOwner, allItems, if (mode == "remote" && remoteCount >= 0) remoteCount else allItems.size)
        }
    }

    private fun showItems(items: List<VideoItem>) {
        allItems = items
        val shown = if (mode == "history") items else PlaylistFilter.apply(items, filter)
        adapter.set(shown)
        message.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        if (items.isNotEmpty() && shown.isEmpty()) {
            message.text = "ไม่มีวิดีโอในตัวกรองนี้"
            message.visibility = View.VISIBLE
        }
    }

    private fun setHeader(title: String, owner: String?, items: List<VideoItem>, count: Int = items.size) {
        val cover = items.firstOrNull { PlayQueue.isPlayable(it) && it.thumb != null }?.thumb ?: items.firstOrNull()?.thumb
        val showFilter = mode == "liked" || mode == "local"
        header.set(PlaylistHeaderAdapter.Info(title, owner, count, cover, items.any { PlayQueue.isPlayable(it) }, showFilter, filter))
    }

    /** "เล่นทั้งหมด" / tapping a video: play the list in order from [index], skipping videos that can't be played. */
    private fun playFrom(index: Int) {
        val items = adapter.items.toList()
        val tapped = items.getOrNull(index)
        if (tapped != null && tapped.unavailable) {
            Toast.makeText(this, tapped.unplayableBadge() ?: "วิดีโอนี้ไม่พร้อมใช้งาน", Toast.LENGTH_SHORT).show(); return
        }
        val first = PlayQueue.start(items, index.coerceAtLeast(0))
        if (first == null) Toast.makeText(this, "ไม่มีวิดีโอที่เล่นได้ในเพลย์ลิสต์นี้", Toast.LENGTH_SHORT).show() else openVideo(first)
    }

    /** Saved lists: drop every deleted / private video at once. */
    private fun removeUnavailable() {
        val bad = adapter.items.filter { it.unavailable }
        if (bad.isEmpty()) { Toast.makeText(this, "ไม่มีวิดีโอที่ไม่พร้อมใช้งาน", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setMessage("นำวิดีโอที่ไม่พร้อมใช้งาน ${bad.size} รายการออกใช่ไหม")
            .setPositiveButton("นำออก") { _, _ ->
                bad.forEach { if (mode == "liked") Likes.set(it, 0) else Playlists.remove(plId, it.url) }
                Toast.makeText(this, "นำออกแล้ว ${bad.size} รายการ", Toast.LENGTH_SHORT).show()
                reloadLocal()
            }.setNegativeButton("ยกเลิก", null).show()
    }

    private fun moreFor(anchor: View, v: VideoItem) {
        val extra = when (mode) {
            "history" -> listOf("นำออกจากประวัติการดู" to { History.remove(v.url); reloadLocal() })
            "local" -> listOf("นำออกจาก ${Playlists.get(plId)?.name.orEmpty()}" to { Playlists.remove(plId, v.url); reloadLocal() })
            "liked" -> listOf("นำออกจากวิดีโอที่ชอบ" to { Likes.set(v, 0); reloadLocal() })
            else -> emptyList()
        }
        anchor.showVideoMenu(v, extra)
    }

    private fun topMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        if (mode == "history") {
            m.menu.add("ล้างประวัติการดูทั้งหมด").setOnMenuItemClickListener { confirmClearHistory(this) { reloadLocal() }; true }
        } else {
            m.menu.add("นำวิดีโอที่ไม่พร้อมใช้งานออกทั้งหมด").setOnMenuItemClickListener { removeUnavailable(); true }
            if (mode == "liked") { m.show(); return }
            if (plId != Playlists.WATCH_LATER) {
                m.menu.add("เปลี่ยนชื่อ").setOnMenuItemClickListener {
                    askName("เปลี่ยนชื่อเพลย์ลิสต์", Playlists.get(plId)?.name.orEmpty()) { Playlists.rename(plId, it); reloadLocal() }; true
                }
                m.menu.add("ลบเพลย์ลิสต์").setOnMenuItemClickListener {
                    AlertDialog.Builder(this).setMessage("ลบเพลย์ลิสต์ ${Playlists.get(plId)?.name} ใช่ไหม")
                        .setPositiveButton("ลบ") { _, _ -> Playlists.delete(plId); finish() }.setNegativeButton("ยกเลิก", null).show(); true
                }
            } else {
                m.menu.add("นำวิดีโอทั้งหมดออก").setOnMenuItemClickListener {
                    AlertDialog.Builder(this).setMessage("นำวิดีโอทั้งหมดออกจากดูภายหลังใช่ไหม")
                        .setPositiveButton("นำออก") { _, _ -> Playlists.clear(plId); reloadLocal() }.setNegativeButton("ยกเลิก", null).show(); true
                }
            }
        }
        m.show()
    }

    private fun loadRemote() {
        progress.visibility = View.VISIBLE
        job = lifecycleScope.launch {
            try {
                val p = withContext(Dispatchers.IO) { Repo.playlistPager(intent.getStringExtra("url").orEmpty()) }
                pager = p
                val items = Availability.apply(p.next())
                val name = runCatching { p.extractor.name }.getOrNull()
                val pe = p.extractor as? org.schabi.newpipe.extractor.playlist.PlaylistExtractor
                remoteCount = pe?.let { runCatching { it.streamCount.toInt() }.getOrDefault(-1) } ?: -1
                remoteOwner = pe?.let { runCatching { it.uploaderName }.getOrNull() }
                setTitleText(name ?: intent.getStringExtra("title").orEmpty(), if (remoteCount > 0) "$remoteCount วิดีโอ" else null)
                adapter.progress = History.progressMap()
                showItems(items)
                setHeader(name ?: intent.getStringExtra("title").orEmpty(), remoteOwner, items, if (remoteCount >= 0) remoteCount else items.size)
                if (items.isEmpty()) { message.text = "เพลย์ลิสต์นี้ไม่มีวิดีโอ"; message.visibility = View.VISIBLE }
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                message.text = Errors.list(e); message.visibility = View.VISIBLE
            } finally { progress.visibility = View.GONE }
        }
    }

    private fun loadMore() {
        val p = pager ?: return
        if (job?.isActive == true || !p.hasMore) return
        adapter.loadingMore = true
        job = lifecycleScope.launch {
            try { val more = Availability.apply(p.next()); adapter.loadingMore = false; adapter.add(more) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { android.util.Log.w("VideoListActivity", "next page failed", e) }
            finally { adapter.loadingMore = false }
        }
    }
}

fun confirmClearHistory(c: Context, done: () -> Unit) {
    AlertDialog.Builder(c).setTitle("ล้างประวัติการดูทั้งหมดใช่ไหม")
        .setMessage("วิดีโอทั้งหมดในประวัติการดูและตำแหน่งที่ดูค้างไว้จะถูกลบ และหน้าแรกจะแนะนำจากช่องที่ติดตามแทน")
        .setPositiveButton("ล้าง") { _, _ -> History.clear(); Toast.makeText(c, "ล้างประวัติการดูแล้ว", Toast.LENGTH_SHORT).show(); done() }
        .setNegativeButton("ยกเลิก", null).show()
}
