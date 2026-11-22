package com.bear.ytclassic

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType

object AudioTracks {
    data class Stream(val id: String, val name: String?, val language: String?, val original: Boolean, val dubbed: Boolean = false)
    data class Track(val id: String, val label: String, val language: String?, val original: Boolean)

    fun showMenu(tracks: List<Track>): Boolean = tracks.size > 1

    fun pick(tracks: List<Track>, preferredLang: String?): Track? {
        if (tracks.isEmpty()) return null
        val pref = preferredLang?.trim().orEmpty()
        if (pref.isNotEmpty()) {
            tracks.firstOrNull { Captions.langMatches(it.language, pref) || it.id.equals(pref, ignoreCase = true) }
                ?.let { return it }
        }
        return tracks.firstOrNull { it.original } ?: tracks.first()
    }

    fun group(streams: List<Stream>): List<Track> {
        val seen = LinkedHashMap<String, Track>()
        for (s in streams) {
            val id = s.id.ifBlank { s.language ?: if (s.original) "original" else "other" }
            if (id !in seen) seen[id] = Track(id, label(s.name, s.language, s.original, s.dubbed), s.language, s.original)
        }
        return seen.values.toList()
    }

    fun label(name: String?, language: String?, original: Boolean, dubbed: Boolean): String {
        val base = name?.takeIf { it.isNotBlank() }
            ?: language?.let { Captions.displayLanguage(it) }?.takeIf { it != "อัตโนมัติ" }
            ?: "เสียง"
        val tag = when {
            original -> "ต้นฉบับ"
            dubbed -> "พากย์"
            else -> null
        }
        return if (tag != null && !base.contains(tag)) "$base ($tag)" else base
    }

    fun from(s: AudioStream): Stream {
        val type = s.audioTrackType
        val original = type == null || type == AudioTrackType.ORIGINAL
        val lang = s.audioLocale?.toLanguageTag()
        val id = s.audioTrackId?.takeIf { it.isNotBlank() }
            ?: lang
            ?: if (original) "original" else type.name
        return Stream(id, s.audioTrackName, lang, original, type == AudioTrackType.DUBBED)
    }

    fun groupStreams(streams: List<AudioStream>): List<Track> = group(streams.map { from(it) })

    fun filter(streams: List<AudioStream>, track: Track?): List<AudioStream> {
        if (track == null) return streams
        val matched = streams.filter { from(it).id == track.id }
        return matched.ifEmpty { streams }
    }
}
