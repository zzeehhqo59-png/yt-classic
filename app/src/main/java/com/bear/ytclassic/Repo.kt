package com.bear.ytclassic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import java.io.Serializable

data class VideoItem(
    val url: String,
    val title: String,
    val channel: String,
    val channelUrl: String?,
    val thumb: String?,
    val avatar: String?,
    val durationSec: Long,
    val views: Long,
    val age: String,
    val live: Boolean,
    val upcoming: Boolean,
    val members: Boolean,
    val scheduledEpochMs: Long,
    val uploadedMs: Long = 0,
) : Serializable

fun List<Image>.pick(target: Int = 360): String? =
    filter { it.url.isNotBlank() }
        .minByOrNull { if (it.height <= 0) 10_000 else kotlin.math.abs(it.height - target) }?.url
        ?: firstOrNull()?.url

fun StreamInfoItem.isShort(): Boolean =
    isShortFormContent || url.contains("/shorts/")

fun StreamInfoItem.toVideo(): VideoItem {
    val live = streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM
    val upcoming = contentAvailability == ContentAvailability.UPCOMING
    return VideoItem(
        url = url, title = name, channel = uploaderName.orEmpty(), channelUrl = uploaderUrl,
        thumb = thumbnails.pick(360), avatar = uploaderAvatars.pick(68),
        durationSec = duration, views = viewCount,
        age = if (live) "" else Fmt.age(uploadDate, textualUploadDate),
        live = live, upcoming = upcoming,
        members = contentAvailability == ContentAvailability.MEMBERSHIP,
        scheduledEpochMs = if (upcoming) uploadDate?.instant?.toEpochMilli() ?: 0 else 0,
        uploadedMs = uploadDate?.instant?.toEpochMilli() ?: 0,
    )
}

fun List<InfoItem>.videosOnly(): List<VideoItem> =
    filterIsInstance<StreamInfoItem>().filterNot { it.isShort() }.map { it.toVideo() }

/** Something that returns videos page by page. */
interface Pager {
    val hasMore: Boolean
    suspend fun next(): List<VideoItem>
}

class ListPager(private val extractor: ListExtractor<out InfoItem>) : Pager {
    private var page: Page? = null
    private var started = false
    override val hasMore get() = !started || Page.isValid(page)
    override suspend fun next(): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = if (!started) {
            extractor.fetchPage(); started = true; extractor.initialPage
        } else extractor.getPage(page)
        page = p.nextPage
        p.items.videosOnly()
    }
}

/** Home: mixes the YouTube trending kiosks (gaming, music, trailers, podcasts...). */
private class HomePager(private val pagers: List<Pager>) : Pager {
    override val hasMore get() = pagers.any { it.hasMore }
    private val seen = HashSet<String>()
    override suspend fun next(): List<VideoItem> = coroutineScope {
        val results = pagers.filter { it.hasMore }.map { p -> async { runCatching { p.next() } } }.awaitAll()
        val lists = results.mapNotNull { it.getOrNull() }
        if (lists.isEmpty()) results.firstOrNull()?.exceptionOrNull()?.let { throw it }
        val out = ArrayList<VideoItem>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) if (i < l.size && seen.add(l[i].url)) out.add(l[i])
        out
    }
}

object Repo {
    private val yt get() = ServiceList.YouTube

    suspend fun homePager(): Pager = withContext(Dispatchers.IO) {
        val kiosks = yt.kioskList
        val ids = kiosks.availableKiosks.filterNot { it.contains("live", ignoreCase = true) }
        val pagers = ids.mapNotNull { id -> runCatching { ListPager(kiosks.getExtractorById(id, null)) }.getOrNull() }
        val home = HomePager(pagers)
        object : Pager {
            var fallback: Pager? = null
            override val hasMore get() = fallback?.hasMore ?: home.hasMore
            override suspend fun next(): List<VideoItem> {
                fallback?.let { return it.next() }
                val items = runCatching { home.next() }.getOrElse { emptyList() }
                if (items.isNotEmpty()) return items
                // Kiosks failed or empty: fall back to a popular search
                val f = searchPager("วิดีโอยอดนิยม")
                fallback = f
                return f.next()
            }
        }
    }

    fun searchPager(query: String): Pager =
        ListPager(yt.getSearchExtractor(query, listOf("videos"), ""))

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        yt.suggestionExtractor.suggestionList(query)
    }

    suspend fun channel(url: String): ChannelInfo = withContext(Dispatchers.IO) { ChannelInfo.getInfo(yt, url) }

    /** Videos tab (no Shorts tab) of a channel. */
    fun channelVideos(url: String): ListPager = withIdTab(url)

    private fun withIdTab(url: String): ListPager {
        val id = yt.channelLHFactory.fromUrl(url).id
        return ListPager(yt.getChannelTabExtractorFromId(id, ChannelTabs.VIDEOS))
    }

    suspend fun stream(url: String): StreamInfo = withContext(Dispatchers.IO) {
        StreamInfo.getInfo(yt, url)
    }
}
