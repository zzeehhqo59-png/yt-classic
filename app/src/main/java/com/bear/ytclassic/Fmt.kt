package com.bear.ytclassic

import org.schabi.newpipe.extractor.localization.DateWrapper
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.floor

/** Thai number / date formatting in the style of the old Thai YouTube app. */
object Fmt {
    private val months = arrayOf("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.")

    private fun oneDecimal(v: Double): String {
        if (v >= 10) return floor(v).toLong().toString()
        val t = floor(v * 10) / 10
        return if (t == floor(t)) t.toLong().toString() else String.format(Locale.US, "%.1f", t)
    }

    /** 1234 -> "1.2 พัน", 12345 -> "1.2 หมื่น", 123456 -> "1.2 แสน", 1234567 -> "1.2 ล้าน" */
    fun shortNum(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 10_000 -> oneDecimal(n / 1_000.0) + " พัน"
        n < 100_000 -> oneDecimal(n / 10_000.0) + " หมื่น"
        n < 1_000_000 -> oneDecimal(n / 100_000.0) + " แสน"
        else -> oneDecimal(n / 1_000_000.0) + " ล้าน"
    }

    fun views(n: Long, live: Boolean = false): String = when {
        n < 0 -> ""
        live -> "กำลังดู ${shortNum(n)} คน"
        else -> "การดู ${shortNum(n)} ครั้ง"
    }

    fun fullViews(n: Long, live: Boolean = false): String = when {
        n < 0 -> ""
        live -> "กำลังดู " + String.format(Locale.US, "%,d", n) + " คน"
        else -> "การดู " + String.format(Locale.US, "%,d", n) + " ครั้ง"
    }

    fun subscribers(n: Long): String = if (n < 0) "" else "ผู้ติดตาม ${shortNum(n)} คน"

    fun duration(sec: Long): String {
        if (sec <= 0) return ""
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun thaiDateTime(t: OffsetDateTime): String {
        val d = t.atZoneSameInstant(ZoneId.systemDefault())
        return String.format(Locale.US, "%d %s %d เวลา %02d:%02d น.", d.dayOfMonth, months[d.monthValue - 1], d.year + 543, d.hour, d.minute)
    }

    fun thaiDate(t: OffsetDateTime): String {
        val d = t.atZoneSameInstant(ZoneId.systemDefault())
        return "${d.dayOfMonth} ${months[d.monthValue - 1]} ${d.year + 543}"
    }

    fun ageMs(ms: Long, fallback: String): String =
        if (ms <= 0) fallback else age(DateWrapper(java.time.Instant.ofEpochMilli(ms)), fallback)

    /** "3 วันที่แล้ว" etc. Falls back to the textual date from YouTube. */
    fun age(date: DateWrapper?, textual: String?): String {
        val t = date?.offsetDateTime() ?: return textual.orEmpty()
        val now = OffsetDateTime.now()
        if (t.isAfter(now.plusMinutes(1))) return "จะเริ่มฉาย " + thaiDateTime(t)
        val secs = Duration.between(t, now).seconds
        return when {
            secs < 60 -> "เมื่อสักครู่"
            secs < 3600 -> "${secs / 60} นาทีที่แล้ว"
            secs < 86400 -> "${secs / 3600} ชั่วโมงที่แล้ว"
            secs < 7 * 86400 -> "${secs / 86400} วันที่แล้ว"
            secs < 30 * 86400 -> "${secs / (7 * 86400)} สัปดาห์ที่แล้ว"
            secs < 365 * 86400 -> "${secs / (30 * 86400)} เดือนที่แล้ว"
            else -> "${secs / (365 * 86400)} ปีที่แล้ว"
        }
    }
}
