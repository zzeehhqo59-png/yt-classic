package com.bear.ytclassic

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * เกี่ยวกับ page of a channel: คำอธิบาย / ลิงก์ / ข้อมูลเพิ่มเติม. Only data NewPipeExtractor 0.27.1 gives
 * (see [ChannelAbout.infoRows]); missing sections are hidden. No copy / share actions here by design.
 */
class ChannelAboutActivity : YtActivity() {
    private lateinit var list: LinearLayout
    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(lightBars = true)
        setContentView(R.layout.activity_channel_about)
        attachPlayerUi()
        findViewById<View>(R.id.root).padForSystemBars()
        findViewById<View>(R.id.btnBack).setOnClickListener { if (!WatchLayer.consumeBack(this)) finish() }
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        findViewById<TextView>(R.id.title).text = name.ifBlank { "เกี่ยวกับ" }
        list = findViewById(R.id.list)
        build(aboutFrom(intent), intent.getStringExtra(EXTRA_URL).orEmpty())
    }

    private fun build(about: ChannelAbout, url: String) {
        if (about.description.isNotBlank()) {
            section("คำอธิบาย")
            list.addView(TextView(this).apply {
                text = android.text.SpannableString(about.description.trim()).also { Linkify.addLinks(it, Linkify.WEB_URLS) }
                textSize = 14f; setLineSpacing(px(2).toFloat(), 1f); setTextColor(color(R.color.text1))
                setLinkTextColor(color(R.color.link)); movementMethod = LinkMovementMethod.getInstance()
                setPadding(px(16), px(4), px(16), px(8))
            })
        }
        if (about.links.isNotEmpty()) {
            section("ลิงก์")
            about.links.forEach { link -> row(R.drawable.ic_link, ChannelPage.linkTitle(link), link, linkColor = true) { open(link) } }
        }
        val info = about.infoRows(url)
        if (info.isNotEmpty()) {
            section("ข้อมูลเพิ่มเติม")
            info.forEach { (kind, text) ->
                val icon = when (kind) {
                    ChannelAbout.Info.URL -> R.drawable.ic_globe
                    ChannelAbout.Info.SUBSCRIBERS -> R.drawable.ic_person
                    ChannelAbout.Info.VIDEOS -> R.drawable.ic_playlist
                    ChannelAbout.Info.VIEWS -> R.drawable.ic_trending
                    ChannelAbout.Info.JOINED -> R.drawable.ic_info
                }
                if (kind == ChannelAbout.Info.URL) row(icon, text, null) { open(url) } else row(icon, text, null, click = null)
            }
        }
    }

    private fun section(title: String) {
        if (list.childCount > 0) list.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply { topMargin = px(8) }
            setBackgroundColor(color(R.color.divider))
        })
        list.addView(TextView(this).apply {
            text = title; textSize = 18f; setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(color(R.color.text1)); setPadding(px(16), px(16), px(16), px(6))
        })
    }

    private fun row(icon: Int, title: String, sub: String?, linkColor: Boolean = false, click: (() -> Unit)?) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = px(52)
            setPadding(px(16), px(6), px(16), px(6))
            if (click != null) {
                val tv = TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); setBackgroundResource(tv.resourceId)
                setOnClickListener { click() }
            }
        }
        r.addView(ImageView(this).apply { setImageResource(icon); setColorFilter(color(R.color.text2)) }, LinearLayout.LayoutParams(px(24), px(24)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), 0, 0, 0) }
        col.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(color(R.color.text1)); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (!sub.isNullOrBlank()) col.addView(TextView(this).apply {
            text = sub; textSize = 13f; setTextColor(color(if (linkColor) R.color.link else R.color.text2)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        list.addView(r)
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { android.util.Log.w("ChannelAbout", "open link failed", it) }
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_URL = "url"
        private const val EXTRA_DESC = "desc"
        private const val EXTRA_SUBS = "subs"
        private const val EXTRA_VIDEOS = "videos"
        private const val EXTRA_VIEWS = "views"
        private const val EXTRA_JOINED = "joined"
        private const val EXTRA_LINKS = "links"

        fun intent(c: Context, name: String, url: String, about: ChannelAbout): Intent =
            Intent(c, ChannelAboutActivity::class.java)
                .putExtra(EXTRA_NAME, name).putExtra(EXTRA_URL, url).putExtra(EXTRA_DESC, about.description)
                .putExtra(EXTRA_SUBS, about.subscriberCount).putExtra(EXTRA_VIDEOS, about.videoCount)
                .putExtra(EXTRA_VIEWS, about.viewCount).putExtra(EXTRA_JOINED, about.joinedMs)
                .putStringArrayListExtra(EXTRA_LINKS, ArrayList(about.links))

        private fun aboutFrom(i: Intent) = ChannelAbout(
            description = i.getStringExtra(EXTRA_DESC).orEmpty(),
            subscriberCount = i.getLongExtra(EXTRA_SUBS, -1),
            videoCount = i.getLongExtra(EXTRA_VIDEOS, -1),
            viewCount = i.getLongExtra(EXTRA_VIEWS, -1),
            joinedMs = i.getLongExtra(EXTRA_JOINED, 0),
            links = i.getStringArrayListExtra(EXTRA_LINKS).orEmpty(),
        )
    }
}
