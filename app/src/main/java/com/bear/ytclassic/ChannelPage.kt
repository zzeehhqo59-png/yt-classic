package com.bear.ytclassic

import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs

/** A channel tab that this app can show, using NewPipeExtractor tab ids (plus a synthesized Home). */
data class ChannelTabSpec(val id: String, val label: String)

/**
 * Whether a tap on a video row's avatar or channel name should open a channel
 * (never the video itself). Null means do not navigate.
 */
object VideoClicks {
    fun channelUrl(item: VideoItem): String? {
        val url = item.channelUrl?.trim().orEmpty()
        if (url.isEmpty() || url == item.url) return null
        return url
    }
}

data class ChannelAbout(
    val description: String = "",
    val subscriberCount: Long = -1,
    val videoCount: Long = -1,
    val viewCount: Long = -1,
    val joinedMs: Long = 0,
    val links: List<String> = emptyList(),
) {
    enum class Info { URL, SUBSCRIBERS, VIDEOS, VIEWS, JOINED }

    /**
     * ข้อมูลเพิ่มเติม rows. NewPipeExtractor 0.27.1 has no channel About tab: it gives the url, description,
     * subscriber count and tags only, so country / join date / total views / video count stay hidden unless set.
     */
    fun infoRows(channelUrl: String): List<Pair<Info, String>> = buildList {
        if (channelUrl.isNotBlank()) add(Info.URL to channelUrl.removePrefix("https://").removePrefix("http://"))
        if (subscriberCount >= 0) add(Info.SUBSCRIBERS to "ผู้ติดตาม ${Fmt.shortNum(subscriberCount)} คน")
        if (videoCount >= 0) add(Info.VIDEOS to "วิดีโอ ${Fmt.shortNum(videoCount)} รายการ")
        if (viewCount >= 0) add(Info.VIEWS to "การดู ${Fmt.fullViews(viewCount, false)}")
        if (joinedMs > 0) add(Info.JOINED to "เข้าร่วมเมื่อ " + Fmt.thaiDate(
            java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(joinedMs), java.time.ZoneId.systemDefault())))
    }

    /** Only stats the extractor actually provided (missing values stay hidden). */
    fun stats(): List<Pair<String, String>> = buildList {
        if (subscriberCount >= 0) add("ผู้ติดตาม" to "${Fmt.shortNum(subscriberCount)} คน")
        if (videoCount >= 0) add("วิดีโอ" to Fmt.shortNum(videoCount))
        if (viewCount >= 0) add("ยอดชม" to Fmt.fullViews(viewCount, false))
        if (joinedMs > 0) add(
            "วันที่สมัคร" to Fmt.thaiDate(
                java.time.OffsetDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(joinedMs),
                    java.time.ZoneId.systemDefault(),
                )
            )
        )
    }
}

/** All / Videos / Shorts chips on a local playlist page. */
object PlaylistFilter {
    const val ALL = 0
    const val VIDEOS = 1
    const val SHORTS = 2
    val chips = listOf(ALL to "ทั้งหมด", VIDEOS to "วิดีโอ", SHORTS to "Shorts")

    fun apply(items: List<VideoItem>, filter: Int): List<VideoItem> = when (filter) {
        VIDEOS -> items.filter { it.kind != 0 || !it.isShortClip() }
        SHORTS -> items.filter { it.kind == 0 && it.isShortClip() }
        else -> items
    }
}

fun VideoItem.isShortClip(): Boolean =
    shortForm || url.contains("/shorts/", ignoreCase = true)

object ChannelPage {
    const val HOME = "home"

    private val tabOrder = listOf(
        ChannelTabs.VIDEOS to "วิดีโอ",
        ChannelTabs.SHORTS to "Shorts",
        ChannelTabs.LIVESTREAMS to "ไลฟ์สด",
        ChannelTabs.PODCASTS to "พอดแคสต์",
        ChannelTabs.PLAYLISTS to "เพลย์ลิสต์",
    )

    private val sortLabels = mapOf(
        "newest" to "ล่าสุด",
        "latest" to "ล่าสุด",
        "popular" to "ยอดนิยม",
        "views" to "ยอดนิยม",
        "oldest" to "เก่าที่สุด",
    )

    private val urlRe = Regex("""https?://[^\s<>"')\]]+""")

    /** Home is synthesized when a video-like tab exists; Posts/community are never faked. */
    fun visibleTabs(extractorTabIds: List<String>): List<ChannelTabSpec> {
        val have = extractorTabIds.toSet()
        val out = ArrayList<ChannelTabSpec>()
        val hasVideoLike = ChannelTabs.VIDEOS in have || ChannelTabs.SHORTS in have || ChannelTabs.LIVESTREAMS in have
        if (hasVideoLike) out += ChannelTabSpec(HOME, "หน้าแรก")
        for ((id, label) in tabOrder) if (id in have) out += ChannelTabSpec(id, label)
        return out
    }

    /** Only sort filters the extractor advertises. Empty means the UI must hide the control. */
    fun sortOptions(available: List<String>): List<Pair<String, String>> =
        available.map { it.trim() }.filter { it.isNotEmpty() }.map { id -> id to (sortLabels[id.lowercase()] ?: id) }

    fun keepLink(url: String): Boolean {
        val raw = url.trim()
        if (raw.isEmpty()) return false
        val low = raw.lowercase()
        if (!low.startsWith("http://") && !low.startsWith("https://")) return false
        val host = try { java.net.URI(low).host.orEmpty().trimStart('.') } catch (_: Throwable) { "" }
        if (host == "facebook.com" || host.endsWith(".facebook.com") ||
            host == "fb.com" || host.endsWith(".fb.com") ||
            host == "fb.me" || host.endsWith(".fb.me")
        ) return false
        if ("youtube.com" in low && ("/join" in low || "/channelmembership" in low)) return false
        return true
    }

    /** Links have no titles in the extractor data, so the title is the site name (host without www.). */
    fun linkTitle(url: String): String =
        runCatching { java.net.URI(url.trim()).host.orEmpty() }.getOrDefault("").removePrefix("www.").ifBlank { url }

    fun linksFrom(description: String, extra: List<String> = emptyList()): List<String> {
        val found = urlRe.findAll(description).map { it.value.trimEnd('.', ',', ';', ')') }.toList()
        return (found + extra).map { it.trim() }.filter { keepLink(it) }.distinct()
    }
}
