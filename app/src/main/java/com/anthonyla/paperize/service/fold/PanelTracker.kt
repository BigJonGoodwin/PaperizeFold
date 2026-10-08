package com.anthonyla.paperize.service.fold

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.anthonyla.paperize.core.FoldPanel
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * PaperizeFold: remembers what each screen of a foldable shows, so folding only re-applies a
 * wallpaper when that screen is actually out of date.
 *
 * Every wallpaper write goes through WallpaperController, which reports it here. A write is
 * credited to a screen only when that screen was in use for the whole write. If the phone was
 * folded or unfolded around it, nobody can tell which screen got it, so both screens are marked
 * unknown and re-checked. The records are kept on disk, so they survive the app being closed.
 *
 * Each screen has two slots (home and lock). A record is "current image id | how it looks".
 */
@Singleton
class PanelTracker @Inject constructor(
    @param:ApplicationContext context: Context,
    private val foldState: FoldState,
    private val settingsRepository: SettingsRepository,
    private val wallpaperRepository: WallpaperRepository
) {
    /** A write in progress: the screen in use when it began. */
    class Ticket internal constructor(val panel: FoldPanel?, internal val startedAt: Long)

    private val prefs = context.getSharedPreferences(FoldState.PREFS_NAME, Context.MODE_PRIVATE)
    private val writesInFlight = AtomicInteger()
    @Volatile private var lastWriteStartedAt = 0L
    @Volatile private var lastWriteEndedAt = 0L

    /** Called when a write may have landed on the other screen. Set by FoldCoordinator. */
    @Volatile var onUncertainWrite: (() -> Unit)? = null

    /** Call right before writing a wallpaper. Returns null on regular phones. */
    fun begin(): Ticket? {
        if (!foldState.isFoldable) return null
        val now = SystemClock.elapsedRealtime()
        writesInFlight.incrementAndGet()
        lastWriteStartedAt = now
        return Ticket(foldState.activePanel(), now)
    }

    /**
     * Call after the write finished (also when it failed), with the slots that were actually
     * written. Must not be cancelled.
     */
    suspend fun end(ticket: Ticket?, written: Set<ScreenType>) {
        if (ticket == null) return
        try {
            if (written.isEmpty()) return
            val panel = FoldSyncPolicy.attributedPanel(
                panelAtStart = ticket.panel,
                panelAtEnd = foldState.activePanel(),
                startedAt = ticket.startedAt,
                lastTransitionAt = foldState.lastTransitionAt
            )
            if (panel == null) {
                Log.i(TAG, "Wallpaper was written while folding or unfolding; both screens will be re-checked")
                forgetAll()
                onUncertainWrite?.invoke()
                return
            }
            val expected = expectedSignatures(panel, settingsRepository.getScheduleSettings())
            prefs.edit {
                for (slot in written) {
                    val signature = expected[slot]
                    if (signature != null) putString(key(panel, slot), signature) else remove(key(panel, slot))
                }
            }
            Log.d(TAG, "Recorded $written on $panel")
        } catch (e: CancellationException) {
            forgetAll()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not record a wallpaper write; both screens will be re-checked", e)
            forgetAll()
        } finally {
            lastWriteEndedAt = SystemClock.elapsedRealtime()
            writesInFlight.decrementAndGet()
        }
    }

    /** A fold right after a write may have raced it, so forget what both screens show. */
    fun onTransition(transitionAt: Long) {
        if (writesInFlight.get() > 0) return // end() sees the fold itself
        if (FoldSyncPolicy.foldRacedWrite(transitionAt, lastWriteEndedAt)) {
            Log.i(TAG, "Folded right after a wallpaper write; both screens will be re-checked")
            forgetAll()
        }
    }

    /** Whether a wallpaper-changed broadcast received at [broadcastAt] came from another app. */
    fun isExternalChange(broadcastAt: Long): Boolean = FoldSyncPolicy.isExternalChange(
        broadcastAt = broadcastAt,
        writesInFlight = writesInFlight.get() > 0,
        lastWriteStartedAt = lastWriteStartedAt,
        lastWriteEndedAt = lastWriteEndedAt,
        lastTransitionAt = foldState.lastTransitionAt
    )

    /** What each active slot on [panel] should show now. Empty when nothing is managed. */
    suspend fun expectedSignatures(panel: FoldPanel, settings: ScheduleSettings): Map<ScreenType, String> {
        if (settingsRepository.getWallpaperMode() != WallpaperMode.STATIC) return emptyMap()
        val screens = settings.activeScreens(WallpaperMode.STATIC)
        val both = ScreenType.BOTH in screens
        val expected = LinkedHashMap<ScreenType, String>()
        if (both || ScreenType.HOME in screens) {
            settings.homeAlbumId
                ?.let { wallpaperRepository.getCurrentWallpaper(it, ScreenType.HOME)?.id }
                ?.let { expected[ScreenType.HOME] = FoldSyncPolicy.slotSignature(it, settings.lookKey(ScreenType.HOME, panel)) }
        }
        if (both || ScreenType.LOCK in screens) {
            settings.lockAlbumId
                ?.let { wallpaperRepository.getCurrentWallpaper(it, ScreenType.LOCK)?.id }
                ?.let { expected[ScreenType.LOCK] = FoldSyncPolicy.slotSignature(it, settings.lookKey(ScreenType.LOCK, panel)) }
        }
        return expected
    }

    /** Slots of [panel] that don't show what they should. */
    fun slotsNeedingWrite(panel: FoldPanel, expected: Map<ScreenType, String>): Set<ScreenType> =
        FoldSyncPolicy.slotsNeedingWrite(expected) { slot -> prefs.getString(key(panel, slot), null) }

    /** Forget what both screens show; each is re-applied the next time it's checked. */
    fun forgetAll() {
        prefs.edit {
            for (panel in FoldPanel.entries) {
                remove(key(panel, ScreenType.HOME))
                remove(key(panel, ScreenType.LOCK))
            }
        }
    }

    private fun key(panel: FoldPanel, slot: ScreenType) = "shows_${panel.name}_${slot.name}"

    private companion object {
        const val TAG = "PanelTracker"
    }
}
