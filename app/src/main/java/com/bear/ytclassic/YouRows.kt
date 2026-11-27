package com.bear.ytclassic

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil3.load

/** Horizontal history thumbnails on the You tab. */
class YouHistoryAdapter(private val onClick: (VideoItem) -> Unit) : RecyclerView.Adapter<YouHistoryAdapter.VH>() {
    private val items = ArrayList<VideoItem>()
    private var progress: Map<String, Int> = emptyMap()
    fun set(list: List<VideoItem>) {
        items.clear(); items.addAll(list)
        progress = History.progressMap()
        notifyDataSetChanged()
    }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_you_video, parent, false))
    override fun onBindViewHolder(h: VH, position: Int) {
        val video = items[position]
        h.thumb.load(video.thumb)
        h.title.text = video.title
        if (video.live) { h.duration.text = "สด"; h.duration.setBackgroundResource(R.drawable.bg_live); h.duration.visibility = View.VISIBLE }
        else if (video.durationSec > 0) { h.duration.text = Fmt.duration(video.durationSec); h.duration.setBackgroundResource(R.drawable.bg_duration); h.duration.visibility = View.VISIBLE }
        else h.duration.visibility = View.GONE
        val p = progress[video.url] ?: 0
        h.watched.visibility = if (p > 0 && !video.live) View.VISIBLE else View.GONE
        h.watched.progress = p
        h.itemView.setOnClickListener { onClick(video) }
    }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val duration: TextView = v.findViewById(R.id.duration)
        val watched: ProgressBar = v.findViewById(R.id.watched)
    }
}

/** A card on the You tab playlist row (watch later, liked, user playlists, or "create"). */
data class YouPlaylistCard(
    val id: Long?,
    val title: String,
    val meta: String,
    val cover: String?,
    val icon: Int,
    val create: Boolean = false,
)

class YouPlaylistAdapter(
    private val onClick: (YouPlaylistCard) -> Unit,
    private val onLong: (YouPlaylistCard) -> Unit,
) : RecyclerView.Adapter<YouPlaylistAdapter.VH>() {
    private val items = ArrayList<YouPlaylistCard>()
    fun set(list: List<YouPlaylistCard>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_you_playlist, parent, false))
    override fun onBindViewHolder(h: VH, position: Int) {
        val card = items[position]
        h.title.text = card.title
        h.meta.text = card.meta
        h.icon.setImageResource(card.icon)
        h.icon.setColorFilter(ContextCompat.getColor(h.itemView.context, R.color.text1))
        if (card.cover.isNullOrBlank()) h.cover.setImageDrawable(null) else h.cover.load(card.cover)
        h.itemView.setOnClickListener { onClick(card) }
        h.itemView.setOnLongClickListener {
            if (card.create || card.id == null || card.id == Playlists.WATCH_LATER) false else { onLong(card); true }
        }
    }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val icon: ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
    }
}
