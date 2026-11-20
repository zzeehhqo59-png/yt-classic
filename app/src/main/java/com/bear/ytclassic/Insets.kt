package com.bear.ytclassic

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Draw behind the system bars (Android 15 forces this for targetSdk 35) on every Android version. */
fun Activity.edgeToEdge(lightBars: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    val light = lightBars && !isNight()
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = light
        isAppearanceLightNavigationBars = light
    }
}

fun android.content.Context.isNight() =
    (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

/**
 * Pads this view by the status bar / navigation bar / cutout insets and consumes them,
 * so the top bar sits below the clock and the bottom nav above the gesture/nav bar.
 * [enabled] lets the watch page drop the padding while fullscreen.
 */
fun View.padForSystemBars(enabled: () -> Boolean = { true }) {
    val l = paddingLeft; val t = paddingTop; val r = paddingRight; val b = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        if (enabled()) {
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + maxOf(bars.bottom, ime.bottom))
        } else v.setPadding(l, t, r, b)
        WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(this)
}
