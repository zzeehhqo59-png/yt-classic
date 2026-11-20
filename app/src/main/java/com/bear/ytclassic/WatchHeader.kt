package com.bear.ytclassic

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
        /** The comments card changed (the landscape row mirrors it). */
        fun onComments(title: String?, preview: String?, avatar: String?)
    }

    private var title = ""; private var channel = ""; private var meta = ""; private var stats = ""
    private var avatar: String? = null; private var channelUrl: String? = null; private var subCount = -1L; private var likes = ""; private var desc: CharSequence = ""

    private var cTitle: String? = null; private var cPreview: String? = null; private var cAvatar: String? = null
    fun setComments(title: String?, preview: String?, avatar: String?) {
        cTitle = title; cPreview = preview; cAvatar = avatar; notifyItemChanged(0); cb.onComments(title, preview, avatar)
    }

    fun reset() { likes = ""; desc = ""; subCount = -1; stats = "" }

    fun bindItem(v: VideoItem) {
        title = v.title; channel = v.channel; avatar = v.avatar; channelUrl = v.channelUrl
        meta = listOf(v.channel, Fmt.views(v.views, v.live), Fmt.ageMs(v.uploadedMs, v.age).takeUnless { v.live }.orEmpty()).filter { it.isNotBlank() }.joinToString("  ")
        if (stats.isEmpty()) stats = meta
        notifyItemChanged(0)
    }

    fun bindInfo(i: StreamInfo) {
        val live = i.streamType == StreamType.LIVE_STREAM
        title = i.name
        channel = i.uploaderName.orEmpty()
        val age = if (live) "" else Fmt.age(i.uploadDate, i.textualUploadDate)
        meta = listOf(channel, Fmt.views(i.viewCount, live), age).filter { it.isNotBlank() }.joinToString("  ")
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
        notifyItemChanged(0)
    }

    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_watch_header, parent, false)
        return object : RecyclerView.ViewHolder(v) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        v.findViewById<TextView>(R.id.wTitle).text = title
        val more = "...เพิ่มเติม"
        val sp = android.text.SpannableStringBuilder(meta).append(if (meta.isEmpty()) "" else "  ")
        val st = sp.length
        sp.append(more)
        sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(a, R.color.text1)), st, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.findViewById<TextView>(R.id.wMeta).text = sp
        v.findViewById<View>(R.id.titleRow).setOnClickListener { a.descriptionSheet(title, stats.ifEmpty { meta }, desc) }
        v.findViewById<ImageView>(R.id.wAvatar).apply { loadAvatar(avatar); setOnClickListener { a.openChannel(channelUrl, channel, avatar) } }
        v.findViewById<TextView>(R.id.likeCount).apply { text = likes; visibility = if (likes.isEmpty()) View.GONE else View.VISIBLE }
        v.findViewById<View>(R.id.btnShare).setOnClickListener { cb.share() }
        v.findViewById<View>(R.id.btnMore).setOnClickListener { cb.moreSheet() }
        val cRow = v.findViewById<View>(R.id.commentsRow)
        cRow.visibility = if (cTitle == null) View.GONE else View.VISIBLE
        v.findViewById<TextView>(R.id.commentsTitle).text = cTitle
        v.findViewById<TextView>(R.id.previewText).text = cPreview
        v.findViewById<ImageView>(R.id.previewAvatar).apply { visibility = if (cAvatar == null) View.GONE else View.VISIBLE; loadAvatar(cAvatar) }
        cRow.setOnClickListener { cb.openComments() }
        val soon = View.OnClickListener { Toast.makeText(a, R.string.coming_soon, Toast.LENGTH_SHORT).show() }
        v.findViewById<TextView>(R.id.btnSubscribe).bindSubscribe({
            channelUrl?.let { u -> Channel(u, channel, avatar, subCount) }
        }, capsule = true)
        v.findViewById<View>(R.id.btnLike).setOnClickListener(soon)
        v.findViewById<View>(R.id.btnDislike).setOnClickListener(soon)
    }

}

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
        movementMethod = LinkMovementMethod.getInstance(); setTextIsSelectable(true)
        setLinkTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.link))
    })
}

