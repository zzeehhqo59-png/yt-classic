package com.bear.ytclassic

object Captions {
    const val SIZE_SMALL = 0
    const val SIZE_NORMAL = 1
    const val SIZE_LARGE = 2
    val sizeNames = arrayOf("เล็ก", "ปกติ", "ใหญ่")

    data class Track(val language: String?, val label: String, val auto: Boolean)

    fun fontSp(size: Int): Float = when (size) {
        SIZE_SMALL -> 14f
        SIZE_LARGE -> 28f
        else -> 20f
    }

    fun langMatches(lang: String?, pref: String): Boolean {
        if (lang.isNullOrBlank() || pref.isBlank()) return false
        val a = lang.lowercase().replace('_', '-')
        val b = pref.lowercase().replace('_', '-')
        return a == b || a.startsWith("$b-") || b.startsWith("$a-")
    }

    fun pick(tracks: List<Track>, enabled: Boolean, preferredLang: String?): Track? {
        if (!enabled || tracks.isEmpty()) return null
        val pref = preferredLang?.trim().orEmpty()
        if (pref.isNotEmpty()) {
            val hit = tracks.firstOrNull { langMatches(it.language, pref) && !it.auto }
                ?: tracks.firstOrNull { langMatches(it.language, pref) }
            if (hit != null) return hit
        }
        return tracks.firstOrNull { it.language?.let { langMatches(it, "th") } == true && !it.auto }
            ?: tracks.firstOrNull { it.language?.let { langMatches(it, "th") } == true }
            ?: tracks.firstOrNull { !it.auto }
            ?: tracks.first()
    }

    fun displayLanguage(tag: String?): String {
        if (tag.isNullOrBlank()) return "อัตโนมัติ"
        val name = runCatching { java.util.Locale.forLanguageTag(tag).getDisplayName(java.util.Locale("th")) }.getOrNull()
        return name?.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) } ?: tag
    }
}
