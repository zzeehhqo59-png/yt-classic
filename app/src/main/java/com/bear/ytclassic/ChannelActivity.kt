package com.bear.ytclassic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun Context.openChannel(url: String?, name: String, avatar: String?) {
    if (url.isNullOrBlank()) return
    startActivity(Intent(this, ChannelActivity::class.java)
        .putExtra("url", url).putExtra("name", name).putExtra("avatar", avatar))
}

class ChannelActivity : AppCompatActivity() {
    override fun onResume() { super.onResume(); videos.refreshProgress() }
    private lateinit var url: String
    private var ch: Channel? = null
    private var banner: String? = null
    private val header = Header()
    private val videos = VideoAdapter(false) { openVideo(it) }
    private var pager: Pager? = null
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(lightBars = true)
        setContentView(R.layout.activity_channel)
        findViewById<View>(R.id.root).padForSystemBars()
        url = intent.getStringExtra("url")!!
        ch = Channel(url, intent.getStringExtra("name").orEmpty(), intent.getStringExtra("avatar"))
        findViewById<TextView>(R.id.barTitle).text = ch?.name
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSearch).setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = ConcatAdapter(header, videos)
        list.onNearEnd { loadMore() }
        load()
    }

    private fun load() {
        val progress = findViewById<View>(R.id.progress)
        val msg = findViewById<TextView>(R.id.message)
        progress.visibility = View.VISIBLE
        job = lifecycleScope.launch {
            try {
                val info = Repo.channel(url)
                ch = Channel(url, info.name, info.avatars.pick(176) ?: ch?.avatar, info.subscriberCount)
                banner = info.banners.pick(400)
                if (Subs.isSubscribed(url)) Subs.update(ch!!)
                findViewById<TextView>(R.id.barTitle).text = info.name
                header.notifyItemChanged(0)
                val p = Repo.channelVideos(url)
                pager = p
                videos.set(p.next())
                if (videos.items.isEmpty()) { msg.text = "ช่องนี้ยังไม่มีวิดีโอ"; msg.visibility = View.VISIBLE }
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                msg.text = Errors.list(e).replace("รายการวิดีโอ", "ช่องนี้"); msg.visibility = View.VISIBLE
            } finally { progress.visibility = View.GONE }
        }
    }

    private fun loadMore() {
        val p = pager ?: return
        if (job?.isActive == true || !p.hasMore) return
        videos.loadingMore = true
        job = lifecycleScope.launch {
            try { val more = p.next(); videos.loadingMore = false; videos.add(more) }
            catch (e: CancellationException) { throw e } catch (_: Throwable) { }
            finally { videos.loadingMore = false }
        }
    }

    private inner class Header : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_channel_header, parent, false)) {}
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val v = h.itemView
            val c = ch
            v.findViewById<ImageView>(R.id.banner).apply {
                if (banner != null) { visibility = View.VISIBLE; load(banner) } else visibility = View.GONE
            }
            v.findViewById<ImageView>(R.id.avatar).loadAvatar(c?.avatar)
            v.findViewById<TextView>(R.id.name).text = c?.name
            v.findViewById<TextView>(R.id.subs).text = Fmt.subscribers(c?.subscribers ?: -1)
            v.findViewById<TextView>(R.id.btnSubscribe).bindSubscribe({ ch })
        }
    }
}
