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

class ShortsImageView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : AppCompatImageView(c, a) {
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, width * 16 / 9)
    }
}

fun ImageView.loadAvatar(url: String?) {
    if (url.isNullOrBlank()) { setImageDrawable(null); return }
    load(url) { transformations(CircleCropTransformation()) }
}

/** List of videos. big = home cards, otherwise compact rows (search / related). */
class VideoAdapter(
    private val big: Boolean,
    private val onMore: ((View, VideoItem) -> Unit)? = null,
    /** Playlists keep deleted / private videos (dimmed) even with "ซ่อนวิดีโอที่ดูไม่ได้" on. */
    private val canHide: Boolean = true,
    private val onClick: (VideoItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val items = ArrayList<VideoItem>()
    /** Everything given to the adapter; [items] is this minus unplayable videos when that setting is on. */
    private val raw = ArrayList<VideoItem>()
    private var hiding = canHide && Prefs.hideUnplayable
    private fun visible(l: List<VideoItem>) = if (hiding) l.filter { it.unplayableBadge() == null } else l
    /** url -> watched fraction (0..1000), shown as a red bar on the thumbnail. */
    var progress: Map<String, Int> = emptyMap()
    fun refreshProgress() {
        progress = History.progressMap()
        if (hiding != (canHide && Prefs.hideUnplayable)) { hiding = canHide && Prefs.hideUnplayable; items.clear(); items.addAll(visible(raw)); notifyDataSetChanged() }
        else notifyItemRangeChanged(0, items.size)
    }
    var loadingMore = false
        set(v) {
            if (field == v) return
            field = v
            if (v) notifyItemInserted(items.size) else notifyItemRemoved(items.size)
        }

    fun set(list: List<VideoItem>) { hiding = canHide && Prefs.hideUnplayable; raw.clear(); raw.addAll(list); items.clear(); items.addAll(visible(list)); notifyDataSetChanged() }
    /** Removes matching videos now; returns them with their positions so [restore] can undo. */
    fun removeWhere(pred: (VideoItem) -> Boolean): List<Pair<Int, VideoItem>> {
        val gone = items.withIndex().filter { pred(it.value) }.map { it.index to it.value }
        if (gone.isEmpty()) return gone
        raw.removeAll { pred(it) }
        gone.asReversed().forEach { (i, _) -> items.removeAt(i); notifyItemRemoved(i) }
        return gone
    }

    fun restore(gone: List<Pair<Int, VideoItem>>) {
        gone.forEach { (i, v) ->
            val at = i.coerceIn(0, items.size)
            items.add(at, v); raw.add(at.coerceAtMost(raw.size), v); notifyItemInserted(at)
        }
    }

    fun add(list: List<VideoItem>) { raw.addAll(list); val v = visible(list); val s = items.size; items.addAll(v); notifyItemRangeInserted(s, v.size) }

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
        private val more: View? = v.findViewById(R.id.more)
        private val watched: android.widget.ProgressBar? = v.findViewById(R.id.watched)

        fun bind(it: VideoItem) {
            more?.setOnClickListener { view -> (onMore ?: { a, b -> a.showVideoMenu(b) })(view, it) }
            itemView.setOnClickListener { _ -> onClick(it) }
            val p = progress[it.url] ?: 0
            watched?.visibility = if (p > 0 && it.kind == 0) View.VISIBLE else View.GONE
            watched?.progress = p
            // deleted / private videos stay in the list, dimmed
            itemView.alpha = if (it.unavailable && it.kind == 0) 0.5f else 1f
            if (it.kind != 0) { bindOther(it); return }
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            avatar?.visibility = View.VISIBLE
            thumb.load(it.thumb) { crossfade(true) }
            val badge = it.unplayableBadge()
            when {
                it.live -> { duration.text = "สด"; duration.setBackgroundResource(R.drawable.bg_live); duration.visibility = View.VISIBLE }
                badge != null -> { duration.text = badge; duration.setBackgroundResource(R.drawable.bg_badge); duration.visibility = View.VISIBLE }
                it.durationSec > 0 -> { duration.text = Fmt.duration(it.durationSec); duration.setBackgroundResource(R.drawable.bg_duration); duration.visibility = View.VISIBLE }
                else -> duration.visibility = View.GONE
            }
            title.text = it.title
            val views = Fmt.views(it.views, it.live)
            val extra = when {
                it.upcoming -> "รอเปิดตัว" + (it.scheduledEpochMs.takeIf { t -> t > 0 }?.let { t ->
                    " " + Fmt.thaiDateTime(java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), java.time.ZoneId.systemDefault()))
                } ?: "")
                it.unavailable -> ""
                else -> badge.orEmpty()
            }
            if (channel != null) {
                channel.text = it.channel
                meta.text = listOf(views, Fmt.ageMs(it.uploadedMs, it.age).takeUnless { _ -> it.live || it.upcoming }.orEmpty(), extra).filter { s -> s.isNotBlank() }.joinToString(" · ")
            } else {
                meta.text = listOf(it.channel, views, Fmt.ageMs(it.uploadedMs, it.age).takeUnless { _ -> it.live || it.upcoming }.orEmpty(), extra).filter { s -> s.isNotBlank() }.joinToString(" · ")
            }
            avatar?.loadAvatar(it.avatar)
            val openCh = View.OnClickListener { _ ->
                val url = VideoClicks.channelUrl(it) ?: return@OnClickListener
                itemView.context.openChannel(url, it.channel, it.avatar)
            }
            avatar?.link(if (VideoClicks.opensChannel(VideoClicks.Part.AVATAR)) openCh else null, "ช่อง")
            // the channel name line in compact rows is part of the row (it spans the row; tapping it used to open the channel)
            channel?.link(if (VideoClicks.opensChannel(VideoClicks.Part.CHANNEL_NAME)) openCh else null, null)
        }

        /** Gives [this] its own tap action, or makes it plain again so the row's click (the bound item) gets the tap. */
        private fun View.link(l: View.OnClickListener?, desc: String?) {
            setOnClickListener(l)
            isClickable = l != null; isFocusable = l != null
            contentDescription = if (l != null) desc else null
        }

        /** Channel / playlist rows in search results. */
        private fun bindOther(it: VideoItem) {
            title.text = it.title
            avatar?.link(null, null); channel?.link(null, null)
            if (it.kind == 1) {
                thumb.scaleType = ImageView.ScaleType.FIT_CENTER
                thumb.load(it.thumb) { transformations(CircleCropTransformation()) }
                duration.visibility = View.GONE
                avatar?.visibility = View.GONE
                val parts = listOf(if (it.views >= 0) Fmt.subscribers(it.views) else "", if (it.count >= 0) "วิดีโอ ${Fmt.shortNum(it.count)} รายการ" else "")
                    .filter { s -> s.isNotBlank() }
                channel?.text = "ช่อง"
                meta.text = parts.joinToString(" · ")
            } else {
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                thumb.load(it.thumb) { crossfade(true) }
                duration.text = if (it.count >= 0) "${Fmt.shortNum(it.count)} วิดีโอ" else "เพลย์ลิสต์"
                duration.setBackgroundResource(R.drawable.bg_duration); duration.visibility = View.VISIBLE
                avatar?.visibility = View.GONE
                channel?.text = it.channel
                meta.text = if (channel == null) listOf(it.channel, "เพลย์ลิสต์").filter { s -> s.isNotBlank() }.joinToString(" · ") else "เพลย์ลิสต์"
            }
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
