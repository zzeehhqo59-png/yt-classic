package com.bear.ytclassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Features609Test {
    private val main = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private fun src(path: String) = File(main, path).readText()

    @Test fun publicFoldersAndNames() {
        assertEquals("Music/YT Classic", DeviceSave.relativePath(true))
        assertEquals("Movies/YT Classic", DeviceSave.relativePath(false))
        assertEquals("audio/mp4", DeviceSave.mime(true))
        assertEquals("video/mp4", DeviceSave.mime(false))
        assertEquals("m4a", DeviceSave.ext(true))
        assertEquals("mp4", DeviceSave.ext(false))
        assertEquals("song.m4a", DeviceSave.displayName("song", true, 0, emptySet()))
        assertEquals("clip (720p).mp4", DeviceSave.displayName("clip", false, 720, emptySet()))
        assertEquals("clip (720p) (2).mp4", DeviceSave.displayName("clip", false, 720, setOf("clip (720p).mp4")))
        assertEquals("a b x.m4a", DeviceSave.displayName(DeviceSave.safeName("a/b:x"), true, 0, emptySet()))
        assertEquals("YT Classic", DeviceSave.ALBUM)
    }

    @Test fun storedPathOrContentUriAndExportRule() {
        assertFalse(DeviceSave.isPublic("/storage/emulated/0/Android/data/com.bear.ytclassic/files/Download/YT Classic/a.m4a"))
        assertTrue(DeviceSave.isPublic("content://media/external/audio/media/42"))
        assertTrue(DeviceSave.canExport(Downloads.DONE, "/data/a.m4a"))
        assertFalse(DeviceSave.canExport(Downloads.DONE, "content://media/external/audio/media/42"))
        assertFalse(DeviceSave.canExport(Downloads.RUNNING, "/data/a.m4a"))
        assertFalse(DeviceSave.canExport(Downloads.DONE, null))
    }

    @Test fun settingDefaultsOnAndLegacyPermissionIsCapped() {
        assertTrue(src("java/com/bear/ytclassic/Db.kt").contains("""b("save_to_device", true)"""))
        assertTrue(src("java/com/bear/ytclassic/SettingsActivity.kt").contains("บันทึกลงเครื่อง"))
        val manifest = src("AndroidManifest.xml")
        assertTrue(manifest.contains("android.permission.WRITE_EXTERNAL_STORAGE"))
        assertTrue(Regex("""WRITE_EXTERNAL_STORAGE"[^>]*maxSdkVersion="28"""").containsMatchIn(manifest)
            || manifest.contains("""android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28""""))
        val gradle = File(main.parentFile.parentFile, "build.gradle.kts").readText()
        assertFalse(gradle.contains("jaudiotagger") || gradle.contains("mp4parser"))
    }

    @Test fun downloadMenuHasSaveToDeviceAndNoLongPress() {
        val menu = src("java/com/bear/ytclassic/LibraryDownloads.kt")
        assertTrue(menu.contains("""add("บันทึกลงเครื่อง")"""))
        assertFalse(menu.contains("setOnLongClickListener"))
        val offenders = main.walk().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { f -> f.readText().let { it.contains("setOnLongClickListener") || it.contains("OnLongClickListener") } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
        // finished public files stay playable: the DB still stores path or content Uri in the same column (no schema bump)
        assertEquals(8, Db.VERSION)
    }
}
