package com.bear.ytclassic

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Host screens that can show the watch overlay and the mini bar, and that may enter PiP. */
open class YtActivity : AppCompatActivity() {
    fun attachPlayerUi() {
        WatchLayer.attach(this)
        MiniPlayerBar.attach(this)
        ScreenAwake.bind(this)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        WatchLayer.of(this)?.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        WatchLayer.of(this)?.onPipChanged(isInPictureInPictureMode, newConfig)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        WatchLayer.of(this)?.onConfig(newConfig)
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        WatchLayer.of(this)?.onLeavingForOtherScreen()
        super.startActivity(intent, options)
    }
}

fun YtActivity.toolbarBack(finish: () -> Unit = { this.finish() }) {
    if (!WatchLayer.consumeBack(this)) finish()
}
