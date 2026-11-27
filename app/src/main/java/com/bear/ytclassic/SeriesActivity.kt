package com.bear.ytclassic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class SeriesActivity : YtActivity() {
    companion object {
        fun intent(c: Context, url: String, title: String, channelUrl: String?) =
            Intent(c, SeriesActivity::class.java).putExtra("url", url).putExtra("title", title).putExtra("channelUrl", channelUrl)
    }

    private val vm: SeriesViewModel by viewModels()
    private val hero = SeriesHeroAdapter({ vm.ui.value }, onPlay = { play(null) })
    private val episodes = EpisodeAdapter { _, index -> play(index) }
    private var boundEps: List<VideoItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(lightBars = true)
        setContentView(R.layout.activity_series)
        attachPlayerUi()
        findViewById<View>(R.id.root).padForSystemBars()
        val url = intent.getStringExtra("url").orEmpty()
        if (url.isBlank()) { finish(); return }
        findViewById<TextView>(R.id.barTitle).text = intent.getStringExtra("title").orEmpty()
        findViewById<View>(R.id.btnBack).setOnClickListener { if (!WatchLayer.consumeBack(this)) finish() }
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = ConcatAdapter(hero, episodes)
        list.onNearEnd { vm.loadMore() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.ui.collect { render(it) } }
        }
        vm.load(url, intent.getStringExtra("title").orEmpty())
    }

    override fun onResume() {
        super.onResume()
        episodes.progress = History.progressMap()
        if (boundEps.isNotEmpty()) episodes.notifyDataSetChanged()
        vm.refreshResume()
    }

    private fun render(st: SeriesUi) {
        findViewById<View>(R.id.progress).visibility = if (st.loading && st.episodes.isEmpty()) View.VISIBLE else View.GONE
        val msg = findViewById<TextView>(R.id.message)
        msg.text = st.error.orEmpty()
        msg.visibility = if (st.error != null && st.episodes.isEmpty()) View.VISIBLE else View.GONE
        if (st.title.isNotBlank()) findViewById<TextView>(R.id.barTitle).text = st.title
        episodes.loadingMore = st.loadingMore
        episodes.progress = History.progressMap()
        if (boundEps != st.episodes) {
            boundEps = st.episodes
            episodes.set(st.episodes)
        }
        hero.notifyItemChanged(0)
    }

    private fun play(episodeIndex: Int?) {
        lifecycleScope.launch {
            vm.fillForPlayback()
            val st = vm.ui.value
            if (st.episodes.isEmpty()) return@launch
            val start = episodeIndex ?: SeriesEpisodes.startIndex(st.episodes, st.resume)
            val first = PlayQueue.start(SeriesEpisodes.queueFrom(st.episodes, start), 0)
            if (first == null) Toast.makeText(this@SeriesActivity, "ไม่มีตอนที่เล่นได้", Toast.LENGTH_SHORT).show()
            else openVideo(first)
        }
    }
}
