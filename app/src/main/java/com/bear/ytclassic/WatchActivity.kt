package com.bear.ytclassic

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Trampoline only: leftover intents / old notifications. The real player is [WatchLayer]
 * on the current screen, so this Activity never inflates a watch page or loads comments.
 */
class WatchActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ITEM = "item"
        const val EXTRA_EXPAND_FROM = "expand_from"
        const val EXTRA_FILE = "file"
        const val EXTRA_EXPAND_CURRENT = "expand_current"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchNav.revealFromIntent(this, intent)
        finish()
        clearActivityTransition()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        WatchNav.revealFromIntent(this, intent)
        finish()
        clearActivityTransition()
    }

    override fun finish() {
        super.finish()
        clearActivityTransition()
    }
}
