package com.anthonyla.paperize.service.fold

import android.app.WallpaperManager
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
    private val wallpaperRepository: WallpaperRepository,
    private val wallpaperManager: WallpaperManager
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
     * Call after the write finished (also when it failed). [written] maps each slot Android
     * accepted to the image written there; null means "the slot's current image". Must not be
     * cancelled.
     */
    suspend fun end(ticket: Ticket?, written: Map<ScreenType, String?>) {
        if (ticket == null) return
        var credited = false
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
            val settings = settingsRepository.getScheduleSettings()
            val currentIds = currentIds(settings)
            val systemIds = readSystemIds()
            prefs.edit {
                for ((slot, writtenId) in written) {
                    val currentId = currentIds[slot]
                    // A specific image from outside the slot's album isn't "current"; leave the
                    // slot unknown so it gets the current image the next time it's checked.
                    if (currentId != null && (writtenId == null || writtenId == currentId)) {
                        putString(key(panel, slot), FoldSyncPolicy.slotSignature(currentId, settings.lookKey(slot, panel)))
                    } else {
                        remove(key(panel, slot))
                    }
                }
                if (systemIds != null) putString(idsKey(panel), systemIds) else remove(idsKey(panel))
            }
            credited = true
            Log.d(TAG, "Recorded ${written.keys} on $panel")
        } catch (e: CancellationException) {
            forgetAll()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not record a wallpaper write; both screens will be re-checked", e)
            forgetAll()
        } finally {
            lastWriteEndedAt = SystemClock.elapsedRealtime()
            writesInFlight.decrementAndGet()
            // A fold noticed while the record was being written raced the write.
            if (credited && foldState.lastTransitionAt > ticket.startedAt - FoldSyncPolicy.SETTLE_MS) {
                Log.i(TAG, "Folded while a wallpaper write was being recorded; both screens will be re-checked")
                forgetAll()
                onUncertainWrite?.invoke()
            }
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

    /**
     * Whether a wallpaper-changed broadcast received at [broadcastAt] came from another app, and
     * so changed [panel]. Our own writes and the system swapping screens are not external, and
     * neither is a broadcast after which the system still reports the wallpaper we last wrote.
     */
    fun isExternalChange(broadcastAt: Long, panel: FoldPanel): Boolean {
        val byTiming = FoldSyncPolicy.isExternalChange(
            broadcastAt = broadcastAt,
            writesInFlight = writesInFlight.get() > 0,
            lastWriteStartedAt = lastWriteStartedAt,
            lastWriteEndedAt = lastWriteEndedAt,
            lastTransitionAt = foldState.lastTransitionAt
        )
        if (!byTiming) return false
        val known = prefs.getString(idsKey(panel), null) ?: return true
        return readSystemIds() != known
    }

    /** What each active slot on [panel] should show now. Empty when nothing is managed. */
    suspend fun expectedSignatures(panel: FoldPanel, settings: ScheduleSettings): Map<ScreenType, String> =
        currentIds(settings).mapValues { (slot, id) -> FoldSyncPolicy.slotSignature(id, settings.lookKey(slot, panel)) }

    /** The current image of each active static slot (from that slot's album). */
    private suspend fun currentIds(settings: ScheduleSettings): Map<ScreenType, String> {
        if (settingsRepository.getWallpaperMode() != WallpaperMode.STATIC) return emptyMap()
        val screens = settings.activeScreens(WallpaperMode.STATIC)
        val both = ScreenType.BOTH in screens
        val ids = LinkedHashMap<ScreenType, String>()
        if (both || ScreenType.HOME in screens) {
            settings.homeAlbumId
                ?.let { wallpaperRepository.getCurrentWallpaper(it, ScreenType.HOME)?.id }
                ?.let { ids[ScreenType.HOME] = it }
        }
        if (both || ScreenType.LOCK in screens) {
            settings.lockAlbumId
                ?.let { wallpaperRepository.getCurrentWallpaper(it, ScreenType.LOCK)?.id }
                ?.let { ids[ScreenType.LOCK] = it }
        }
        return ids
    }

    /** Slots of [panel] that don't show what they should. */
    fun slotsNeedingWrite(panel: FoldPanel, expected: Map<ScreenType, String>): Set<ScreenType> =
        FoldSyncPolicy.slotsNeedingWrite(expected) { slot -> prefs.getString(key(panel, slot), null) }

    /** Forget what [panel] shows; it is re-applied the next time it's checked. */
    fun forget(panel: FoldPanel) {
        prefs.edit {
            remove(key(panel, ScreenType.HOME))
            remove(key(panel, ScreenType.LOCK))
            remove(idsKey(panel))
        }
    }

    /** Forget what both screens show; each is re-applied the next time it's checked. */
    fun forgetAll() {
        FoldPanel.entries.forEach(::forget)
    }

    /** The system's wallpaper ids for home and lock, used to recognize our own last write. */
    private fun readSystemIds(): String? = try {
        "${wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)}," +
            "${wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)}"
    } catch (e: Exception) {
        null
    }

    private fun key(panel: FoldPanel, slot: ScreenType) = "shows_${panel.name}_${slot.name}"

    private fun idsKey(panel: FoldPanel) = "system_ids_${panel.name}"

    private companion object {
        const val TAG = "PanelTracker"
    }
}
