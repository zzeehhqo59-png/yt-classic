package com.bear.ytclassic

import java.text.Collator
import java.util.Locale

/**
 * Pure logic for the คุณ (You) page: which filter chips exist, which collection rows each chip
 * shows, covers (the latest clip in each collection) and empty texts. Only data this app really
 * stores on the phone is used (history, local playlists, likes, downloads, subscriptions).
 */
object LibraryModel {
    /** Rows with a ⋮ (rename / delete): only playlists the user created. */
    fun hasMenu(r: Row): Boolean = r.editable && r.kind == Kind.PLAYLIST && r.playlistId != null

    /** ALL is the first chip; it is the sort dropdown, so its label is the current [Sort]. */
    enum class Filter(val label: String) {
        ALL("ทั้งหมด"), DOWNLOADED("ดาวน์โหลดแล้ว"), PLAYLISTS("เพลย์ลิสต์"), MUSIC("เพลง"),
    }

    enum class Sort(val label: String) {
        RECENT("ล่าสุด"), A_Z("ก-ฮ"), Z_A("ฮ-ก");
        companion object {
            fun fromKey(key: String?): Sort = entries.firstOrNull { it.name == key } ?: RECENT
        }
    }

    fun chipLabel(filter: Filter, sort: Sort): String = if (filter == Filter.ALL) "${sort.label} \u25BE" else filter.label

    /** Thai-aware title order (leading vowels sort under their consonant), case-insensitive for English. */
    fun titleComparator(): Comparator<String> {
        val c = Collator.getInstance(Locale.forLanguageTag("th-TH")).apply { strength = Collator.SECONDARY }
        return Comparator { a, b -> c.compare(a.trim(), b.trim()) }
    }

    enum class Kind { WATCH_LATER, LIKED, PLAYLIST, DOWNLOADS, CREATE }

    data class PlaylistInfo(val id: Long, val name: String, val count: Int, val cover: String?, val latest: Long)
    data class DownloadInfo(val id: Long, val cover: String?, val audioOnly: Boolean, val created: Long)

    data class Snapshot(
        val history: List<VideoItem> = emptyList(),
        val watchLater: PlaylistInfo? = null,
        val likedCount: Int = 0,
        val likedCover: String? = null,
        val likedLatest: Long = 0,
        val playlists: List<PlaylistInfo> = emptyList(),
        val downloads: List<DownloadInfo> = emptyList(),
        val subscriptions: Int = 0,
        val watchedCount: Int = 0,
    )

    data class Row(
        val kind: Kind,
        val title: String,
        val meta: String,
        val cover: String?,
        val playlistId: Long? = null,
        val latest: Long = 0,
        /** Has a ⋮ to rename/delete it (user playlists only). */
        val editable: Boolean = false,
    )

    const val EMPTY = "ยังว่าง"

    fun filters(s: Snapshot): List<Filter> = buildList {
        add(Filter.ALL)
        if (s.downloads.isNotEmpty()) add(Filter.DOWNLOADED)
        if (s.playlists.isNotEmpty()) add(Filter.PLAYLISTS)
        if (s.downloads.any { it.audioOnly }) add(Filter.MUSIC)
    }

    /** The selected chip, or the first (all) chip when that chip no longer has data. */
    fun effective(s: Snapshot, selected: Filter): Filter = if (selected in filters(s)) selected else Filter.ALL

    fun rows(s: Snapshot, filter: Filter, sort: Sort = Sort.RECENT): List<Row> {
        val wl = s.watchLater
        val watchLater = Row(Kind.WATCH_LATER, "ดูภายหลัง", videos(wl?.count ?: 0), wl?.cover, wl?.id ?: Playlists.WATCH_LATER, wl?.latest ?: 0)
        val liked = Row(Kind.LIKED, "วิดีโอที่ชอบ", videos(s.likedCount), s.likedCover, latest = s.likedLatest)
        val mine = s.playlists.map { Row(Kind.PLAYLIST, it.name, videos(it.count), it.cover, it.id, it.latest, editable = true) }
        val create = Row(Kind.CREATE, "สร้างเพลย์ลิสต์ใหม่", "เก็บไว้ในเครื่องนี้", null)
        val collections = listOf(watchLater, liked) + mine
        return when (filter) {
            Filter.ALL -> {
                val newest = s.downloads.maxByOrNull { it.created }
                val downloads = Row(Kind.DOWNLOADS, "การดาวน์โหลด", videos(s.downloads.size), newest?.cover, latest = newest?.created ?: 0)
                // ล่าสุด: downloads compete by time; ก-ฮ / ฮ-ก: downloads stays a fixed row. สร้างเพลย์ลิสต์ใหม่ is always last.
                if (sort == Sort.RECENT) sorted(collections + downloads, sort) + create
                else sorted(collections, sort) + downloads + create
            }
            Filter.PLAYLISTS -> sorted(collections, sort) + create
            Filter.DOWNLOADED, Filter.MUSIC -> emptyList()
        }
    }

    private fun sorted(rows: List<Row>, sort: Sort): List<Row> = when (sort) {
        Sort.RECENT -> rows.sortedByDescending { it.latest }
        Sort.A_Z -> titleComparator().let { c -> rows.sortedWith { a, b -> c.compare(a.title, b.title) } }
        Sort.Z_A -> titleComparator().let { c -> rows.sortedWith { a, b -> c.compare(b.title, a.title) } }
    }

    fun downloads(s: Snapshot, filter: Filter): List<DownloadInfo> = when (filter) {
        Filter.DOWNLOADED -> s.downloads
        Filter.MUSIC -> s.downloads.filter { it.audioOnly }
        else -> emptyList()
    }

    fun emptyText(filter: Filter): String = when (filter) {
        Filter.DOWNLOADED -> "ยังไม่มีวิดีโอที่ดาวน์โหลด"
        Filter.MUSIC -> "ยังไม่มีเพลงที่ดาวน์โหลดแบบเสียง"
        Filter.PLAYLISTS -> "ยังไม่มีเพลย์ลิสต์"
        Filter.ALL -> "ยังไม่มีอะไรในคลังวิดีโอ"
    }

    fun profileLine(s: Snapshot): String = "ติดตาม ${s.subscriptions} ช่อง · ดูแล้ว ${s.watchedCount} วิดีโอ"

    private fun videos(n: Int) = if (n > 0) "$n วิดีโอ" else EMPTY
}
