package com.anthonyla.paperize.service.fold

import android.os.SystemClock
import android.util.Log
import com.anthonyla.paperize.core.FoldPanel
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.staticSlots
import com.anthonyla.paperize.data.datastore.FoldPreferences
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.service.WallpaperChangeLock
import com.anthonyla.paperize.service.quiet.QuietChangeGate
import com.anthonyla.paperize.service.wallpaper.WallpaperController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * PaperizeFold: keeps both screens of a foldable showing Paperize's current wallpaper.
 *
 * Samsung foldables only apply a static wallpaper to the screen in use. So after every fold or
 * unfold (once it settles), the screen now in use is checked against [PanelTracker]'s record of
 * what it shows, and only the slots that are out of date are re-applied. Nothing ever advances
 * the rotation.
 *
 * - The other screen's version is rendered ahead of time, so the switch only hands finished
 *   image bytes to Android.
 * - Scheduled changes held for a quiet moment run first, so a fold that turns the screen off
 *   doesn't write twice.
 * - If media is playing, the sync waits until it stops or the screen turns off.
 *
 * Events arrive from FoldSyncService.
 */
@Singleton
class FoldCoordinator @Inject constructor(
    private val foldState: FoldState,
    private val tracker: PanelTracker,
    private val wallpaperController: WallpaperController,
    private val settingsRepository: SettingsRepository,
    private val wallpaperChangeLock: WallpaperChangeLock,
    private val quietChangeGate: QuietChangeGate,
    private val foldPreferences: FoldPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobLock = Any()
    private var sessionJob: Job? = null
    private var delayJob: Job? = null
    private var prerenderJob: Job? = null

    /**
     * Checks run one at a time, in order. A request made while one is running queues a single
     * follow-up instead of cancelling it, so a screen-off during a fold sync can't drop the sync.
     */
    private val checks = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var running = false
    @Volatile private var seenTransitionAt = 0L

    /**
     * The screen in use should be checked now: after a fold, after a write that may have landed
     * on the other screen, or when the user asks. Stays set while media holds the check back.
     */
    @Volatile private var syncPending = false

    /** After starting, check the screen in use at the next screen-off (out of sight). */
    @Volatile private var verifyWhenQuiet = false

    /** A check was held back because media was playing; run it when playback stops. */
    @Volatile private var heldForMedia = false

    /** The user asked to resync: ignore the media wait once. */
    @Volatile private var forceNext = false

    /** Ready-to-apply wallpapers for the screen not in use, and what they show. */
    private class PanelCache(
        val panel: FoldPanel,
        val signatures: Map<ScreenType, String>,
        val entries: List<Pair<ScreenType, ByteArray>>
    )
    @Volatile private var cache: PanelCache? = null

    init {
        tracker.onUncertainWrite = {
            syncPending = true
            requestCheck(FoldSyncPolicy.SETTLE_MS)
        }
        scope.launch {
            for (request in checks) {
                try {
                    runCheck()
                } catch (e: CancellationException) {
                    // Only stop when the scope itself is cancelled; otherwise keep serving checks.
                    currentCoroutineContext().ensureActive()
                    Log.w(TAG, "Fold check was cancelled", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Fold check failed", e)
                }
            }
        }
    }

    private fun syncOn(): Boolean = foldState.isFoldable && foldPreferences.current.foldSyncEnabled

    /**
     * False while media is playing with the screen on, unless the user turned off waiting for
     * media on fold. With the screen off there's no video to interrupt.
     */
    private fun mediaAllows(): Boolean =
        !foldPreferences.current.foldWaitForMedia || !quietChangeGate.isScreenOn() ||
            !quietChangeGate.isMediaPlaying()

    fun start() {
        if (running) return
        running = true
        foldState.refresh()
        seenTransitionAt = foldState.lastTransitionAt
        // Anything that happened while we weren't watching is checked out of sight, at the next
        // screen-off, instead of re-applying a wallpaper while the app is being opened.
        verifyWhenQuiet = true
        synchronized(jobLock) {
            sessionJob?.cancel()
            sessionJob = scope.launch {
                // Look or album edits change what the other screen should show.
                settingsRepository.getScheduleSettingsFlow()
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { schedulePrerender() }
            }
        }
        schedulePrerender()
        Log.d(TAG, "Started, screen=${foldState.activePanel()}")
    }

    fun stop() {
        running = false
        synchronized(jobLock) {
            sessionJob?.cancel()
            delayJob?.cancel()
            prerenderJob?.cancel()
        }
        cache = null
    }

    /** The default display changed: maybe a fold or unfold. Main thread. */
    fun onDisplayChanged() {
        foldState.refresh()
        val transitionAt = foldState.lastTransitionAt
        if (transitionAt == seenTransitionAt) return
        seenTransitionAt = transitionAt
        tracker.onTransition(transitionAt)
        synchronized(jobLock) { prerenderJob?.cancel() }
        if (syncOn()) {
            syncPending = true
            requestCheck(FoldSyncPolicy.SETTLE_MS)
        }
    }

    /** The screen turned off: run deferred changes and any waiting check, out of sight. */
    fun onScreenOff() = requestCheck(SCREEN_OFF_DELAY_MS)

    fun onPlaybackChanged() {
        if (quietChangeGate.isMediaPlaying()) return
        if (heldForMedia || quietChangeGate.hasPendingWork()) requestCheck(0L)
    }

    /**
     * Something wrote a wallpaper. If it was another app, the screen in use no longer shows
     * Paperize's wallpaper; it gets it back the next time that screen is checked (after a fold).
     */
    fun onWallpaperChanged() {
        val at = SystemClock.elapsedRealtime()
        val panel = foldState.activePanel()
        scope.launch {
            delay(BROADCAST_CLASSIFY_DELAY_MS)
            if (panel != null && panel == foldState.activePanel() && tracker.isExternalChange(at, panel)) {
                Log.i(TAG, "Wallpaper on the $panel screen was changed by another app")
                tracker.forget(panel)
            }
            schedulePrerender()
        }
    }

    /** User asked to resync: forget everything and re-apply to the screen in use now. */
    fun resyncNow() {
        tracker.forgetAll()
        cache = null
        forceNext = true
        syncPending = true
        requestCheck(0L)
    }

    /** Run a check after [delayMs]; a newer request replaces one that is still waiting. */
    private fun requestCheck(delayMs: Long) {
        synchronized(jobLock) {
            delayJob?.cancel()
            delayJob = scope.launch {
                delay(delayMs)
                checks.trySend(Unit)
            }
        }
    }

    private suspend fun runCheck() {
        awaitSettled()
        // Deferred scheduled changes first: they change what both screens should show.
        quietChangeGate.evaluate()
        val verify = verifyWhenQuiet && !quietChangeGate.isScreenOn()
        if (syncPending || verify || (heldForMedia && mediaAllows())) syncActivePanel()
        schedulePrerender()
    }

    private suspend fun awaitSettled() {
        while (!foldState.isSettled()) {
            val waited = SystemClock.elapsedRealtime() - foldState.lastTransitionAt
            delay((FoldSyncPolicy.SETTLE_MS - waited).coerceAtLeast(MIN_POLL_MS))
            foldState.refresh()
        }
    }

    private suspend fun syncActivePanel() {
        if (!syncOn()) {
            syncPending = false
            verifyWhenQuiet = false
            heldForMedia = false
            forceNext = false
            return
        }
        // Don't recolor under a playing video; catch up when it stops or the screen turns off.
        if (!forceNext && !mediaAllows()) {
            heldForMedia = true
            return
        }
        syncPending = false
        verifyWhenQuiet = false
        heldForMedia = false
        forceNext = false
        try {
            wallpaperChangeLock.mutex.withLock {
                val panel = foldState.refresh() ?: return
                if (!foldState.isSettled()) {
                    // Folded again while waiting for the lock: check once that settles.
                    syncPending = true
                    requestCheck(FoldSyncPolicy.SETTLE_MS)
                    return
                }
                if (settingsRepository.getWallpaperMode() != WallpaperMode.STATIC) return
                val settings = settingsRepository.getScheduleSettings()
                if (!settings.enableChanger || !settings.hasRequiredAlbums(WallpaperMode.STATIC)) return

                val expected = tracker.expectedSignatures(panel, settings)
                val needed = tracker.slotsNeedingWrite(panel, expected)
                if (needed.isEmpty()) {
                    Log.d(TAG, "$panel screen already current")
                    return
                }
                val cached = cache
                if (cached != null && cached.panel == panel && cached.signatures == expected) {
                    // Fast path: rendered and encoded while this screen was off.
                    val entries = cached.entries.filter { (target, _) -> target.staticSlots().any { it in needed } }
                    wallpaperController.applyEncoded(entries)
                    Log.d(TAG, "Applied prepared $needed to $panel screen")
                } else {
                    for (target in FoldSyncPolicy.writeTargets(needed)) {
                        wallpaperController.reapply(target, settings, panel = panel, advanceIfMissing = false)
                    }
                    Log.d(TAG, "Rendered $needed for $panel screen")
                }
                if (cached?.panel == panel) cache = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Try again at the next check (screen-off, playback stop or fold).
            syncPending = true
            Log.e(TAG, "Fold sync failed", e)
        }
    }

    /**
     * While one screen is in use, prepare the other one's wallpaper (if it's out of date) so the
     * next fold or unfold is near-instant.
     */
    private fun schedulePrerender() {
        if (!running) return
        synchronized(jobLock) {
            prerenderJob?.cancel()
            prerenderJob = scope.launch {
                delay(PRERENDER_DELAY_MS)
                prerender()
            }
        }
    }

    private suspend fun prerender() {
        if (!syncOn() || !foldPreferences.current.prerenderCover) {
            cache = null
            return
        }
        try {
            val active = foldState.refresh() ?: return
            if (!foldState.isSettled()) return
            val target = active.other
            if (!foldState.knowsSize(target)) return
            if (settingsRepository.getWallpaperMode() != WallpaperMode.STATIC) return
            val settings = settingsRepository.getScheduleSettings()
            if (!settings.enableChanger || !settings.hasRequiredAlbums(WallpaperMode.STATIC)) return
            val expected = tracker.expectedSignatures(target, settings)
            if (expected.isEmpty()) return
            val needed = tracker.slotsNeedingWrite(target, expected)
            if (needed.isEmpty()) {
                cache = null
                return
            }
            val existing = cache
            if (existing != null && existing.panel == target && existing.signatures == expected) return

            val entries = FoldSyncPolicy.writeTargets(expected.keys).flatMap { screen ->
                wallpaperController.renderEncoded(screen, settings, target)
            }
            // Only keep it if it covers every slot and nothing changed while rendering.
            val covered = entries.flatMap { (screen, _) -> screen.staticSlots() }.toSet()
            val after = tracker.expectedSignatures(target, settingsRepository.getScheduleSettings())
            if (covered.containsAll(expected.keys) && after == expected && foldState.activePanel() == active) {
                cache = PanelCache(target, expected, entries)
                Log.d(TAG, "Prepared $target screen (${entries.sumOf { it.second.size } / 1024} KB)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Preparing the other screen failed", e)
        }
    }

    private companion object {
        const val TAG = "FoldCoordinator"
        const val SCREEN_OFF_DELAY_MS = 400L
        const val PRERENDER_DELAY_MS = 2_000L
        const val BROADCAST_CLASSIFY_DELAY_MS = 1_000L
        const val MIN_POLL_MS = 50L
    }
}
