package com.bear.ytclassic

import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** Turns extractor exceptions into a Thai explanation of why a video cannot be watched. */
object Errors {
    fun video(e: Throwable, item: VideoItem? = null): String {
        val msg = (e.message ?: "")
        val lower = msg.lowercase()
        val reason = msg.substringAfter("Got error", "").trim().trim(':', '"', ' ')
        val upcomingTime = item?.scheduledEpochMs?.takeIf { it > 0 }?.let {
            Fmt.thaiDateTime(OffsetDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()))
        }
        return when {
            item?.upcoming == true || lower.contains("premiere") || lower.contains("live event") ||
                lower.contains("will begin") || msg.contains("ปฐมทัศน์") || msg.contains("จะเริ่ม") ->
                "วิดีโอนี้ยังไม่เผยแพร่ (รอฉายรอบปฐมทัศน์หรือรอถ่ายทอดสด)" +
                    (upcomingTime?.let { "\nจะเริ่มฉาย $it" } ?: "") +
                    (if (reason.isNotEmpty()) "\n$reason" else "")
            e is AgeRestrictedContentException ->
                "วิดีโอนี้จำกัดอายุ\nYouTube ให้ดูได้เฉพาะคนที่ลงชื่อเข้าใช้และยืนยันอายุแล้ว"
            e is PaidContentException || lower.contains("members") || item?.members == true ->
                "วิดีโอนี้ดูได้เฉพาะสมาชิกของช่อง (หรือต้องซื้อก่อน)"
            e is YoutubeMusicPremiumContentException ->
                "วิดีโอนี้ดูได้เฉพาะผู้ใช้ YouTube Music Premium"
            e is PrivateContentException -> "วิดีโอนี้เป็นวิดีโอส่วนตัว"
            e is GeographicRestrictionException || e is UnsupportedContentInCountryException ->
                "วิดีโอนี้ไม่เปิดให้ดูในประเทศไทย"
            e is AccountTerminatedException -> "ช่องของวิดีโอนี้ถูกปิดแล้ว"
            e is SignInConfirmNotBotException || e is ReCaptchaException ->
                "YouTube บล็อกการเข้าถึงชั่วคราว (ขอให้ยืนยันว่าไม่ใช่บอท)\nลองใหม่อีกครั้งภายหลัง หรือเปลี่ยนเครือข่าย"
            e is ContentNotSupportedException -> "แอปนี้ยังไม่รองรับวิดีโอประเภทนี้"
            e is ContentNotAvailableException -> when {
                lower.contains("private") -> "วิดีโอนี้เป็นวิดีโอส่วนตัว"
                lower.contains("removed") || lower.contains("deleted") || lower.contains("terminated") || msg.contains("ถูกนำออก") || msg.contains("ลบ") ->
                    "วิดีโอนี้ถูกลบหรือไม่มีให้ดูแล้ว" + (if (reason.isNotEmpty()) "\n$reason" else "")
                lower.contains("country") -> "วิดีโอนี้ไม่เปิดให้ดูในประเทศไทย"
                else -> "วิดีโอนี้ไม่มีให้ดู" + (if (reason.isNotEmpty()) "\n$reason" else "")
            }
            e is IOException -> "เชื่อมต่ออินเทอร์เน็ตไม่ได้ ตรวจสอบการเชื่อมต่อแล้วลองอีกครั้ง"
            else -> "โหลดวิดีโอไม่ได้ (${e.javaClass.simpleName})"
        }
    }

    fun list(e: Throwable): String = when (e) {
        is SignInConfirmNotBotException, is ReCaptchaException ->
            "YouTube บล็อกการเข้าถึงชั่วคราว ลองใหม่อีกครั้งภายหลัง"
        is IOException -> "เชื่อมต่ออินเทอร์เน็ตไม่ได้ ตรวจสอบการเชื่อมต่อแล้วลองอีกครั้ง"
        else -> "โหลดรายการวิดีโอไม่ได้ (${e.javaClass.simpleName})"
    }
}
