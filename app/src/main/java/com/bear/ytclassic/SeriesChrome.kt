package com.bear.ytclassic

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade

class SeriesHeroAdapter(
    private val state: () -> SeriesUi,
    private val onPlay: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_series_hero, parent, false)
    ) {}
    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val st = state()
        val v = h.itemView
        v.findViewById<ImageView>(R.id.heroThumb).load(st.thumb) { crossfade(true) }
        v.findViewById<TextView>(R.id.heroTitle).text = st.title
        v.findViewById<TextView>(R.id.heroOwner).apply {
            text = st.owner.orEmpty()
            visibility = if (st.owner.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val play = v.findViewById<TextView>(R.id.btnPlay)
        play.text = SeriesEpisodes.playLabel(st.resume)
        play.setOnClickListener { onPlay() }
    }
}

class EpisodeAdapter(
    private val onClick: (VideoItem, Int) -> Unit,
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {
    var items: List<VideoItem> = emptyList(); private set
    var progress: Map<String, Int> = emptyMap()
    var loadingMore = false
        set(v) {
            if (field == v) return
            field = v
            if (v) notifyItemInserted(items.size) else notifyItemRemoved(items.size)
        }

    fun set(list: List<VideoItem>) {
        items = list; notifyDataSetChanged()
    }

    override fun getItemCount() = items.size + if (loadingMore) 1 else 0
    override fun getItemViewType(position: Int) = if (position >= items.size) 1 else 0
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 1) VH(inf.inflate(R.layout.item_loading, parent, false))
        else VH(inf.inflate(R.layout.item_episode, parent, false))
    }
    override fun onBindViewHolder(h: VH, position: Int) {
        if (position >= items.size) return
        val ep = items[position]
        h.itemView.findViewById<ImageView>(R.id.thumb).load(ep.thumb) { crossfade(true) }
        h.itemView.findViewById<TextView>(R.id.epLabel).text = SeriesEpisodes.episodeLabel(position)
        h.itemView.findViewById<TextView>(R.id.title).text = ep.title
        val dur = h.itemView.findViewById<TextView>(R.id.duration)
        if (ep.durationSec > 0) { dur.text = Fmt.duration(ep.durationSec); dur.visibility = View.VISIBLE } else dur.visibility = View.GONE
        val bar = h.itemView.findViewById<android.widget.ProgressBar>(R.id.watched)
        val p = progress[ep.url] ?: 0
        bar.visibility = if (p > 0) View.VISIBLE else View.GONE
        bar.progress = p
        h.itemView.setOnClickListener { onClick(ep, position) }
        h.itemView.findViewById<View>(R.id.more).setOnClickListener { it.showVideoMenu(ep) }
    }
    class VH(v: View) : RecyclerView.ViewHolder(v)
}
