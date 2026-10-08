package com.anthonyla.paperize.service.fold

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.anthonyla.paperize.R
import com.anthonyla.paperize.presentation.MainActivity
import com.anthonyla.paperize.service.quiet.QuietChangeGate
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * PaperizeFold: delivers fold, screen-off, playback and wallpaper events to [FoldCoordinator]
 * (fold sync) and [QuietChangeGate] (quiet changes). The logic lives in those singletons; this
 * service only keeps the app alive in the background with a silent notification.
 */
@AndroidEntryPoint
class FoldSyncService : Service() {

    @Inject lateinit var foldCoordinator: FoldCoordinator
    @Inject lateinit var quietChangeGate: QuietChangeGate

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var displayManager: DisplayManager
    private var audioManager: AudioManager? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) foldCoordinator.onDisplayChanged()
        }
    }

    private val wallpaperChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            foldCoordinator.onWallpaperChanged()
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            foldCoordinator.onScreenOff()
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            foldCoordinator.onPlaybackChanged()
        }
    }

    @Suppress("DEPRECATION") // ACTION_WALLPAPER_CHANGED is still broadcast; ColorBlendr relies on it too.
    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(DisplayManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        startInForeground()
        displayManager.registerDisplayListener(displayListener, mainHandler)
        // System broadcasts are delivered to NOT_EXPORTED receivers too.
        ContextCompat.registerReceiver(
            this,
            wallpaperChangedReceiver,
            IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        audioManager?.registerAudioPlaybackCallback(playbackCallback, mainHandler)
        quietChangeGate.active = true
        foldCoordinator.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.fold_sync_channel_name),
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = getString(R.string.fold_sync_channel_description)
                    setShowBadge(false)
                }
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent().setClassName(packageName, MainActivity::class.java.name),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.fold_sync_notification_title))
            .setContentText(getString(R.string.fold_sync_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        foldCoordinator.stop()
        quietChangeGate.active = false
        displayManager.unregisterDisplayListener(displayListener)
        audioManager?.unregisterAudioPlaybackCallback(playbackCallback)
        runCatching { unregisterReceiver(wallpaperChangedReceiver) }
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FoldSyncService"
        private const val CHANNEL_ID = "paperize_fold_sync"
        private const val NOTIFICATION_ID = 4201

        /** True on devices with a hinge sensor (foldables). */
        fun isFoldable(context: Context): Boolean =
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_SENSOR_HINGE_ANGLE)

        /**
         * Starts the service (fold sync on foldables, quiet-change events everywhere).
         * Safe to call repeatedly. Must be called while the app is allowed to start foreground
         * services (app visible, boot completed, or app updated).
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, FoldSyncService::class.java))
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when started from the background.
                Log.w(TAG, "Could not start fold sync", e)
            }
        }
    }
}
