package com.bear.ytclassic

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Kill the default right-to-left Activity slide.
 *
 * On API 34+ (targetSdk 34/35) [Activity.overridePendingTransition] is ignored;
 * [Activity.overrideActivityTransition] must be registered in onCreate instead.
 * A 0-duration anim resource is used rather than @null, which some OEM skins
 * (e.g. XOS) treat as "use the default slide".
 */
fun Activity.installNoActivityTransitions() {
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.no_anim, R.anim.no_anim)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.no_anim, R.anim.no_anim)
    }
}

fun Activity.clearActivityTransition() {
    @Suppress("DEPRECATION")
    overridePendingTransition(0, 0)
}

fun Context.startActivityNoTransition(intent: Intent) {
    if (this is Activity) {
        startActivity(intent, ActivityOptions.makeCustomAnimation(this, R.anim.no_anim, R.anim.no_anim).toBundle())
        clearActivityTransition()
    } else startActivity(intent)
}
