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
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
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
import androidx.core.content.edit
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.data.datastore.FoldPreferences
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import com.anthonyla.paperize.presentation.MainActivity
import com.anthonyla.paperize.service.WallpaperChangeLock
import com.anthonyla.paperize.service.quiet.QuietChangeGate
import com.anthonyla.paperize.service.wallpaper.WallpaperController
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
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
 * PaperizeFold: keeps the cover and inner screens of a foldable showing the same wallpaper,
 * and feeds screen/media events to [QuietChangeGate] so wallpaper changes happen quietly.
 *
 * Samsung foldables apply a static wallpaper only to the panel that is active when it is set.
 * This service watches for fold/unfold and re-applies Paperize's *current* wallpaper to the
 * panel that just became active, sized for that panel. It never advances the rotation.
 *
 * - Panels that are already current are skipped (no redundant change, no extra recolor).
 * - While the phone is open, the cover version is pre-rendered in the background, so folding
 *   only has to hand finished image bytes to Android.
 * - If media is playing when you fold, the sync waits until playback stops or the screen
 *   turns off, so videos aren't interrupted by a recolor.
 */
@AndroidEntryPoint
class FoldSyncService : Service() {

    @Inject lateinit var wallpaperController: WallpaperController
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var wallpaperRepository: WallpaperRepository
    @Inject lateinit var wallpaperChangeLock: WallpaperChangeLock
    @Inject lateinit var quietChangeGate: QuietChangeGate
    @Inject lateinit var foldPreferences: FoldPreferences

    /** Fold syncing only makes sense on foldables; the quiet-change events run everywhere. */
    private var foldable = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var displayManager: DisplayManager
    private var audioManager: AudioManager? = null

    /** Last observed fold state; null until the first read. Written on the main thread. */
    @Volatile private var lastFolded: Boolean? = null
    private var pendingSync: Job? = null
    private var pendingRecord: Job? = null
    private var prerenderJob: Job? = null

    /** A fold/unfold happened while media was playing; sync once it's quiet. */
    @Volatile private var foldSyncWaiting = false

    /** What each panel (key = folded?) currently shows, as a [signature]. */
    private val panelShows = ConcurrentHashMap<Boolean, String>()

    /** Ready-to-apply cover wallpaper(s), rendered while the phone was open. */
    private class CoverCache(val signature: String, val size: Size, val encoded: List<Pair<ScreenType, ByteArray>>)
    @Volatile private var coverCache: CoverCache? = null

