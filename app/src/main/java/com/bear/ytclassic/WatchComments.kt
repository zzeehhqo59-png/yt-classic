package com.bear.ytclassic

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfo

/** First page of comments for the watch page: feeds the comments card ([show]) and opens the full sheet. */
class WatchComments(private val a: AppCompatActivity, private val show: (title: String?, preview: String?, avatar: String?) -> Unit) {
    private var comments: CommentsInfo? = null
    private var commentsFor: String? = null

    /** Forget the loaded video so the next [load] fetches again. */
    fun reset() { commentsFor = null }

    fun load(url: String) {
        if (commentsFor == url) return
        commentsFor = url; comments = null
        show(null, null, null)
        a.lifecycleScope.launch {
            val ci = runCatching { withContext(Dispatchers.IO) { CommentsInfo.getInfo(ServiceList.YouTube, url) } }
                .onFailure { android.util.Log.w("WatchComments", "load failed: $url", it) }.getOrNull()
            if (commentsFor != url) return@launch
            comments = ci
            when {
                ci == null -> show("ความคิดเห็น", "โหลดความคิดเห็นไม่ได้ แตะเพื่อลองอีกครั้ง", null)
                ci.isLiveChat -> show(null, null, null)
                ci.isCommentsDisabled -> show("ความคิดเห็น", "ปิดการแสดงความคิดเห็นอยู่", null)
                else -> {
                    val first = ci.relatedItems.firstOrNull()
                    val n = if (ci.commentsCount >= 0) " " + Fmt.shortNum(ci.commentsCount.toLong()) else ""
                    show("ความคิดเห็น$n", first?.let { commentPlain(it) } ?: "ยังไม่มีความคิดเห็น", first?.uploaderAvatars?.pick(48))
                }
            }
        }
    }

    /** Open the comments sheet below [topPx]; if loading failed, retry for [url] instead. */
    fun open(url: String?, topPx: Int) {
        val ci = comments
        if (ci == null) { commentsFor = null; url?.let { load(it) }; return }
        showCommentsSheet(a, ci, topPx)
    }
}
