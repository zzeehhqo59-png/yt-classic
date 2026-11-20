package com.bear.ytclassic

import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Classic red "ติดตาม" / grey "ติดตามแล้ว" button backed by [Subs]. */
fun TextView.bindSubscribe(channel: () -> Channel?, onChange: () -> Unit = {}, capsule: Boolean = false) {
    fun render() {
        val ch = channel()
        val subbed = ch != null && Subs.isSubscribed(ch.url)
        text = if (subbed) "ติดตามแล้ว" else "ติดตาม"
        setTextColor(if (subbed || capsule) androidx.core.content.ContextCompat.getColor(context, if (capsule) R.color.text1 else R.color.text2) else 0xFFCC0000.toInt())
        alpha = if (ch == null) 0.5f else 1f
    }
    render()
    setOnClickListener {
        val ch = channel() ?: return@setOnClickListener
        if (Subs.isSubscribed(ch.url)) {
            AlertDialog.Builder(context)
                .setMessage("เลิกติดตาม ${ch.name} ใช่ไหม")
                .setPositiveButton("เลิกติดตาม") { _, _ -> Subs.remove(ch.url); render(); onChange() }
                .setNegativeButton("ยกเลิก", null)
                .show()
        } else {
            Subs.add(ch)
            Toast.makeText(context, "ติดตาม ${ch.name} แล้ว", Toast.LENGTH_SHORT).show()
            render(); onChange()
        }
    }
}
