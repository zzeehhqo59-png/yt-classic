package com.bear.ytclassic

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * How a video is opened after 6.0.2: the player lives in an overlay on the current
 * screen ([WatchLayer]). [WatchActivity] is only a trampoline for old notifications.
 */
object WatchNav {
    fun route(fromActivity: Boolean): String = if (fromActivity) "overlay" else "MainActivity"

    /** The open path must never start a real watch Activity (that caused the slide / flicker). */
    fun launchesWatchActivity(fromActivity: Boolean): Boolean = false.also { route(fromActivity) }

    fun backAction(overlayExpanded: Boolean, landscape: Boolean): String = when {
        !overlayExpanded -> "none"
        landscape -> "exit_landscape"
        else -> "collapse"
    }

    fun swipeDownAction(): String = "collapse"

    fun opensCommentsOnCollapse(): Boolean = false

    fun open(ctx: Context, item: VideoItem, file: File? = null, expandFrom: Float = -1f) {
        val a = ctx as? AppCompatActivity
        if (a != null) { WatchLayer.attach(a).open(item, file, expandFrom); return }
        ctx.startActivity(
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(WatchActivity.EXTRA_ITEM, item)
                .putExtra(WatchActivity.EXTRA_FILE, file?.path)
                .putExtra(WatchActivity.EXTRA_EXPAND_FROM, expandFrom),
        )
    }

    fun consumeIntent(a: AppCompatActivity, intent: Intent?) {
        if (intent == null) return
        val expandCurrent = intent.getBooleanExtra(WatchActivity.EXTRA_EXPAND_CURRENT, false)
        @Suppress("DEPRECATION")
        val item = intent.getSerializableExtra(WatchActivity.EXTRA_ITEM) as? VideoItem
        val file = intent.getStringExtra(WatchActivity.EXTRA_FILE)?.let { File(it) }
        val from = intent.getFloatExtra(WatchActivity.EXTRA_EXPAND_FROM, -1f)
        if (item == null && !expandCurrent) return
        intent.removeExtra(WatchActivity.EXTRA_ITEM)
        intent.removeExtra(WatchActivity.EXTRA_EXPAND_CURRENT)
        WatchLayer.attach(a).open(item, file, from)
    }

    fun revealFromIntent(starter: Activity, intent: Intent) {
        val host = WatchLayer.lastAlive()?.takeIf { it !== starter }
        if (host != null) {
            consumeIntent(host, intent)
            starter.overridePendingTransition(0, 0)
            return
        }
        starter.startActivityNoTransition(
            Intent(starter, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtras(intent),
        )
    }

    fun bringToFrontAndExpand(ctx: Context): Boolean {
        val host = WatchLayer.lastAlive() ?: return false
        val item = Playback.state.value.item ?: return false
        WatchLayer.attach(host).open(item, null, 1f)
        ctx.startActivity(
            Intent(ctx, host.javaClass)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        return true
    }
}
