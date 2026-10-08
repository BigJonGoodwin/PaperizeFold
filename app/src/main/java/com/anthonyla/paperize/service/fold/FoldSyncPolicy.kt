package com.anthonyla.paperize.service.fold

import com.anthonyla.paperize.core.FoldPanel
import com.anthonyla.paperize.core.ScreenType
import kotlin.math.abs

/**
 * PaperizeFold: the rules that decide what each screen of a foldable shows. Kept free of Android
 * types so they can be unit tested.
 *
 * A static wallpaper only lands on the screen that is in use when it is written. So a write is
 * only credited to a screen when that screen was in use, unchanged, from a moment before the write
 * started until after it finished. Anything else (folding while a wallpaper is being written) is
 * treated as unknown, and both screens are checked again.
 */
internal object FoldSyncPolicy {
    /** Time for a fold or unfold to settle before a write can be credited to the new screen. */
    const val SETTLE_MS = 350L

    /** A fold this soon after a write may have raced it. */
    const val POST_WRITE_GUARD_MS = 500L

    /** Wallpaper-changed broadcasts this long after our own write are ours. */
    const val OWN_BROADCAST_WINDOW_MS = 5_000L

    /** Broadcasts this close to a fold are the system swapping screens, not a new wallpaper. */
    const val PANEL_SWAP_BROADCAST_WINDOW_MS = 3_000L

    /**
     * The screen a write landed on, or null when it can't be known for sure.
     *
     * @param panelAtStart screen in use when the write started (null: not a foldable)
     * @param panelAtEnd screen in use when it finished
     * @param startedAt elapsed time when the write started
     * @param lastTransitionAt elapsed time of the most recent fold or unfold (0 = none seen)
     */
    fun attributedPanel(
        panelAtStart: FoldPanel?,
        panelAtEnd: FoldPanel?,
        startedAt: Long,
        lastTransitionAt: Long
    ): FoldPanel? {
        if (panelAtStart == null || panelAtStart != panelAtEnd) return null
        if (lastTransitionAt != 0L && lastTransitionAt > startedAt - SETTLE_MS) return null
        return panelAtStart
    }

    /** True when a fold at [transitionAt] came so soon after a write ending at [writeEndedAt]. */
    fun foldRacedWrite(transitionAt: Long, writeEndedAt: Long): Boolean =
        writeEndedAt != 0L && transitionAt - writeEndedAt in 0..POST_WRITE_GUARD_MS

    /**
     * Whether a wallpaper-changed broadcast received at [broadcastAt] came from another app.
     * Our own writes and the system swapping screens on fold are not external.
     */
    fun isExternalChange(
        broadcastAt: Long,
        writesInFlight: Boolean,
        lastWriteStartedAt: Long,
        lastWriteEndedAt: Long,
        lastTransitionAt: Long
    ): Boolean {
        if (writesInFlight) return false
        if (lastWriteStartedAt != 0L && lastWriteStartedAt >= broadcastAt - OWN_BROADCAST_WINDOW_MS) return false
        if (lastWriteEndedAt != 0L && lastWriteEndedAt >= broadcastAt - OWN_BROADCAST_WINDOW_MS) return false
        if (lastTransitionAt != 0L && abs(lastTransitionAt - broadcastAt) <= PANEL_SWAP_BROADCAST_WINDOW_MS) return false
        return true
    }

    /** What a screen slot (home or lock) should show: the current image and how it looks. */
    fun slotSignature(wallpaperId: String, lookKey: String): String = "$wallpaperId|$lookKey"

    /** Slots whose recorded signature differs from what they should show now. */
    fun slotsNeedingWrite(
        expected: Map<ScreenType, String>,
        recorded: (ScreenType) -> String?
    ): Set<ScreenType> = expected.filter { (slot, signature) -> recorded(slot) != signature }.keys

    /** Fewest writes that cover [slots]. BOTH may still split into two writes if needed. */
    fun writeTargets(slots: Set<ScreenType>): List<ScreenType> = when {
        ScreenType.HOME in slots && ScreenType.LOCK in slots -> listOf(ScreenType.BOTH)
        ScreenType.HOME in slots -> listOf(ScreenType.HOME)
        ScreenType.LOCK in slots -> listOf(ScreenType.LOCK)
        else -> emptyList()
    }
}
