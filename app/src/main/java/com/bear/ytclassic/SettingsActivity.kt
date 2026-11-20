package com.bear.ytclassic

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(lightBars = true)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.root).padForSystemBars()
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        val themeValue = findViewById<TextView>(R.id.themeValue)
        themeValue.text = Prefs.themeNames[Prefs.theme]
        findViewById<View>(R.id.rowTheme).setOnClickListener {
            AlertDialog.Builder(this).setTitle("ธีม")
                .setSingleChoiceItems(Prefs.themeNames, Prefs.theme) { d, w -> d.dismiss(); if (w != Prefs.theme) Prefs.theme = w }
                .setNegativeButton("ยกเลิก", null).show()
        }
        findViewById<SwitchMaterial>(R.id.swBackground).apply { isChecked = Prefs.background; setOnCheckedChangeListener { _, on -> Prefs.background = on } }
        findViewById<SwitchMaterial>(R.id.swPip).apply {
            isChecked = Prefs.pip; setOnCheckedChangeListener { _, on -> Prefs.pip = on }
            if (android.os.Build.VERSION.SDK_INT < 26) { isEnabled = false; text = "จอลอย (ภาพซ้อนภาพ)\nต้องใช้ Android 8.0 ขึ้นไป" }
        }
        findViewById<View>(R.id.rowClearHistory).setOnClickListener { confirmClearHistory(this) {} }
        findViewById<View>(R.id.rowClearSearch).setOnClickListener {
            AlertDialog.Builder(this).setTitle("ล้างประวัติการค้นหาใช่ไหม")
                .setMessage("คำที่เคยค้นหาทั้งหมดจะถูกลบออกจากเครื่องนี้")
                .setPositiveButton("ล้าง") { _, _ -> SearchHistory.clear(); android.widget.Toast.makeText(this, "ล้างประวัติการค้นหาแล้ว", android.widget.Toast.LENGTH_SHORT).show() }
                .setNegativeButton("ยกเลิก", null).show()
        }
        findViewById<TextView>(R.id.version).text = "YT Classic เวอร์ชัน " + runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("")
    }
}
