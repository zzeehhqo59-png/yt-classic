package com.bear.ytclassic

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

/** Settings in the style of the classic YouTube Android app: grouped sections, title + summary rows, switches on the right. */
class SettingsActivity : YtActivity() {
    private lateinit var list: LinearLayout
    private val d get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(lightBars = true)
        setContentView(R.layout.activity_settings)
        attachPlayerUi()
        findViewById<View>(R.id.root).padForSystemBars()
        findViewById<View>(R.id.btnBack).setOnClickListener { if (!WatchLayer.consumeBack(this)) finish() }
        list = findViewById(R.id.list)
        build()
    }

    private fun build() {
        list.removeAllViews()
        section("ทั่วไป")
        item("โหมดมืด", Prefs.themeNames[Prefs.theme]) {
            AlertDialog.Builder(this).setTitle("โหมดมืด")
                .setSingleChoiceItems(Prefs.themeNames, Prefs.theme) { dlg, w -> dlg.dismiss(); if (w != Prefs.theme) Prefs.theme = w }
                .setNegativeButton("ยกเลิก", null).show()
        }
        val pipOk = android.os.Build.VERSION.SDK_INT >= 26
        switch("จอลอย (ภาพซ้อนภาพ)", if (pipOk) "ย่อวิดีโอเป็นจอเล็กลอยเมื่อกดปุ่มโฮมระหว่างเล่น" else "ต้องใช้ Android 8.0 ขึ้นไป", Prefs.pip, enabled = pipOk) { Prefs.pip = it }
        switch("เล่นในเบื้องหลัง", "เสียงยังเล่นต่อเมื่อออกจากแอปหรือปิดหน้าจอ", Prefs.background) { Prefs.background = it }

        section("การเล่นและคุณภาพวิดีโอ")
        qualityItem("คุณภาพเริ่มต้นเมื่อใช้ Wi-Fi", { Prefs.qualityWifi }) { Prefs.qualityWifi = it }
        qualityItem("คุณภาพเริ่มต้นเมื่อใช้เน็ตมือถือ", { Prefs.qualityMobile }) { Prefs.qualityMobile = it }
        note("อัตโนมัติ = ปรับความละเอียดตามความเร็วเน็ต เปลี่ยนคุณภาพได้ทุกเมื่อจากปุ่มคุณภาพในหน้าวิดีโอ")

        section("คำบรรยายและเสียง")
        switch("แสดงคำบรรยาย", "เปิดคำบรรยายอัตโนมัติเมื่อวิดีโอมีคำบรรยาย", Prefs.ccEnabled) { Prefs.ccEnabled = it }
        item("ภาษาคำบรรยาย", captionLangName()) {
            val opts = langOptions(Prefs.ccLanguage)
            AlertDialog.Builder(this).setTitle("ภาษาคำบรรยาย")
                .setSingleChoiceItems(opts.map { it.second }.toTypedArray(), opts.indexOfFirst { it.first == Prefs.ccLanguage }.coerceAtLeast(0)) { dlg, w ->
                    dlg.dismiss(); Prefs.ccLanguage = opts[w].first; build()
                }.setNegativeButton("ยกเลิก", null).show()
        }
        item("ขนาดตัวอักษร", Captions.sizeNames.getOrElse(Prefs.ccFontSize) { "ปกติ" }) {
            AlertDialog.Builder(this).setTitle("ขนาดตัวอักษร")
                .setSingleChoiceItems(Captions.sizeNames, Prefs.ccFontSize) { dlg, w -> dlg.dismiss(); Prefs.ccFontSize = w; build() }
                .setNegativeButton("ยกเลิก", null).show()
        }
        item("ภาษาเสียงที่ชอบ", audioLangName()) {
            val opts = langOptions(Prefs.audioLanguage)
            AlertDialog.Builder(this).setTitle("ภาษาเสียงที่ชอบ")
                .setSingleChoiceItems(opts.map { it.second }.toTypedArray(), opts.indexOfFirst { it.first == Prefs.audioLanguage }.coerceAtLeast(0)) { dlg, w ->
                    dlg.dismiss(); Prefs.audioLanguage = opts[w].first; build()
                }.setNegativeButton("ยกเลิก", null).show()
        }
        note("ถ้าวิดีโอไม่มีภาษาที่เลือก จะใช้คำบรรยายอัตโนมัติ และใช้แทร็กเสียงต้นฉบับ")

        section("การดาวน์โหลด")
        val dlNames = Prefs.qualityHeights.map { Prefs.qualityName(it, "สูงสุดที่มี") }.toTypedArray()
        item("คุณภาพการดาวน์โหลดเริ่มต้น", Prefs.qualityName(Prefs.dlQuality, "สูงสุดที่มี")) {
            AlertDialog.Builder(this).setTitle("คุณภาพการดาวน์โหลดเริ่มต้น")
                .setSingleChoiceItems(dlNames, Prefs.qualityHeights.indexOf(Prefs.dlQuality).coerceAtLeast(0)) { dlg, w -> dlg.dismiss(); Prefs.dlQuality = Prefs.qualityHeights[w]; build() }
                .setNegativeButton("ยกเลิก", null).show()
        }
        switch("ดาวน์โหลดเฉพาะเสียง", "เลือกไฟล์เสียง (m4a) เป็นค่าเริ่มต้น เหมาะกับเพลงและพอดแคสต์", Prefs.dlAudioOnly) { Prefs.dlAudioOnly = it }
        switch("ถามคุณภาพทุกครั้ง", "แสดงตัวเลือกคุณภาพก่อนเริ่มดาวน์โหลด", Prefs.dlAsk) { Prefs.dlAsk = it }
        val dir = Downloads.dir(this).path
        item("ตำแหน่งที่บันทึก", dir.substringAfter("/emulated/0/").let { if (it == dir) dir else "ที่เก็บข้อมูลภายใน/$it" }) {
            AlertDialog.Builder(this).setTitle("ตำแหน่งที่บันทึก")
                .setMessage("ไฟล์ที่ดาวน์โหลดเก็บไว้ในพื้นที่ของแอปนี้:\n\n$dir\n\nดูแบบออฟไลน์ได้ที่ แท็บคุณ > การดาวน์โหลด\nไฟล์จะถูกลบเมื่อถอนการติดตั้งแอป")
                .setPositiveButton("ตกลง", null).show()
        }

        section("ประวัติและความเป็นส่วนตัว")
        switch("หยุดประวัติการดูชั่วคราว", "วิดีโอที่ดูต่อจากนี้จะไม่ถูกบันทึกในประวัติการดูและไม่ใช้แนะนำวิดีโอ", Prefs.historyPaused) { Prefs.historyPaused = it }
        item("ล้างประวัติการดู", "ลบวิดีโอทั้งหมดในประวัติการดูของเครื่องนี้") { confirmClearHistory(this) {} }
        item("ล้างประวัติการค้นหา", "ลบคำที่เคยค้นหาทั้งหมด") {
            AlertDialog.Builder(this).setTitle("ล้างประวัติการค้นหาใช่ไหม")
                .setMessage("คำที่เคยค้นหาทั้งหมดจะถูกลบออกจากเครื่องนี้")
                .setPositiveButton("ล้าง") { _, _ -> SearchHistory.clear(); Toast.makeText(this, "ล้างประวัติการค้นหาแล้ว", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("ยกเลิก", null).show()
        }

        section("ฟีดและเนื้อหา")
        switch("ซ่อนวิดีโอที่ดูไม่ได้", "ซ่อนวิดีโอเฉพาะสมาชิก วิดีโอที่ต้องซื้อ วิดีโอรอเปิดตัว/รอฉาย และวิดีโอส่วนตัวหรือถูกลบ ออกจากหน้าแรก การติดตาม การค้นหา หน้าช่อง และวิดีโอถัดไป", Prefs.hideUnplayable) {
            Prefs.hideUnplayable = it
        }

        section("เกี่ยวกับ")
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("")
        item("เวอร์ชัน", "YT Classic $ver") {}
        item("แหล่งข้อมูล", "ข้อมูลวิดีโอจาก YouTube ผ่าน NewPipe Extractor · ข้อมูลทั้งหมดเก็บไว้ในเครื่องนี้เท่านั้น") {}
    }

    private var first = true
    private fun section(title: String) {
        if (list.childCount > 0) list.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply { topMargin = px(8) }
            setBackgroundColor(color(R.color.divider))
        })
        list.addView(TextView(this).apply {
            text = title; textSize = 14f; setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(0xFFCC0000.toInt())
            setPadding(px(16), px(16), px(16), px(4))
        })
    }

    private fun rowBase(title: String, summary: String?): Triple<LinearLayout, LinearLayout, TextView?> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = px(64)
            setPadding(px(16), px(10), px(16), px(10))
            val tv = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); setBackgroundResource(tv.resourceId)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        texts.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(color(R.color.text1)) })
        val sum = summary?.let { TextView(this).apply { text = it; textSize = 14f; setTextColor(color(R.color.text2)); setPadding(0, px(2), 0, 0) }.also { s -> texts.addView(s) } }
        row.addView(texts)
        list.addView(row)
        return Triple(row, texts, sum)
    }

    private fun item(title: String, summary: String?, onClick: () -> Unit) {
        rowBase(title, summary).first.setOnClickListener { onClick() }
    }

    private fun switch(title: String, summary: String, value: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) {
        val (row, _, _) = rowBase(title, summary)
        val sw = SwitchMaterial(this).apply { isChecked = value; isEnabled = enabled; isClickable = false; setPadding(px(12), 0, 0, 0) }
        row.addView(sw)
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else 0.5f
        row.setOnClickListener { sw.isChecked = !sw.isChecked; set(sw.isChecked) }
    }

    private fun note(text: String) {
        list.addView(TextView(this).apply { this.text = text; textSize = 12f; setTextColor(color(R.color.text3)); setPadding(px(16), 0, px(16), px(8)) })
    }

    private fun qualityItem(title: String, get: () -> Int, set: (Int) -> Unit) {
        val names = Prefs.qualityHeights.map { Prefs.qualityName(it) }.toTypedArray()
        item(title, Prefs.qualityName(get())) {
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(names, Prefs.qualityHeights.indexOf(get()).coerceAtLeast(0)) { dlg, w -> dlg.dismiss(); set(Prefs.qualityHeights[w]); build() }
                .setNegativeButton("ยกเลิก", null).show()
        }
    }

    private fun commonLangs() = listOf(
        "" to "อัตโนมัติ", "th" to "ไทย", "en" to "อังกฤษ", "ja" to "ญี่ปุ่น", "ko" to "เกาหลี",
        "zh" to "จีน", "es" to "สเปน", "fr" to "ฝรั่งเศส", "de" to "เยอรมัน", "pt" to "โปรตุเกส",
        "ru" to "รัสเซีย", "id" to "อินโดนีเซีย", "vi" to "เวียดนาม",
    )
    private fun langOptions(current: String): List<Pair<String, String>> {
        val base = commonLangs()
        return if (current.isNotBlank() && base.none { it.first == current }) {
            base.take(1) + listOf(current to Captions.displayLanguage(current)) + base.drop(1)
        } else base
    }
    private fun captionLangName() = if (Prefs.ccLanguage.isBlank()) "อัตโนมัติ" else Captions.displayLanguage(Prefs.ccLanguage)
    private fun audioLangName() = if (Prefs.audioLanguage.isBlank()) "ต้นฉบับ (อัตโนมัติ)" else Captions.displayLanguage(Prefs.audioLanguage)

}
