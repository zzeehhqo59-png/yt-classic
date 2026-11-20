package com.bear.ytclassic

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.request.transformations
import coil3.transform.CircleCropTransformation

class RatioImageView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : AppCompatImageView(c, a) {
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, width * 9 / 16)
    }
}

fun ImageView.loadAvatar(url: String?) {
    if (url.isNullOrBlank()) { setImageDrawable(null); return }
    load(url) { transformations(CircleCropTransformation()) }
}

/** List of videos. big = home cards, otherwise compact rows (search / related). */
class VideoAdapter(
    private val big: Boolean,
    private val onClick: (VideoItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val items = ArrayList<VideoItem>()
    var loadingMore = false
        set(v) {
            if (field == v) return
            field = v
            if (v) notifyItemInserted(items.size) else notifyItemRemoved(items.size)
        }

    fun set(list: List<VideoItem>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    fun add(list: List<VideoItem>) { val s = items.size; items.addAll(list); notifyItemRangeInserted(s, list.size) }

    override fun getItemCount() = items.size + if (loadingMore) 1 else 0
    override fun getItemViewType(position: Int) = if (position >= items.size) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        if (viewType == 1) return object : RecyclerView.ViewHolder(inf.inflate(R.layout.item_loading, parent, false)) {}
        return VH(inf.inflate(if (big) R.layout.item_video_big else R.layout.item_video_compact, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is VH) holder.bind(items[position])
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val thumb: ImageView = v.findViewById(R.id.thumb)
        private val duration: TextView = v.findViewById(R.id.duration)
        private val title: TextView = v.findViewById(R.id.title)
        private val meta: TextView = v.findViewById(R.id.meta)
        private val channel: TextView? = v.findViewById(R.id.channel)
        private val avatar: ImageView? = v.findViewById(R.id.avatar)

        fun bind(it: VideoItem) {
            thumb.load(it.thumb) { crossfade(true) }
            when {
                it.live -> { duration.text = "สด"; duration.setBackgroundResource(R.drawable.bg_live); duration.visibility = View.VISIBLE }
                it.upcoming -> { duration.text = "เร็วๆ นี้"; duration.setBackgroundResource(R.drawable.bg_duration); duration.visibility = View.VISIBLE }
                it.durationSec > 0 -> { duration.text = Fmt.duration(it.durationSec); duration.setBackgroundResource(R.drawable.bg_duration); duration.visibility = View.VISIBLE }
                else -> duration.visibility = View.GONE
            }
            title.text = it.title
            val views = Fmt.views(it.views, it.live)
            val extra = if (it.members) "เฉพาะสมาชิก" else ""
            if (channel != null) {
                channel.text = it.channel
                meta.text = listOf(views, Fmt.ageMs(it.uploadedMs, it.age).takeUnless { _ -> it.live }.orEmpty(), extra).filter { s -> s.isNotBlank() }.joinToString(" · ")
            } else {
                meta.text = listOf(it.channel, views, Fmt.ageMs(it.uploadedMs, it.age).takeUnless { _ -> it.live }.orEmpty(), extra).filter { s -> s.isNotBlank() }.joinToString(" · ")
            }
            avatar?.loadAvatar(it.avatar)
            itemView.setOnClickListener { _ -> onClick(it) }
        }
    }
}

/** Calls [load] when the list is scrolled near its end. */
fun RecyclerView.onNearEnd(load: () -> Unit) {
    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
            if (dy > 0 && lm.findLastVisibleItemPosition() >= (rv.adapter?.itemCount ?: 0) - 4) load()
        }
    })
}
