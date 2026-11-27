package com.bear.ytclassic

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Subscription file formats: NewPipe JSON export (read + write) and YouTube Takeout subscriptions.csv (read). */
object SubsFormat {
    private const val YOUTUBE_SERVICE_ID = 0

    fun exportFileName(date: LocalDate): String = "yt-classic-subscriptions-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}.json"

    fun toNewPipeJson(channels: List<Channel>): String {
        val arr = JSONArray()
        channels.forEach { ch ->
            arr.put(JSONObject().put("service_id", YOUTUBE_SERVICE_ID).put("url", ch.url).put("name", ch.name))
        }
        return JSONObject().put("app", "YT Classic").put("subscriptions", arr).toString(2)
    }

    fun parse(raw: String): List<Channel> {
        val text = raw.removePrefix("\uFEFF")
        return if (text.trimStart().startsWith("{")) parseNewPipe(text) else parseTakeoutCsv(text)
    }

    private fun parseNewPipe(text: String): List<Channel> {
        val arr = JSONObject(text).getJSONArray("subscriptions")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (o.optInt("service_id", YOUTUBE_SERVICE_ID) != YOUTUBE_SERVICE_ID) return@mapNotNull null
            val url = o.optString("url"); if (url.isBlank()) null else Channel(url, o.optString("name"), null)
        }
    }

    private fun parseTakeoutCsv(text: String): List<Channel> {
        val rows = text.lines().filter { it.isNotBlank() }.map { csvRow(it) }
        return rows.mapNotNull { r ->
            val id = r.getOrNull(0)?.trim().orEmpty()
            if (!id.startsWith("UC")) return@mapNotNull null   // skips the header row (any language)
            val url = r.getOrNull(1)?.trim()?.takeIf { it.startsWith("http") } ?: "https://www.youtube.com/channel/$id"
            Channel(url, r.getOrNull(2)?.trim().orEmpty(), null)
        }
    }

    private fun csvRow(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                q && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                ch == ',' && !q -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
