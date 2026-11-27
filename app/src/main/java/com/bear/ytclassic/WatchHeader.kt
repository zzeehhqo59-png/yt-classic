package com.bear.ytclassic

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

/** Title, meta line, channel/actions row and the comments card shown above the up-next videos. */
class WatchHeaderAdapter(private val a: AppCompatActivity, private val cb: Callbacks) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    interface Callbacks {
        fun openComments()
        fun moreSheet()
        fun share()
        fun save()
        /** Local rating of the current video: 1 ถูกใจ, -1 ไม่ชอบ, 0 none. */
        fun rating(): Int
        fun rate(value: Int)
        /** The comments card changed (the landscape row mirrors it). */
        fun onComments(title: String?, preview: String?, avatar: String?)
        /** Title of the chapter playing now, or null when the video has no chapters. */
        fun chapterTitle(): String? = null
        fun openChapters() {}
        /** A timestamp in the description was tapped. */
        fun seekTo(ms: Long) {}
    }

    private var title = ""; private var channel = ""; private var meta = ""; private var stats = ""
    private var avatar: String? = null; private var channelUrl: String? = null; private var subCount = -1L; private var likes = ""; private var desc: CharSequence = ""

    private var cTitle: String? = null; private var cPreview: String? = null; private var cAvatar: String? = null
    fun setComments(title: String?, preview: String?, avatar: String?) {
        cTitle = title; cPreview = preview; cAvatar = avatar; notifyItemChanged(0); cb.onComments(title, preview, avatar)
    }

    private var descSheet: android.app.Dialog? = null

    /** The current chapter changed: only the chapter row is rebound. */
    fun chapterChanged() = notifyItemChanged(0, CHAPTER)

    fun reset() { likes = ""; desc = ""; subCount = -1; stats = "" }

    fun bindItem(v: VideoItem) {
        title = v.title; channel = v.channel; avatar = v.avatar; channelUrl = v.channelUrl
        meta = listOf(Fmt.channelLabel(v.channel, v.channelUrl), Fmt.views(v.views, v.live), Fmt.ageMs(v.uploadedMs, v.age).takeUnless { v.live }.orEmpty()).filter { it.isNotBlank() }.joinToString("  ")
        if (stats.isEmpty()) stats = meta
        notifyItemChanged(0)
    }

    fun bindInfo(i: StreamInfo) {
        val live = i.streamType == StreamType.LIVE_STREAM
        title = i.name
        channel = i.uploaderName.orEmpty()
        val age = if (live) "" else Fmt.age(i.uploadDate, i.textualUploadDate)
        meta = listOf(Fmt.channelLabel(channel, i.uploaderUrl ?: channelUrl), Fmt.views(i.viewCount, live), age).filter { it.isNotBlank() }.joinToString("  ")
        val date = i.uploadDate?.offsetDateTime()?.let { Fmt.thaiDate(it) } ?: i.textualUploadDate.orEmpty()
        stats = listOf(channel, Fmt.subscribers(i.uploaderSubscriberCount), Fmt.fullViews(i.viewCount, live), if (live) "" else date,
            if (i.likeCount >= 0) "ถูกใจ ${Fmt.shortNum(i.likeCount)}" else "").filter { it.isNotBlank() }.joinToString(" · ")
        subCount = i.uploaderSubscriberCount
        channelUrl = i.uploaderUrl ?: channelUrl
        avatar = i.uploaderAvatars.pick(88) ?: avatar
        likes = if (i.likeCount >= 0) Fmt.shortNum(i.likeCount) else ""
        val d = i.description ?: Description.EMPTY_DESCRIPTION
        desc = if (d.type() == Description.Type.HTML) HtmlCompat.fromHtml(d.content().orEmpty(), HtmlCompat.FROM_HTML_MODE_LEGACY).trim()
        else android.text.SpannableString(d.content().orEmpty()).also { android.text.util.Linkify.addLinks(it, android.text.util.Linkify.WEB_URLS) }
        desc = linkTimestamps(desc, if (live) 0 else i.duration * 1000L)
        notifyItemChanged(0)
    }

    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_watch_header, parent, false)
        return object : RecyclerView.ViewHolder(v) {}
    }

    /** 12:30 / 1:02:03 in the description become links that seek the player. */
    private fun linkTimestamps(text: CharSequence, durationMs: Long): CharSequence {
        val stamps = Chapters.timestamps(text, durationMs)
        if (stamps.isEmpty()) return text
        val sp = android.text.SpannableString(text)
        stamps.filter { st -> sp.getSpans(st.start, st.end, android.text.style.URLSpan::class.java).isEmpty() }.forEach { st ->
            sp.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) { descSheet?.dismiss(); cb.seekTo(st.ms) }
            }, st.start, st.end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == CHAPTER }) bindChapter(holder.itemView) else onBindViewHolder(holder, position)
    }

    private fun bindChapter(v: View) {
        val t = cb.chapterTitle()
        v.findViewById<View>(R.id.chapterRow).apply {
            visibility = if (t == null) View.GONE else View.VISIBLE
            setOnClickListener { cb.openChapters() }
            contentDescription = t?.let { "บท: $it" }
        }
        v.findViewById<TextView>(R.id.chapterTitle).text = t
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        bindChapter(v)
        v.findViewById<TextView>(R.id.wTitle).text = title
        val more = "...เพิ่มเติม"
        val sp = android.text.SpannableStringBuilder(meta).append(if (meta.isEmpty()) "" else "  ")
        val st = sp.length
        sp.append(more)
        sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(a, R.color.text1)), st, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.findViewById<TextView>(R.id.wMeta).text = sp
        v.findViewById<View>(R.id.titleRow).setOnClickListener { descSheet = a.descriptionSheet(title, stats.ifEmpty { meta }, desc) }
        v.findViewById<ImageView>(R.id.wAvatar).loadAvatar(avatar)
        v.findViewById<TextView>(R.id.wChannelName).text = channel
        v.findViewById<View>(R.id.channelIdentity).setOnClickListener { a.openChannel(channelUrl, channel, avatar) }
        v.findViewById<TextView>(R.id.likeCount).apply { text = likes; visibility = if (likes.isEmpty()) View.GONE else View.VISIBLE }
        v.findViewById<View>(R.id.btnShare).setOnClickListener { cb.share() }
        v.findViewById<View>(R.id.btnSave).setOnClickListener { cb.save() }
        v.findViewById<View>(R.id.btnMore).setOnClickListener { cb.moreSheet() }
        val cRow = v.findViewById<View>(R.id.commentsRow)
        cRow.visibility = if (cTitle == null) View.GONE else View.VISIBLE
        v.findViewById<TextView>(R.id.commentsTitle).text = cTitle
        v.findViewById<TextView>(R.id.previewText).text = cPreview
        v.findViewById<ImageView>(R.id.previewAvatar).apply { visibility = if (cAvatar == null) View.GONE else View.VISIBLE; loadAvatar(cAvatar) }
        cRow.setOnClickListener { cb.openComments() }
        v.findViewById<TextView>(R.id.btnSubscribe).bindSubscribe({
            channelUrl?.let { u -> Channel(u, channel, avatar, subCount) }
        }, capsule = true)
        val r = cb.rating()
        v.findViewById<ImageView>(R.id.likeIcon).setColorFilter(rateColor(a, r == Likes.LIKE, R.color.text1))
        v.findViewById<ImageView>(R.id.btnDislike).setColorFilter(rateColor(a, r == Likes.DISLIKE, R.color.text1))
        v.findViewById<View>(R.id.btnLike).setOnClickListener { cb.rate(Likes.LIKE) }
        v.findViewById<View>(R.id.btnDislike).setOnClickListener { cb.rate(Likes.DISLIKE) }
    }

    private companion object { const val CHAPTER = "chapter" }

}

/** 👍 / 👎 tint: blue when chosen (like the old app), otherwise [normal]. */
fun rateColor(c: Context, on: Boolean, normal: Int): Int = androidx.core.content.ContextCompat.getColor(c, if (on) R.color.rated else normal)

/** "...เพิ่มเติม": full title, counts, date and description in a sheet. */
fun Context.descriptionSheet(title: String, stats: String, desc: CharSequence) = sheet { box, _ ->
    box.sheetTitle(title)
    val pad = (20 * resources.displayMetrics.density).toInt()
    box.addView(TextView(this@descriptionSheet).apply {
        text = stats; textSize = 13f; setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text2)); setPadding(pad, 0, pad, pad / 2)
    })
    box.addView(TextView(this@descriptionSheet).apply {
        text = desc.ifEmpty { "ไม่มีคำอธิบาย" }; textSize = 14f; setLineSpacing(2 * resources.displayMetrics.density, 1f)
        setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text1)); setPadding(pad, 0, pad, pad)
        setTextIsSelectable(true); movementMethod = LinkMovementMethod.getInstance()
        setLinkTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.link))
    })
}

