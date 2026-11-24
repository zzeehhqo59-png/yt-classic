package com.bear.ytclassic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notification tap: expand the overlay on the current screen, or open Main if none is alive. */
class PlayerOpenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (WatchNav.bringToFrontAndExpand(context)) return
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(WatchActivity.EXTRA_EXPAND_CURRENT, true),
        )
    }
}
