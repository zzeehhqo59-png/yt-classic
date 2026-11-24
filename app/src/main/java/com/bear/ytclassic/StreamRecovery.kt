package com.bear.ytclassic

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/**
 * When to retry after ExoPlayer / extractor failures, and when a newly available
 * network should refetch a fresh stream URL and resume.
 */
object StreamRecovery {
    const val MAX_RETRIES = 5

    /** IO / timeout / unspecified / parse — not decoder, DRM, or failed runtime checks. */
    fun isRecoverable(errorCode: Int): Boolean = errorCode in 1000..3999 && errorCode != 1004

    fun shouldRetry(errorCode: Int, retriesSoFar: Int, offline: Boolean): Boolean =
        !offline && retriesSoFar < MAX_RETRIES && isRecoverable(errorCode)

    fun delayMs(attempt: Int): Long = (1_000L shl (attempt - 1).coerceAtLeast(0)).coerceAtMost(16_000L)

    fun isNetworkFailure(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is java.io.IOException) return true
            t = t.cause
        }
        return false
    }

    fun shouldRefetchOnNetworkRestore(
        hasItem: Boolean,
        offline: Boolean,
        hasError: Boolean,
        playWhenReady: Boolean,
        playbackIdle: Boolean,
    ): Boolean = hasItem && !offline && (hasError || (playWhenReady && playbackIdle))
}

/** Calls [onAvailable] when a validated network comes up (or is already up). */
class NetworkWatch(app: Context, private val notify: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { handler.post { notify() } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) handler.post { notify() }
        }
    }

    fun start() {
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching { cm.registerNetworkCallback(req, callback) }
    }
}
