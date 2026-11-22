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
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
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
    /** 0 = video, 1 = channel, 2 = playlist */
    val kind: Int = 0,
    val count: Long = -1,
    val paid: Boolean = false,
    /** Private / deleted entries (e.g. in playlists). */
    val unavailable: Boolean = false,
    /** With [unavailable]: the video is private (otherwise deleted / removed). */
    val privateVideo: Boolean = false,
    /** Short-form clip, from the extractor or a /shorts/ URL. */
    val shortForm: Boolean = false,
) : Serializable

/** Thai badge for a list item that can't be watched here (null = playable as far as the list tells). */
fun VideoItem.unplayableBadge(): String? = when {
    kind != 0 -> null
    members -> "เฉพาะสมาชิก"
    paid -> "ต้องซื้อ"
    upcoming -> "รอเปิดตัว"
    unavailable -> if (privateVideo) "วิดีโอส่วนตัว" else "วิดีโอนี้ไม่พร้อมใช้งาน"
    else -> null
}


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
        paid = contentAvailability == ContentAvailability.PAID,
        unavailable = Availability.kindFromTitle(name) != Availability.OK,
        privateVideo = Availability.kindFromTitle(name) == Availability.PRIVATE,
        scheduledEpochMs = if (upcoming) uploadDate?.instant?.toEpochMilli() ?: 0 else 0,
        uploadedMs = uploadDate?.instant?.toEpochMilli() ?: 0,
        shortForm = isShort(),
    )
}

fun List<InfoItem>.videosOnly(): List<VideoItem> =
    filterIsInstance<StreamInfoItem>().filterNot { it.isShort() }.map { it.toVideo() }

/** Something that returns videos page by page. */
interface Pager {
    val hasMore: Boolean
    suspend fun next(): List<VideoItem>
}

fun List<InfoItem>.toItems(): List<VideoItem> = mapNotNull { i ->
    when (i) {
        is StreamInfoItem -> if (i.isShort()) null else i.toVideo()
        is ChannelInfoItem -> VideoItem(url = i.url, title = i.name, channel = i.name, channelUrl = i.url, thumb = i.thumbnails.pick(176),
            avatar = i.thumbnails.pick(68), durationSec = 0, views = i.subscriberCount, age = "", live = false, upcoming = false, members = false,
            scheduledEpochMs = 0, kind = 1, count = i.streamCount)
        is PlaylistInfoItem -> VideoItem(url = i.url, title = i.name, channel = i.uploaderName.orEmpty(), channelUrl = i.uploaderUrl, thumb = i.thumbnails.pick(360),
            avatar = null, durationSec = 0, views = -1, age = "", live = false, upcoming = false, members = false, scheduledEpochMs = 0, kind = 2, count = i.streamCount)
        else -> null
    }
}

class ListPager(val extractor: ListExtractor<out InfoItem>, private val map: (List<InfoItem>) -> List<VideoItem> = { it.videosOnly() }) : Pager {
    private var page: Page? = null
    private var started = false
    override val hasMore get() = !started || Page.isValid(page)
    override suspend fun next(): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = if (!started) {
            extractor.fetchPage(); started = true; extractor.initialPage
        } else extractor.getPage(page)
        page = p.nextPage
        @Suppress("UNCHECKED_CAST")
        map(p.items as List<InfoItem>)
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

fun List<InfoItem>.tabItems(includeShorts: Boolean, onlyShorts: Boolean = false): List<VideoItem> = mapNotNull { i ->
    when (i) {
        is StreamInfoItem -> {
            val v = i.toVideo()
            when {
                onlyShorts && !v.isShortClip() -> null
                !includeShorts && v.isShortClip() -> null
                else -> v
            }
        }
        is ChannelInfoItem, is PlaylistInfoItem -> i.let { listOf(it).toItems().firstOrNull() }
        else -> null
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

    fun searchPager(query: String, filter: String = "videos"): Pager =
        ListPager(yt.getSearchExtractor(query, listOf(filter), "")) { it.toItems() }

    /** Content filters YouTube's search supports, in display order, with Thai labels (unsupported ones are never shown). */
    fun searchFilters(): List<Pair<String, String>> {
        val avail = runCatching { yt.searchQHFactory.availableContentFilter.toList() }.getOrDefault(emptyList())
        return listOf("all" to "ทั้งหมด", "videos" to "วิดีโอ", "channels" to "ช่อง", "playlists" to "เพลย์ลิสต์").filter { it.first in avail }
    }

    fun playlistPager(url: String): ListPager = ListPager(yt.getPlaylistExtractor(url))

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

    fun channelTab(handler: org.schabi.newpipe.extractor.linkhandler.ListLinkHandler): ListPager {
        val tab = handler.contentFilters.firstOrNull().orEmpty()
        val extractor = yt.getChannelTabExtractor(handler)
        return when (tab) {
            ChannelTabs.SHORTS -> ListPager(extractor) { it.tabItems(includeShorts = true, onlyShorts = true) }
            ChannelTabs.PLAYLISTS, ChannelTabs.PODCASTS, ChannelTabs.ALBUMS, ChannelTabs.COURSES ->
                ListPager(extractor) { it.toItems() }
            else -> ListPager(extractor) { it.tabItems(includeShorts = tab != ChannelTabs.VIDEOS && tab != ChannelTabs.LIVESTREAMS) }
        }
    }

    fun channelTab(url: String, tabId: String): ListPager {
        val id = yt.channelLHFactory.fromUrl(url).id
        val extractor = yt.getChannelTabExtractorFromId(id, tabId)
        return when (tabId) {
            ChannelTabs.SHORTS -> ListPager(extractor) { it.tabItems(includeShorts = true, onlyShorts = true) }
            ChannelTabs.PLAYLISTS, ChannelTabs.PODCASTS, ChannelTabs.ALBUMS, ChannelTabs.COURSES ->
                ListPager(extractor) { it.toItems() }
            else -> ListPager(extractor) { it.tabItems(includeShorts = false) }
        }
    }

    suspend fun stream(url: String): StreamInfo = withContext(Dispatchers.IO) {
        StreamInfo.getInfo(yt, url)
    }
}
