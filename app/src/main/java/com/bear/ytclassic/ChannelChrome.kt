package com.bear.ytclassic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade

/** Banner, identity, subscribe and a 2-line description that opens the About sheet. */
class ChannelHeaderAdapter(
    private val state: () -> ChannelUiState,
    private val onAbout: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_channel_header, parent, false)
    ) {}
    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        h.itemView.bindChannelHeader(state(), onAbout)
    }
}

fun View.bindChannelHeader(st: ChannelUiState, onAbout: () -> Unit) {
        val v = this
        val c = st.channel
        v.findViewById<ImageView>(R.id.banner).apply {
            if (st.banner != null) { visibility = View.VISIBLE; load(st.banner) } else visibility = View.GONE
        }
        v.findViewById<ImageView>(R.id.avatar).loadAvatar(c?.avatar)
        v.findViewById<TextView>(R.id.name).text = c?.name
        val handle = Fmt.channelLabel(c?.name.orEmpty(), c?.url).takeIf { it.startsWith("@") }
        v.findViewById<TextView>(R.id.handle).apply {
            text = handle.orEmpty(); visibility = if (handle == null) View.GONE else View.VISIBLE
        }
        v.findViewById<TextView>(R.id.subs).text = Fmt.subscribers(c?.subscribers ?: -1)
        v.findViewById<TextView>(R.id.btnSubscribe).bindSubscribe({ c }, filled = true)
        val desc = v.findViewById<TextView>(R.id.description)
        val more = v.findViewById<TextView>(R.id.btnAbout)
        val about = st.about
        if (about == null || (about.description.isBlank() && about.stats().isEmpty() && about.links.isEmpty())) {
            desc.visibility = View.GONE
            more.visibility = View.GONE
        } else {
            if (about.description.isBlank()) desc.visibility = View.GONE
            else {
                desc.visibility = View.VISIBLE
                desc.maxLines = 2
                desc.ellipsize = android.text.TextUtils.TruncateAt.END
                desc.text = about.description
            }
            more.visibility = View.VISIBLE
            more.setOnClickListener { onAbout() }
            desc.setOnClickListener { onAbout() }
        }
}

/** Horizontally scrolling channel tabs; only tabs present in [ChannelUiState.tabs]. */
class ChannelTabsAdapter(
    private val state: () -> ChannelUiState,
    private val onTab: (String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = if (state().tabs.isEmpty()) 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_channel_tabs, parent, false)
    ) {}
    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val st = state()
        val box = h.itemView.findViewById<LinearLayout>(R.id.tabs)
        box.removeAllViews()
        val inf = LayoutInflater.from(box.context)
        st.tabs.forEach { tab ->
            val tv = inf.inflate(R.layout.item_text_tab, box, false) as TextView
            tv.text = tab.label
            val on = tab.id == st.selectedTab
            tv.setTextColor(ContextCompat.getColor(box.context, if (on) R.color.text1 else R.color.text2))
            tv.setBackgroundResource(if (on) R.drawable.bg_tab_on else 0)
            tv.setOnClickListener { onTab(tab.id) }
            box.addView(tv)
        }
    }
}

/** Large featured video at the top of the channel Home tab. */
class FeaturedAdapter(private val onClick: (VideoItem) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var item: VideoItem? = null
        set(v) {
            val was = field != null
            field = v
            when {
                v == null && was -> notifyItemRemoved(0)
                v != null && !was -> notifyItemInserted(0)
                v != null && was -> notifyItemChanged(0)
            }
        }
    var progress: Map<String, Int> = emptyMap()
    fun applyProgress(map: Map<String, Int>) {
        progress = map
        if (item != null) notifyItemChanged(0)
    }
    override fun getItemCount() = if (item == null) 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_featured, parent, false)
    ) {}
    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val video = item ?: return
        val v = h.itemView
        v.findViewById<ImageView>(R.id.thumb).load(video.thumb) { crossfade(true) }
        val duration = v.findViewById<TextView>(R.id.duration)
        when {
            video.live -> { duration.text = "สด"; duration.setBackgroundResource(R.drawable.bg_live); duration.visibility = View.VISIBLE }
            video.durationSec > 0 -> { duration.text = Fmt.duration(video.durationSec); duration.setBackgroundResource(R.drawable.bg_duration); duration.visibility = View.VISIBLE }
            else -> duration.visibility = View.GONE
        }
        v.findViewById<TextView>(R.id.title).text = video.title
        v.findViewById<TextView>(R.id.meta).text = listOf(Fmt.views(video.views, video.live), Fmt.ageMs(video.uploadedMs, video.age).takeUnless { _ -> video.live }.orEmpty())
            .filter { s -> s.isNotBlank() }.joinToString(" · ")
        val p = progress[video.url] ?: 0
        val watched = v.findViewById<ProgressBar>(R.id.watched)
        watched.visibility = if (p > 0 && !video.live) View.VISIBLE else View.GONE
        watched.progress = p
        v.setOnClickListener { onClick(video) }
        v.findViewById<View>(R.id.more).setOnClickListener { view -> view.showVideoMenu(video) }
    }
}

/** 3-column Shorts grid. */
class ShortsAdapter(private val onClick: (VideoItem) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val items = ArrayList<VideoItem>()
    var loadingMore = false
        set(v) {
            if (field == v) return
            field = v
            if (v) notifyItemInserted(items.size) else notifyItemRemoved(items.size)
        }
    fun set(list: List<VideoItem>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    override fun getItemCount() = items.size + if (loadingMore) 1 else 0
    override fun getItemViewType(position: Int) = if (position >= items.size) 1 else 0
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        if (viewType == 1) return object : RecyclerView.ViewHolder(inf.inflate(R.layout.item_loading, parent, false)) {}
        return object : RecyclerView.ViewHolder(inf.inflate(R.layout.item_short, parent, false)) {}
    }
    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        if (position >= items.size) return
        val video = items[position]
        val v = h.itemView
        v.findViewById<ImageView>(R.id.thumb).load(video.thumb) { crossfade(true) }
        v.findViewById<TextView>(R.id.title).text = video.title
        v.findViewById<TextView>(R.id.meta).text = Fmt.views(video.views, video.live)
        v.setOnClickListener { onClick(video) }
    }
}

fun Context.channelAboutSheet(name: String, about: ChannelAbout) = sheet { box, _ ->
    box.sheetTitle("เกี่ยวกับ")
    val pad = (20 * resources.displayMetrics.density).toInt()
    if (name.isNotBlank()) {
        box.addView(TextView(this).apply {
            text = name; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text1)); setPadding(pad, 0, pad, pad / 3)
        })
    }
    about.stats().forEach { (label, value) ->
        box.addView(TextView(this).apply {
            text = "$label  $value"; textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text2)); setPadding(pad, 0, pad, pad / 4)
        })
    }
    if (about.description.isNotBlank()) {
        box.addView(TextView(this).apply {
            text = about.description; textSize = 14f; setLineSpacing(2 * resources.displayMetrics.density, 1f)
            setTextColor(ContextCompat.getColor(context, R.color.text1)); setPadding(pad, pad / 2, pad, pad / 2)
            setTextIsSelectable(true)
        })
    }
    about.links.forEach { url ->
        box.addView(TextView(this).apply {
            text = url; textSize = 14f; setTextColor(ContextCompat.getColor(context, R.color.link))
            setPadding(pad, pad / 4, pad, pad / 4)
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { android.util.Log.w("Channel", "open link failed", it) }
            }
        })
    }
}
