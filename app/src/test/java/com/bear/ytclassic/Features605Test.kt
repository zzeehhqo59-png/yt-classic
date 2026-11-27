package com.bear.ytclassic

import org.junit.Assert.assertEquals
import org.junit.Test

class Features605Test {
    private fun pl(id: Long, name: String, latest: Long) = LibraryModel.PlaylistInfo(id, name, 1, null, latest)

    private val snap = LibraryModel.Snapshot(
        watchLater = LibraryModel.PlaylistInfo(Playlists.WATCH_LATER, "ดูภายหลัง", 0, null, latest = 5),
        likedCount = 1, likedLatest = 6,
        playlists = listOf(pl(10, "แมว", 50), pl(11, "Zelda", 40), pl(12, "กาแฟ", 30), pl(13, "apple", 20), pl(14, "เขา", 10)),
        downloads = listOf(LibraryModel.DownloadInfo(1, null, false, created = 100)),
    )

    private fun titles(sort: LibraryModel.Sort) = LibraryModel.rows(snap, LibraryModel.Filter.ALL, sort).map { it.title }

    @Test fun thaiCollatorSortsByConsonantIgnoringLeadingVowels() {
        // Thai: leading vowels (เ แ) sort under the consonant after them: กาแฟ < เขา (ข) < ดูภายหลัง (ด) < แมว (ม) < วิดีโอที่ชอบ (ว)
        val thai = listOf("แมว", "วิดีโอที่ชอบ", "เขา", "ดูภายหลัง", "กาแฟ").sortedWith(LibraryModel.titleComparator())
        assertEquals(listOf("กาแฟ", "เขา", "ดูภายหลัง", "แมว", "วิดีโอที่ชอบ"), thai)
        // English: case-insensitive
        assertEquals(listOf("apple", "Banana", "zelda"), listOf("zelda", "Banana", "apple").sortedWith(LibraryModel.titleComparator()))
    }

    @Test fun alphabeticalSortsKeepDownloadsThenCreateLast() {
        val az = titles(LibraryModel.Sort.A_Z)
        assertEquals(listOf("การดาวน์โหลด", "สร้างเพลย์ลิสต์ใหม่"), az.takeLast(2))
        val sorted = az.dropLast(2)
        assertEquals(sorted.sortedWith(LibraryModel.titleComparator()), sorted)
        val thaiOnly = sorted.filter { it.first() > '\u0E00' }
        assertEquals(listOf("กาแฟ", "เขา", "ดูภายหลัง", "แมว", "วิดีโอที่ชอบ"), thaiOnly)
        assertEquals(listOf("apple", "Zelda"), sorted.filter { it.first() < '\u0E00' })
        val za = titles(LibraryModel.Sort.Z_A)
        assertEquals(sorted.reversed(), za.dropLast(2))
        assertEquals("สร้างเพลย์ลิสต์ใหม่", za.last())
    }

    @Test fun recentSortIsNewestFirstAndChipShowsTheChoice() {
        assertEquals(listOf("การดาวน์โหลด", "แมว", "Zelda", "กาแฟ", "apple", "เขา", "วิดีโอที่ชอบ", "ดูภายหลัง", "สร้างเพลย์ลิสต์ใหม่"), titles(LibraryModel.Sort.RECENT))
        assertEquals(listOf("ล่าสุด", "ก-ฮ", "ฮ-ก"), LibraryModel.Sort.entries.map { it.label })
        assertEquals("ก-ฮ ▾", LibraryModel.chipLabel(LibraryModel.Filter.ALL, LibraryModel.Sort.A_Z))
        assertEquals("เพลย์ลิสต์", LibraryModel.chipLabel(LibraryModel.Filter.PLAYLISTS, LibraryModel.Sort.A_Z))
        assertEquals(LibraryModel.Sort.Z_A, LibraryModel.Sort.fromKey("Z_A"))
        assertEquals(LibraryModel.Sort.RECENT, LibraryModel.Sort.fromKey("garbage"))
        assertEquals(LibraryModel.Sort.RECENT, LibraryModel.Sort.fromKey(null))
        // the playlists chip uses the same sort
        val pls = LibraryModel.rows(snap, LibraryModel.Filter.PLAYLISTS, LibraryModel.Sort.A_Z).map { it.title }
        assertEquals("สร้างเพลย์ลิสต์ใหม่", pls.last())
        assertEquals(pls.dropLast(1).sortedWith(LibraryModel.titleComparator()), pls.dropLast(1))
    }

    @Test fun exportedSubscriptionsImportBackUnchanged() {
        val chans = listOf(
            Channel("https://www.youtube.com/channel/UCabc", "Muse Thailand", "https://img/a.jpg"),
            Channel("https://www.youtube.com/@handle", "ช่อง \"พิเศษ\", ทดสอบ", null),
        )
        val json = SubsFormat.toNewPipeJson(chans)
        val back = SubsFormat.parse(json)
        assertEquals(chans.map { it.url to it.name }, back.map { it.url to it.name })
        // BOM + Takeout CSV still parse
        val csv = "\uFEFFChannel Id,Channel Url,Channel Title\nUCxyz,http://www.youtube.com/channel/UCxyz,\"A, B\"\n"
        assertEquals(listOf("http://www.youtube.com/channel/UCxyz" to "A, B"), SubsFormat.parse(csv).map { it.url to it.name })
        assertEquals("yt-classic-subscriptions-20261127.json", SubsFormat.exportFileName(java.time.LocalDate.of(2026, 11, 27)))
    }
}
