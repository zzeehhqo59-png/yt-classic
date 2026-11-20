package com.bear.ytclassic

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.StyledPlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.time.OffsetDateTime

class WatchActivity : AppCompatActivity() {
    companion object { const val EXTRA_ITEM = "item" }

    private data class Quality(val label: String, val height: Int, val build: () -> MediaSource)

    private lateinit var item: VideoItem
    private lateinit var player: ExoPlayer
    private lateinit var playerView: StyledPlayerView
    private lateinit var playerBox: FrameLayout
    private lateinit var playerError: TextView
    private lateinit var playerLoading: View
    private lateinit var list: RecyclerView
    private val header = HeaderAdapter()
    private val related = VideoAdapter(false) { openVideo(it) }
    private val dsFactory by lazy { YtDataSource.Factory(DownloaderImpl.instance.client) }
    private var qualities: List<Quality> = emptyList()
    private var current = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch)
        @Suppress("DEPRECATION")
        item = intent.getSerializableExtra(EXTRA_ITEM) as VideoItem
        playerView = findViewById(R.id.playerView)
        playerBox = findViewById(R.id.playerBox)
        playerError = findViewById(R.id.playerError)
        playerLoading = findViewById(R.id.playerLoading)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = ConcatAdapter(header, related)
        header.bindItem(item)

        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                showPlayerError("เล่นวิดีโอไม่ได้ ลองเลือกคุณภาพอื่น\n(${error.errorCodeName})")
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) playerLoading.visibility = View.GONE
            }
        })
        playerView.setFullscreenButtonClickListener { full ->
            requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else finish()
            }
        })
        applyLayout(resources.configuration)
        load()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout(newConfig)
    }

    private fun applyLayout(c: Configuration) {
        val land = c.orientation == Configuration.ORIENTATION_LANDSCAPE
        val lp = playerBox.layoutParams
        lp.height = if (land) ViewGroup.LayoutParams.MATCH_PARENT else resources.displayMetrics.widthPixels * 9 / 16
        playerBox.layoutParams = lp
        list.visibility = if (land) View.GONE else View.VISIBLE
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (land) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else ctl.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun showPlayerError(text: String) {
        playerLoading.visibility = View.GONE
        playerError.text = text
        playerError.visibility = View.VISIBLE
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val info = Repo.stream(item.url)
                header.bindInfo(info)
                related.set(info.relatedItems.videosOnly())
                qualities = buildQualities(info)
                if (qualities.isEmpty()) {
                    val future = info.uploadDate?.offsetDateTime()?.isAfter(OffsetDateTime.now()) == true
                    val it2 = if (future) item.copy(upcoming = true,
                        scheduledEpochMs = info.uploadDate!!.instant.toEpochMilli()) else item
                    showPlayerError(Errors.video(ContentNotAvailableException("no streams"), it2))
                    return@launch
                }
                val def = qualities.indexOfLast { it.height in 1..720 }.takeIf { it >= 0 } ?: 0
                play(def, 0)
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                showPlayerError(Errors.video(e, item))
            }
        }
    }

    private fun play(index: Int, positionMs: Long) {
        current = index
        playerError.visibility = View.GONE
        playerLoading.visibility = View.VISIBLE
        player.setMediaSource(qualities[index].build(), positionMs)
        player.prepare()
        player.playWhenReady = true
    }

    private fun bestAudio(audios: List<AudioStream>, forWebm: Boolean): AudioStream? {
        val pref = audios.filter { if (forWebm) it.format == MediaFormat.WEBMA || it.format == MediaFormat.WEBMA_OPUS else it.format == MediaFormat.M4A }
        return (pref.ifEmpty { audios }).maxByOrNull { if (it.averageBitrate > 0) it.averageBitrate else it.bitrate }
    }

    private fun buildQualities(info: StreamInfo): List<Quality> {
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        val hls = info.hlsUrl.orEmpty()
        val dash = info.dashMpdUrl.orEmpty()
        val out = ArrayList<Quality>()
        if (live || (info.videoStreams.isEmpty() && info.videoOnlyStreams.isEmpty())) {
            if (hls.isNotEmpty()) out.add(Quality("อัตโนมัติ", 0) { HlsMediaSource.Factory(dsFactory).createMediaSource(MediaItem.fromUri(hls)) })
            else if (dash.isNotEmpty()) out.add(Quality("อัตโนมัติ", 0) { DashMediaSource.Factory(dsFactory).createMediaSource(MediaItem.fromUri(dash)) })
            if (live) return out
        }
        val prog = ProgressiveMediaSource.Factory(dsFactory)
        fun ok(s: org.schabi.newpipe.extractor.stream.Stream) = s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        val allAudio = info.audioStreams.filter { ok(it) }
        val audios = allAudio.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { allAudio }
        val byHeight = LinkedHashMap<Int, Quality>()
        val videoOnly = info.videoOnlyStreams.filter { ok(it) && it.height > 0 }
        if (audios.isNotEmpty()) {
            videoOnly.groupBy { it.height }.forEach { (h, list) ->
                val v: VideoStream = list.firstOrNull { it.format == MediaFormat.MPEG_4 && (it.codec ?: "").startsWith("avc") }
                    ?: list.firstOrNull { it.format == MediaFormat.MPEG_4 } ?: list.first()
                val a = bestAudio(audios, v.format == MediaFormat.WEBM) ?: return@forEach
                val label = "${h}p" + if (v.fps > 30) "${v.fps}" else ""
                byHeight[h] = Quality(label, h) {
                    MergingMediaSource(true,
                        prog.createMediaSource(MediaItem.fromUri(v.content)),
                        prog.createMediaSource(MediaItem.fromUri(a.content)))
                }
            }
        }
        info.videoStreams.filter { ok(it) }.forEach { v ->
            val h = v.height.takeIf { it > 0 } ?: v.resolution.filter { c -> c.isDigit() }.take(4).toIntOrNull() ?: 0
            if (!byHeight.containsKey(h)) byHeight[h] = Quality(if (h > 0) "${h}p" else v.resolution, h) {
                prog.createMediaSource(MediaItem.fromUri(v.content))
            }
        }
        out.addAll(byHeight.values.sortedBy { it.height })
        if (out.isEmpty() && hls.isNotEmpty()) out.add(Quality("อัตโนมัติ", 0) { HlsMediaSource.Factory(dsFactory).createMediaSource(MediaItem.fromUri(hls)) })
        return out
    }

    private fun pickQuality() {
        if (qualities.isEmpty()) { Toast.makeText(this, "ยังไม่มีตัวเลือกคุณภาพ", Toast.LENGTH_SHORT).show(); return }
        val labels = qualities.map { it.label }.reversed().toTypedArray()
        val checked = qualities.size - 1 - current
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_quality)
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                val idx = qualities.size - 1 - which
                if (idx != current) play(idx, player.currentPosition)
            }.show()
    }

    override fun onStop() { super.onStop(); player.pause() }
    override fun onDestroy() { super.onDestroy(); player.release() }

    /** Title, counts, channel row and description shown above the related videos. */
    private inner class HeaderAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var view: View? = null
        private var title = ""; private var views = ""; private var channel = ""; private var subs = ""
        private var avatar: String? = null; private var likes = ""; private var desc: CharSequence = ""
        private var expanded = false

        fun bindItem(v: VideoItem) {
            title = v.title; channel = v.channel; avatar = v.avatar
            views = listOf(Fmt.views(v.views, v.live), v.age).filter { it.isNotBlank() }.joinToString(" · ")
            notifyItemChanged(0)
        }

        fun bindInfo(i: StreamInfo) {
            val live = i.streamType == StreamType.LIVE_STREAM
            title = i.name
            val date = i.uploadDate?.offsetDateTime()?.let { Fmt.thaiDate(it) } ?: i.textualUploadDate.orEmpty()
            views = listOf(Fmt.fullViews(i.viewCount, live), if (live) "" else date).filter { it.isNotBlank() }.joinToString(" · ")
            channel = i.uploaderName.orEmpty()
            subs = Fmt.subscribers(i.uploaderSubscriberCount)
            avatar = i.uploaderAvatars.pick(88) ?: avatar
            likes = if (i.likeCount >= 0) Fmt.shortNum(i.likeCount) else ""
            val d = i.description ?: Description.EMPTY_DESCRIPTION
            desc = if (d.type() == Description.Type.HTML) HtmlCompat.fromHtml(d.content().orEmpty(), HtmlCompat.FROM_HTML_MODE_LEGACY).trim()
            else d.content().orEmpty()
            notifyItemChanged(0)
        }

        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_watch_header, parent, false)
            view = v
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            v.findViewById<TextView>(R.id.wTitle).apply { text = title; maxLines = if (expanded) 10 else 2 }
            v.findViewById<TextView>(R.id.wViews).text = views
            v.findViewById<TextView>(R.id.wChannel).text = channel
            v.findViewById<TextView>(R.id.wSubs).apply { text = subs; visibility = if (subs.isEmpty()) View.GONE else View.VISIBLE }
            v.findViewById<ImageView>(R.id.wAvatar).loadAvatar(avatar)
            v.findViewById<TextView>(R.id.btnLike).text = likes.ifEmpty { getString(R.string.like) }
            val descView = v.findViewById<TextView>(R.id.wDesc)
            val toggle = v.findViewById<TextView>(R.id.wDescToggle)
            descView.text = desc
            descView.movementMethod = LinkMovementMethod.getInstance()
            descView.maxLines = if (expanded) Int.MAX_VALUE else 3
            descView.visibility = if (desc.isEmpty()) View.GONE else View.VISIBLE
            toggle.visibility = descView.visibility
            toggle.setText(if (expanded) R.string.show_less else R.string.show_more)
            val flip = View.OnClickListener { expanded = !expanded; notifyItemChanged(0) }
            toggle.setOnClickListener(flip)
            v.findViewById<View>(R.id.titleRow).setOnClickListener(flip)
            v.findViewById<View>(R.id.btnQuality).setOnClickListener { pickQuality() }
            v.findViewById<View>(R.id.btnShare).setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, item.url), getString(R.string.share)))
            }
            val soon = View.OnClickListener { Toast.makeText(this@WatchActivity, R.string.coming_soon, Toast.LENGTH_SHORT).show() }
            v.findViewById<View>(R.id.btnSubscribe).setOnClickListener(soon)
            v.findViewById<View>(R.id.btnLike).setOnClickListener(soon)
            v.findViewById<View>(R.id.btnDislike).setOnClickListener(soon)
        }
    }
}
