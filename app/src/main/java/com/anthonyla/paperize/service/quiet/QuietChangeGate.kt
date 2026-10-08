package com.anthonyla.paperize.service.quiet

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.data.datastore.FoldPreferences
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.service.WallpaperChangeLock
import com.anthonyla.paperize.service.fold.FoldState
import com.anthonyla.paperize.service.fold.FoldSyncPolicy
import com.anthonyla.paperize.service.fold.PanelTracker
import com.anthonyla.paperize.service.wallpaper.WallpaperController
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * PaperizeFold: holds back wallpaper changes until they won't interrupt anything.
 *
 * Every wallpaper change makes Material You apps (and tools like ColorBlendr) recolor, which
 * reloads open apps and can pause videos. So:
 * - Scheduled changes wait until the screen is off and nothing is playing.
 * - Effect/scaling edits are collected and applied once, after you leave the app.
 * - Manual "change now" requests are never delayed.
 *
 * "Nothing is playing" can't wait forever: after the user's wait limit the change goes through
 * anyway (with the screen off that's invisible, and background audio apps aren't reloaded).
 *
 * Events (screen off, playback stopped, app closed) come from FoldSyncService and the
 * Application. If that service isn't running, [active] is false and nothing is delayed.
 */
@Singleton
class QuietChangeGate @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wallpaperController: WallpaperController,
    private val settingsRepository: SettingsRepository,
    private val wallpaperChangeLock: WallpaperChangeLock,
    private val foldPreferences: FoldPreferences,
    private val foldState: FoldState,
    private val panelTracker: PanelTracker
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val evaluateMutex = Mutex()
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** Set by FoldSyncService while it is running and delivering events. */
    @Volatile var active: Boolean = false

    private val pendingScheduled: MutableSet<ScreenType> = ConcurrentHashMap.newKeySet()
    @Volatile private var pendingEffects = false
    @Volatile private var waitingSince = 0L
    private var capJob: Job? = null

    fun isScreenOn(): Boolean = powerManager?.isInteractive ?: true

    fun isMediaPlaying(): Boolean {
        val manager = audioManager ?: return false
        if (manager.isMusicActive) return true
        return manager.activePlaybackConfigurations.any {
            val usage = it.audioAttributes.usage
            usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME
        }
    }

    private fun isAppVisible(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun maxMediaWaitMs(): Long = foldPreferences.current.mediaWaitMinutes * 60_000L

    private fun waitedTooLong(): Boolean =
        waitingSince != 0L && SystemClock.elapsedRealtime() - waitingSince >= maxMediaWaitMs()

    /**
     * True when nothing is playing, "wait for media" is off, or we've already waited as long as
     * the user allows.
     */
    fun mediaAllows(): Boolean =
        !foldPreferences.current.waitForMedia || !isMediaPlaying() || waitedTooLong()

    private fun scheduledAllowed(): Boolean {
        val prefs = foldPreferences.current
        return (!prefs.waitForScreenOff || !isScreenOn()) && mediaAllows()
    }

    private fun effectsAllowed(): Boolean = !isAppVisible() && mediaAllows()

    /**
     * Called by the scheduled worker. Returns true if the change was deferred, in which case the
     * caller must not change the wallpaper now; it will happen on a later [evaluate].
     */
    fun deferScheduledIfNeeded(screen: ScreenType): Boolean {
        if (!active || scheduledAllowed()) return false
        pendingScheduled += screen
        markWaiting()
        Log.d(TAG, "Deferred scheduled change for $screen until screen off / playback stops")
        return true
    }

    /**
     * Called when effects or scaling change. Returns true if the gate took the request (it will
     * re-apply once after the app is closed). False means the caller should apply immediately.
     */
    fun requestEffectsReapply(): Boolean {
        if (!active || !foldPreferences.current.deferEffects) return false
        pendingEffects = true
        markWaiting()
        evaluateAsync()
        return true
    }

    fun hasPendingWork(): Boolean = pendingScheduled.isNotEmpty() || pendingEffects

    fun evaluateAsync() {
        if (!hasPendingWork()) return
        scope.launch { evaluate() }
    }

    suspend fun evaluate() = evaluateMutex.withLock {
        try {
            if (pendingScheduled.isNotEmpty() && scheduledAllowed()) runScheduled()
            if (pendingEffects && effectsAllowed()) runEffects()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Deferred wallpaper work failed", e)
        } finally {
            if (!hasPendingWork()) {
                waitingSince = 0L
                capJob?.cancel()
                capJob = null
            }
        }
    }

    private suspend fun runScheduled() {
        val screens = pendingScheduled.toList()
        pendingScheduled.removeAll(screens.toSet())
        wallpaperChangeLock.mutex.withLock {
            val settings = settingsRepository.getScheduleSettings()
            for (screen in screens) {
                wallpaperController.change(screen, settings)
            }
        }
        // A scheduled change supersedes a pending effects edit: it renders with current settings.
        pendingEffects = false
        Log.d(TAG, "Ran deferred scheduled change for $screens")
    }

    private suspend fun runEffects() {
        pendingEffects = false
        wallpaperChangeLock.mutex.withLock {
            val mode = settingsRepository.getWallpaperMode()
            val settings = settingsRepository.getScheduleSettings()
            if (mode != WallpaperMode.STATIC || !settings.enableChanger || !settings.hasRequiredAlbums(mode)) {
                return@withLock
            }
            val panel = foldState.activePanel()
            if (panel == null) {
                for (screen in settings.activeScreens(mode)) {
                    wallpaperController.reapply(screen, settings)
                }
            } else {
                // PaperizeFold: only what looks different on the screen in use. A fold sync may
                // already have applied the new look, and edits to the other screen's look wait
                // for the next fold.
                val needed = panelTracker.slotsNeedingWrite(panel, panelTracker.expectedSignatures(panel, settings))
                for (target in FoldSyncPolicy.writeTargets(needed)) {
                    wallpaperController.reapply(target, settings, panel = panel)
                }
            }
        }
        Log.d(TAG, "Applied pending effect changes")
    }

    private fun markWaiting() {
        if (waitingSince == 0L) waitingSince = SystemClock.elapsedRealtime()
        if (capJob?.isActive == true) return
        capJob = scope.launch {
            // Re-check once the media wait cap has passed, in case no other event arrives.
            delay(maxMediaWaitMs() + 1_000L)
            evaluate()
        }
    }

    companion object {
        private const val TAG = "QuietChangeGate"
    }
}
