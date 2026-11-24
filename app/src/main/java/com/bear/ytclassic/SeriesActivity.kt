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
    private val hero = SeriesHeroAdapter({ vm.ui.value }, onPlay = { play(null) }, onSeason = { openSeasons() })
    private val episodes = EpisodeAdapter { _, index -> play(index) }
    private var boundSeason = -1
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
        vm.load(url, intent.getStringExtra("title").orEmpty(), intent.getStringExtra("channelUrl"))
    }

    override fun onResume() {
        super.onResume()
        episodes.progress = History.progressMap()
        if (boundEps.isNotEmpty()) episodes.notifyDataSetChanged()
    }

    private fun render(st: SeriesUi) {
        findViewById<View>(R.id.progress).visibility = if (st.loading && st.seasons.isEmpty()) View.VISIBLE else View.GONE
        val msg = findViewById<TextView>(R.id.message)
        msg.text = st.error.orEmpty()
        msg.visibility = if (st.error != null && st.seasons.isEmpty()) View.VISIBLE else View.GONE
        if (st.title.isNotBlank()) findViewById<TextView>(R.id.barTitle).text = st.title
        val season = st.seasons.getOrNull(st.seasonIndex)
        val eps = season?.episodes.orEmpty()
        episodes.loadingMore = st.loadingMore
        episodes.progress = History.progressMap()
        if (boundSeason != st.seasonIndex || boundEps != eps) {
            boundSeason = st.seasonIndex
            boundEps = eps
            episodes.set(eps, season?.number)
        }
        hero.notifyItemChanged(0)
    }

    private fun openSeasons() {
        val st = vm.ui.value
        if (st.seasons.isEmpty()) return
        seasonSheet(st.seasons, st.seasonIndex) { vm.selectSeason(it) }
    }

    private fun play(episodeIndex: Int?) {
        lifecycleScope.launch {
            val start = episodeIndex ?: -1
            vm.fillForPlayback(vm.ui.value.seasonIndex)
            val st = vm.ui.value
            val seasons = st.seasons
            if (seasons.isEmpty()) return@launch
            val si: Int
            val ei: Int
            if (start >= 0) {
                si = st.seasonIndex; ei = start
            } else {
                val resume = st.resume
                val found = resume?.let { target ->
                    seasons.indices.firstNotNullOfOrNull { i ->
                        seasons[i].episodes.indexOfFirst { it.url == target.url }.takeIf { it >= 0 }?.let { i to it }
                    }
                }
                if (found != null) { si = found.first; ei = found.second }
                else { si = st.seasonIndex; ei = 0 }
            }
            val queue = SeriesSeasons.queueFrom(seasons, si, ei)
            val first = PlayQueue.start(queue, 0)
            if (first == null) Toast.makeText(this@SeriesActivity, "ไม่มีตอนที่เล่นได้", Toast.LENGTH_SHORT).show()
            else openVideo(first)
        }
    }
}
