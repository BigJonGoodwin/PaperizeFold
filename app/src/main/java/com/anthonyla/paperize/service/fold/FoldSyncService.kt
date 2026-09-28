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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import com.anthonyla.paperize.presentation.MainActivity
import com.anthonyla.paperize.service.WallpaperChangeLock
import com.anthonyla.paperize.service.wallpaper.WallpaperController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * PaperizeFold: keeps the cover and inner screens of a foldable showing the same wallpaper.
 *
 * Samsung foldables apply a static wallpaper only to the panel that is active when it is set.
 * This service watches for fold/unfold and re-applies Paperize's *current* wallpaper to the
 * panel that just became active, rendered at that panel's size. It never advances the rotation.
 *
 * It only acts when a panel is actually out of date: every time the wallpaper changes, the
 * active panel is recorded as showing that version, so unfolding to an already-current screen
 * does nothing (no redundant wallpaper change, no extra Material You recolor).
 */
@AndroidEntryPoint
class FoldSyncService : Service() {

    @Inject lateinit var wallpaperController: WallpaperController
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var wallpaperRepository: WallpaperRepository
    @Inject lateinit var wallpaperChangeLock: WallpaperChangeLock

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var displayManager: DisplayManager

    /** Last observed fold state; null until the first read. Main thread only. */
    private var lastFolded: Boolean? = null
    private var pendingSync: Job? = null
    private var pendingRecord: Job? = null

    /** What each panel (key = folded?) currently shows, as a [signature]. */
    private val panelShows = java.util.concurrent.ConcurrentHashMap<Boolean, String>()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) checkFoldState()
        }
    }

    /** Our own re-syncs record their panel directly; ignore their broadcasts until this time. */
    @Volatile private var ignoreBroadcastsUntil = 0L

    private val wallpaperChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (SystemClock.elapsedRealtime() < ignoreBroadcastsUntil) return
            // Something else (e.g. Paperize's schedule) changed the wallpaper. It landed on the
            // panel that was active right now, so capture that before waiting for the DB update.
            val folded = lastFolded ?: return
            pendingRecord?.cancel()
            pendingRecord = scope.launch {
                delay(RECORD_DELAY_MS)
                currentSignature()?.let { panelShows[folded] = it }
            }
        }
    }

    @Suppress("DEPRECATION") // ACTION_WALLPAPER_CHANGED is still broadcast; ColorBlendr relies on it too.
    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(DisplayManager::class.java)
        startInForeground()
        lastFolded = readFolded()
        displayManager.registerDisplayListener(displayListener, mainHandler)
        // System broadcasts are delivered to NOT_EXPORTED receivers too.
        ContextCompat.registerReceiver(
            this,
            wallpaperChangedReceiver,
            IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.d(TAG, "Fold sync started, folded=$lastFolded")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_STICKY
    }

    private fun checkFoldState() {
        val folded = readFolded() ?: return
        if (folded == lastFolded) return
        lastFolded = folded
        pendingSync?.cancel()
        pendingSync = scope.launch {
            // Let the panel switch and launcher settle before drawing for the new size.
            delay(SETTLE_DELAY_MS)
            if (readFolded() != folded) return@launch
            syncActivePanel(folded)
        }
    }

    private suspend fun syncActivePanel(folded: Boolean) {
        wallpaperChangeLock.mutex.withLock {
            try {
                if (settingsRepository.getWallpaperMode() != WallpaperMode.STATIC) return
                val settings = settingsRepository.getScheduleSettings()
                if (!settings.enableChanger || !settings.hasRequiredAlbums(WallpaperMode.STATIC)) return

                val signature = signature(settings) ?: return
                if (panelShows[folded] == signature) {
                    Log.d(TAG, "Panel folded=$folded already current, skipping")
                    return
                }

                val targetSize = if (folded) readPanelSize() else null
                ignoreBroadcastsUntil = Long.MAX_VALUE
                try {
                    for (screen in settings.activeScreens(WallpaperMode.STATIC)) {
                        wallpaperController.reapply(
                            screen = screen,
                            settings = settings,
                            targetSize = targetSize,
                            advanceIfMissing = false
                        )
                    }
                } finally {
                    ignoreBroadcastsUntil = SystemClock.elapsedRealtime() + OWN_BROADCAST_GRACE_MS
                }
                panelShows[folded] = signature
                Log.d(TAG, "Synced wallpaper to panel folded=$folded size=$targetSize")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Fold sync failed", e)
            }
        }
    }

    /** Identifies what should be on screen: current home/lock images plus display settings. */
    private suspend fun currentSignature(): String? {
        if (settingsRepository.getWallpaperMode() != WallpaperMode.STATIC) return null
        return signature(settingsRepository.getScheduleSettings())
    }

    private suspend fun signature(settings: ScheduleSettings): String? {
        val screens = settings.activeScreens(WallpaperMode.STATIC)
        if (screens.isEmpty()) return null
        val homeId = settings.homeAlbumId
            ?.takeIf { ScreenType.HOME in screens || ScreenType.BOTH in screens }
            ?.let { wallpaperRepository.getCurrentWallpaper(it, ScreenType.HOME)?.id }
        val lockId = settings.lockAlbumId
            ?.takeIf { ScreenType.LOCK in screens || ScreenType.BOTH in screens }
            ?.let { wallpaperRepository.getCurrentWallpaper(it, ScreenType.LOCK)?.id }
        val display = listOf(
            settings.homeEffects, settings.lockEffects,
            settings.homeScalingType, settings.lockScalingType,
            settings.homeScrollingEnabled, settings.adaptiveBrightness
        ).hashCode()
        return "$homeId|$lockId|$display"
    }

    /** Current size of the default display, normalized to portrait (width <= height). */
    private fun readPanelSize(): Size? {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val point = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(point)
        if (point.x <= 0 || point.y <= 0) return null
        return Size(minOf(point.x, point.y), maxOf(point.x, point.y))
    }

    /**
     * Cover screens are tall and narrow (~2.4:1); inner screens are close to square (~1.2:1).
     * Returns null if the display can't be read.
     */
    private fun readFolded(): Boolean? {
        val size = readPanelSize() ?: return null
        return size.height.toFloat() / size.width >= FOLDED_ASPECT_THRESHOLD
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
        displayManager.unregisterDisplayListener(displayListener)
        runCatching { unregisterReceiver(wallpaperChangedReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FoldSyncService"
        private const val CHANNEL_ID = "paperize_fold_sync"
        private const val NOTIFICATION_ID = 4201
        private const val SETTLE_DELAY_MS = 800L
        private const val RECORD_DELAY_MS = 500L
        private const val OWN_BROADCAST_GRACE_MS = 2_000L
        private const val FOLDED_ASPECT_THRESHOLD = 1.8f

        /** True on devices with a hinge sensor (foldables). */
        fun isFoldable(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)

        /**
         * Starts the service on foldables. Safe to call repeatedly. Must be called while the app
         * is allowed to start foreground services (app visible, boot completed, or app updated).
         */
        fun start(context: Context) {
            if (!isFoldable(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, FoldSyncService::class.java))
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when started from the background.
                Log.w(TAG, "Could not start fold sync", e)
            }
        }
    }
}