    /** Our own re-syncs record their panel directly; ignore their broadcasts until this time. */
    @Volatile private var ignoreBroadcastsUntil = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) checkFoldState()
        }
    }

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
                maybePrerenderCover()
            }
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch { onQuietMoment() }
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            if (!quietChangeGate.isMediaPlaying()) scope.launch { onQuietMoment() }
        }
    }

    @Suppress("DEPRECATION") // ACTION_WALLPAPER_CHANGED is still broadcast; ColorBlendr relies on it too.
    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(DisplayManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        foldable = isFoldable(this)
        startInForeground()
        lastFolded = readFolded()
        if (lastFolded == true) rememberCoverSize()
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
        Log.d(TAG, "Fold sync started, folded=$lastFolded")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_STICKY
    }

    private fun foldSyncOn(): Boolean = foldable && foldPreferences.current.foldSyncEnabled

    /** False while media is playing, unless the user turned off waiting for media on fold. */
    private fun foldMediaAllows(): Boolean =
        !foldPreferences.current.foldWaitForMedia || !quietChangeGate.isMediaPlaying()

    /** Screen went off or playback stopped: run anything that was waiting for quiet. */
    private suspend fun onQuietMoment() {
        quietChangeGate.evaluate()
        val folded = lastFolded ?: return
        if (foldSyncWaiting && foldSyncOn() && foldMediaAllows()) {
            foldSyncWaiting = false
            syncActivePanel(folded)
        }
    }

    private fun checkFoldState() {
        val folded = readFolded() ?: return
        if (folded == lastFolded) return
        lastFolded = folded
        if (!foldSyncOn()) return
        pendingSync?.cancel()
        if (folded) prerenderJob?.cancel() // cover is active now; use whatever is cached
        pendingSync = scope.launch {
            // Give the panel switch a moment to finish before drawing for the new size.
            delay(SETTLE_DELAY_MS)
            if (readFolded() != folded) return@launch
            if (folded) rememberCoverSize()
            if (!foldMediaAllows()) {
                // Don't recolor under a playing video; catch up when it stops or screen turns off.
                foldSyncWaiting = true
                return@launch
            }
            foldSyncWaiting = false
            syncActivePanel(folded)
        }
    }

    private suspend fun syncActivePanel(folded: Boolean) {
        wallpaperChangeLock.mutex.withLock {
            try {
                if (!foldSyncOn()) return
                if (settingsRepository.getWallpaperMode() != WallpaperMode.STATIC) return
                val settings = settingsRepository.getScheduleSettings()
                if (!settings.enableChanger || !settings.hasRequiredAlbums(WallpaperMode.STATIC)) return

                val signature = signature(settings) ?: return
                if (panelShows[folded] == signature) {
                    Log.d(TAG, "Panel folded=$folded already current, skipping")
                    return
                }

                val panelSize = if (folded) readPanelSize() else null
                val cached = coverCache
                ignoreBroadcastsUntil = Long.MAX_VALUE
                try {
                    if (folded && cached != null && cached.signature == signature && cached.size == panelSize) {
                        // Fast path: already rendered and encoded while the phone was open.
                        wallpaperController.applyEncoded(cached.encoded)
                        Log.d(TAG, "Applied pre-rendered cover wallpaper")
                    } else {
                        for (screen in settings.activeScreens(WallpaperMode.STATIC)) {
                            wallpaperController.reapply(
                                screen = screen,
                                settings = settings,
                                targetSize = panelSize,
                                advanceIfMissing = false
                            )
                        }
                    }
                } finally {
                    ignoreBroadcastsUntil = SystemClock.elapsedRealtime() + OWN_BROADCAST_GRACE_MS
                }
                if (folded) coverCache = null
                panelShows[folded] = signature
                Log.d(TAG, "Synced wallpaper to panel folded=$folded size=$panelSize")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Fold sync failed", e)
            }
        }
        maybePrerenderCover()
    }

    /**
     * While the phone is open and the cover is out of date, render the cover version ahead of
     * time so folding is near-instant.
     */
    private suspend fun maybePrerenderCover() {
        if (!foldSyncOn() || !foldPreferences.current.prerenderCover) {
            coverCache = null
            return
        }
        if (lastFolded != false) return
        val coverSize = storedCoverSize() ?: return
        val signature = currentSignature() ?: return
        if (panelShows[true] == signature) return
        val cached = coverCache
        if (cached != null && cached.signature == signature && cached.size == coverSize) return

        prerenderJob?.cancel()
        prerenderJob = scope.launch {
            delay(PRERENDER_DELAY_MS)
            try {
                val settings = settingsRepository.getScheduleSettings()
                if (signature(settings) != signature) return@launch
                val encoded = settings.activeScreens(WallpaperMode.STATIC).flatMap { screen ->
                    wallpaperController.renderEncoded(screen, settings, coverSize)
                }
                if (encoded.isNotEmpty() && lastFolded == false) {
                    coverCache = CoverCache(signature, coverSize, encoded)
                    Log.d(TAG, "Pre-rendered cover wallpaper (${encoded.sumOf { it.second.size } / 1024} KB)")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cover pre-render failed", e)
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

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun rememberCoverSize() {
        val size = readPanelSize() ?: return
        prefs().edit {
            putInt(KEY_COVER_WIDTH, size.width)
            putInt(KEY_COVER_HEIGHT, size.height)
        }
    }

    private fun storedCoverSize(): Size? {
        val width = prefs().getInt(KEY_COVER_WIDTH, 0)
        val height = prefs().getInt(KEY_COVER_HEIGHT, 0)
        return if (width > 0 && height > 0) Size(width, height) else null
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
        quietChangeGate.active = false
        displayManager.unregisterDisplayListener(displayListener)
        audioManager?.unregisterAudioPlaybackCallback(playbackCallback)
        runCatching { unregisterReceiver(wallpaperChangedReceiver) }
        runCatching { unregisterReceiver(screenOffReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FoldSyncService"
        private const val CHANNEL_ID = "paperize_fold_sync"
        private const val NOTIFICATION_ID = 4201
        private const val SETTLE_DELAY_MS = 350L
        private const val RECORD_DELAY_MS = 500L
        private const val PRERENDER_DELAY_MS = 3_000L
        private const val OWN_BROADCAST_GRACE_MS = 2_000L
        private const val FOLDED_ASPECT_THRESHOLD = 1.8f
        private const val PREFS_NAME = "paperize_fold"
        private const val KEY_COVER_WIDTH = "cover_width"
        private const val KEY_COVER_HEIGHT = "cover_height"

        /** True on devices with a hinge sensor (foldables). */
        fun isFoldable(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)

        /**
         * Starts the service (fold sync on foldables, quiet-change events everywhere).
         * Safe to call repeatedly. Must be called while the app
         * is allowed to start foreground services (app visible, boot completed, or app updated).
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
