package com.bear.ytclassic

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.ServiceCompat
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ui.PlayerNotificationManager

/** Keeps audio going in the background and shows the media notification (play/pause/seek). */
class PlaybackService : Service() {
    private lateinit var session: MediaSessionCompat
    private lateinit var connector: MediaSessionConnector
    private lateinit var notif: PlayerNotificationManager
    private var foreground = false
    private var iconUrl: String? = null
    private var icon: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "ytclassic").apply { isActive = true }
        connector = MediaSessionConnector(session).apply {
            setPlayer(Playback.player)
            setMediaMetadataProvider { p ->
                val it = Playback.state.value.item
                android.support.v4.media.MediaMetadataCompat.Builder()
                    .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, it?.title.orEmpty())
                    .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, it?.channel.orEmpty())
                    .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, if (p.duration > 0) p.duration else -1)
                    .apply { icon?.let { b -> putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART, b) } }
                    .build()
            }
        }
        notif = PlayerNotificationManager.Builder(this, 1001, "playback")
            .setChannelNameResourceId(R.string.notif_channel)
            .setSmallIconResourceId(R.drawable.ic_notif)
            .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: Player): CharSequence = Playback.state.value.item?.title ?: getString(R.string.app_name)
                override fun getCurrentContentText(player: Player): CharSequence? = Playback.state.value.item?.channel
                override fun createCurrentContentIntent(player: Player): PendingIntent =
                    PendingIntent.getActivity(this@PlaybackService, 0,
                        Intent(this@PlaybackService, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                override fun getCurrentLargeIcon(player: Player, callback: PlayerNotificationManager.BitmapCallback): Bitmap? {
                    val url = Playback.state.value.item?.thumb ?: return null
                    if (url == iconUrl) return icon
                    iconUrl = url; icon = null
                    imageLoader.enqueue(ImageRequest.Builder(this@PlaybackService).data(url).allowHardware(false)
                        .target(onSuccess = { img -> if (iconUrl == url) { icon = img.toBitmap(); callback.onBitmap(icon!!); connector.invalidateMediaSessionMetadata() } })
                        .build())
                    return null
                }
            })
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                override fun onNotificationPosted(id: Int, notification: Notification, ongoing: Boolean) {
                    lastNotif = notification
                    if (ongoing && !foreground) {
                        ServiceCompat.startForeground(this@PlaybackService, id, notification,
                            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
                        foreground = true
                    } else if (!ongoing && foreground) {
                        ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_DETACH)
                        foreground = false
                    }
                }
                override fun onNotificationCancelled(id: Int, dismissedByUser: Boolean) {
                    if (dismissedByUser) Playback.close() else stopSelf()
                }
            })
            .build().apply {
                setMediaSessionToken(session.sessionToken)
                setUseNextAction(false); setUsePreviousAction(false)
                setUseFastForwardAction(true); setUseRewindAction(true)
                setUseStopAction(false)
                setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                setPlayer(Playback.player)
            }
    }

    private var lastNotif: Notification? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // every startForegroundService() needs its own startForeground() call (also when already in the foreground,
        // or when the player notification is about to drop out of it while switching videos)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = android.app.NotificationChannel("playback", getString(R.string.notif_channel), android.app.NotificationManager.IMPORTANCE_LOW)
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(ch)
        }
        val n = lastNotif ?: androidx.core.app.NotificationCompat.Builder(this, "playback").setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(Playback.state.value.item?.title ?: getString(R.string.app_name)).setSilent(true).build()
        ServiceCompat.startForeground(this, 1001, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        foreground = true
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!Playback.player.isPlaying) Playback.close()
    }

    override fun onDestroy() {
        notif.setPlayer(null)
        connector.setPlayer(null)
        session.release()
        super.onDestroy()
    }
}
