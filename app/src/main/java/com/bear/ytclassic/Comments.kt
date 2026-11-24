package com.bear.ytclassic

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import java.lang.ref.WeakReference
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.stream.Description

fun commentText(c: CommentsInfoItem): CharSequence {
    val d = c.commentText ?: Description.EMPTY_DESCRIPTION
    return if (d.type() == Description.Type.HTML) HtmlCompat.fromHtml(d.content().orEmpty(), HtmlCompat.FROM_HTML_MODE_LEGACY).trim() else d.content().orEmpty()
}
fun commentPlain(c: CommentsInfoItem): String = commentText(c).toString().replace('\n', ' ')

/** Read-only comments in a bottom sheet under the player: infinite scroll, replies expand in place. */
private var commentsSheet: WeakReference<BottomSheetDialog>? = null

fun dismissCommentsSheet() { commentsSheet?.get()?.dismiss(); commentsSheet = null }

fun showCommentsSheet(c: Context, info: CommentsInfo, topPx: Int) {
    val dlg = BottomSheetDialog(c)
    commentsSheet = WeakReference(dlg)
    val v = LayoutInflater.from(c).inflate(R.layout.sheet_comments, null)
    val height = c.resources.displayMetrics.heightPixels - topPx
    v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    dlg.setContentView(v)
    dlg.window?.setDimAmount(0.25f)
    dlg.behavior.apply { peekHeight = height; state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true }
    val scope = MainScope()
    dlg.setOnDismissListener { scope.cancel(); if (commentsSheet?.get() === dlg) commentsSheet = null }
    v.findViewById<View>(R.id.sheetClose).setOnClickListener { dlg.dismiss() }
    if (info.commentsCount >= 0) v.findViewById<TextView>(R.id.sheetTitle).text = "ความคิดเห็น  " + Fmt.shortNum(info.commentsCount.toLong())
    val progress = v.findViewById<View>(R.id.commentProgress)
    val message = v.findViewById<TextView>(R.id.commentMessage)
    val list = v.findViewById<RecyclerView>(R.id.commentList)
    val adapter = CommentAdapter(scope, info.url)
    list.layoutManager = LinearLayoutManager(c)
    list.adapter = adapter
    progress.visibility = View.GONE
    if (info.isCommentsDisabled) {
        message.text = "ผู้อัปโหลดปิดการแสดงความคิดเห็นของวิดีโอนี้"; message.visibility = View.VISIBLE
    } else {
        adapter.addTop(info.relatedItems)
        if (info.relatedItems.isEmpty()) { message.text = "ยังไม่มีความคิดเห็น"; message.visibility = View.VISIBLE }
    }
    var next: Page? = info.nextPage
    var loading = false
    list.onNearEnd {
        val p = next
        if (loading || !Page.isValid(p)) return@onNearEnd
        loading = true
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { CommentsInfo.getMoreItems(ServiceList.YouTube, info, p) }
                next = page.nextPage
                adapter.addTop(page.items)
            } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
            loading = false
        }
    }
    dlg.show()
}

private class CommentAdapter(private val scope: kotlinx.coroutines.CoroutineScope, private val url: String) : RecyclerView.Adapter<CommentAdapter.VH>() {
    class Row(val c: CommentsInfoItem, val reply: Boolean) { var expanded = false; var loading = false; var shownReplies = 0 }
    private val rows = ArrayList<Row>()

    fun addTop(items: List<CommentsInfoItem>) { val s = rows.size; rows.addAll(items.map { Row(it, false) }); notifyItemRangeInserted(s, items.size) }

    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_comment, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = rows[position]; val c = r.c
        val d = h.itemView.resources.displayMetrics.density
        h.itemView.setPaddingRelative(((if (r.reply) 60 else 12) * d).toInt(), h.itemView.paddingTop, h.itemView.paddingEnd, h.itemView.paddingBottom)
        h.avatar.layoutParams.apply { val s = ((if (r.reply) 24 else 36) * d).toInt(); width = s; height = s }
        h.avatar.loadAvatar(c.uploaderAvatars.pick(64))
        val time = Fmt.age(c.uploadDate, c.textualUploadDate)
        h.author.text = listOf(c.uploaderName.orEmpty(), time, if (c.isEdited) "(แก้ไขแล้ว)" else "").filter { it.isNotBlank() }.joinToString(" · ")
        h.pinned.visibility = if (c.isPinned) View.VISIBLE else View.GONE
        h.text.text = commentText(c)
        h.text.movementMethod = LinkMovementMethod.getInstance()
        h.likes.text = when {
            c.likeCount > 0 -> Fmt.shortNum(c.likeCount.toLong())
            !c.textualLikeCount.isNullOrBlank() -> c.textualLikeCount
            else -> ""
        } + if (c.isHeartedByUploader) "   ❤ ผู้สร้างกดหัวใจ" else ""
        h.likes.visibility = if (h.likes.text.isNullOrBlank()) View.GONE else View.VISIBLE
        val n = c.replyCount
        if (!r.reply && n > 0 && c.replies != null) {
            h.replies.visibility = View.VISIBLE
            h.replies.text = when {
                r.loading -> "กำลังโหลด…"
                r.expanded -> "▲ ซ่อนการตอบกลับ"
                else -> "▼ ตอบกลับ ${Fmt.shortNum(n.toLong())} รายการ"
            }
            h.replies.setOnClickListener { toggle(h.bindingAdapterPosition) }
        } else h.replies.visibility = View.GONE
    }

    private fun toggle(pos: Int) {
        if (pos < 0) return
        val r = rows[pos]
        if (r.loading) return
        if (r.expanded) {
            repeat(r.shownReplies) { rows.removeAt(pos + 1) }
            notifyItemRangeRemoved(pos + 1, r.shownReplies)
            r.expanded = false; r.shownReplies = 0; notifyItemChanged(pos); return
        }
        r.loading = true; notifyItemChanged(pos)
        scope.launch {
            val out = ArrayList<CommentsInfoItem>()
            try {
                var p: Page? = r.c.replies
                var pages = 0
                while (Page.isValid(p) && pages < 10) {
                    val page = withContext(Dispatchers.IO) { CommentsInfo.getMoreItems(ServiceList.YouTube, url, p) }
                    out.addAll(page.items); p = page.nextPage; pages++
                }
            } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
            val at = rows.indexOf(r)
            r.loading = false
            if (at < 0) return@launch
            rows.addAll(at + 1, out.map { Row(it, true) })
            r.expanded = out.isNotEmpty(); r.shownReplies = out.size
            notifyItemChanged(at); notifyItemRangeInserted(at + 1, out.size)
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.cAvatar)
        val author: TextView = v.findViewById(R.id.cAuthor)
        val pinned: TextView = v.findViewById(R.id.cPinned)
        val text: TextView = v.findViewById(R.id.cText)
        val likes: TextView = v.findViewById(R.id.cLikes)
        val replies: TextView = v.findViewById(R.id.cReplies)
    }
}
