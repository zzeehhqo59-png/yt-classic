package com.bear.ytclassic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Watch history, a local playlist (incl. ดูภายหลัง) or a YouTube playlist. */
class VideoListActivity : AppCompatActivity() {
    companion object {
        fun history(c: Context) = Intent(c, VideoListActivity::class.java).putExtra("mode", "history")
        fun local(c: Context, id: Long) = Intent(c, VideoListActivity::class.java).putExtra("mode", "local").putExtra("id", id)
        fun remote(c: Context, url: String, title: String) = Intent(c, VideoListActivity::class.java).putExtra("mode", "remote").putExtra("url", url).putExtra("title", title)
    }

    private lateinit var mode: String
    private var plId = 0L
    private lateinit var adapter: VideoAdapter
    private lateinit var message: TextView
    private lateinit var progress: View
    private var pager: ListPager? = null
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(lightBars = true)
        setContentView(R.layout.activity_list)
        findViewById<View>(R.id.root).padForSystemBars()
        mode = intent.getStringExtra("mode") ?: "history"
        plId = intent.getLongExtra("id", 0)
        message = findViewById(R.id.message)
        progress = findViewById(R.id.progress)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        adapter = VideoAdapter(false, onMore = { v, item -> moreFor(v, item) }) { openVideo(it) }
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        val menu = findViewById<View>(R.id.btnMenu)
        when (mode) {
            "history" -> { setTitleText("ประวัติการดู"); menu.visibility = View.VISIBLE; menu.setOnClickListener { topMenu(it) } }
            "local" -> { menu.visibility = View.VISIBLE; menu.setOnClickListener { topMenu(it) } }
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
        } else {
            val pl = Playlists.get(plId) ?: run { finish(); return }
            items = Playlists.items(plId)
            setTitleText(pl.name, "${items.size} วิดีโอ")
            message.text = if (plId == Playlists.WATCH_LATER) "ยังไม่มีวิดีโอในดูภายหลัง\nกด ⋮ ที่วิดีโอแล้วเลือก \"บันทึกลงในดูภายหลัง\"" else "เพลย์ลิสต์นี้ยังว่างอยู่"
        }
        adapter.progress = History.progressMap()
        adapter.set(items.map { if (mode == "history") it.copy(uploadedMs = 0) else it })
        message.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun moreFor(anchor: View, v: VideoItem) {
        val extra = when (mode) {
            "history" -> listOf("นำออกจากประวัติการดู" to { History.remove(v.url); reloadLocal() })
            "local" -> listOf("นำออกจาก ${Playlists.get(plId)?.name.orEmpty()}" to { Playlists.remove(plId, v.url); reloadLocal() })
            else -> emptyList()
        }
        anchor.showVideoMenu(v, extra)
    }

    private fun topMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        if (mode == "history") {
            m.menu.add("ล้างประวัติการดูทั้งหมด").setOnMenuItemClickListener { confirmClearHistory(this) { reloadLocal() }; true }
        } else {
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
                val p = withContext(Dispatchers.IO) { Repo.playlistPager(intent.getStringExtra("url")!!) }
                pager = p
                val items = p.next()
                val name = runCatching { p.extractor.name }.getOrNull()
                val count = runCatching { (p.extractor as org.schabi.newpipe.extractor.playlist.PlaylistExtractor).streamCount }.getOrDefault(-1)
                setTitleText(name ?: intent.getStringExtra("title").orEmpty(), if (count > 0) "$count วิดีโอ" else null)
                adapter.progress = History.progressMap()
                adapter.set(items)
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
            try { val more = p.next(); adapter.loadingMore = false; adapter.add(more) }
            catch (e: CancellationException) { throw e }
            catch (_: Throwable) { }
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
