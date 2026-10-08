package com.anthonyla.paperize.service.fold

import com.anthonyla.paperize.core.FoldPanel
import com.anthonyla.paperize.core.ScreenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldSyncPolicyTest {

    @Test fun `write on a settled screen is credited to it`() {
        assertEquals(
            FoldPanel.COVER,
            FoldSyncPolicy.attributedPanel(FoldPanel.COVER, FoldPanel.COVER, startedAt = 10_000, lastTransitionAt = 5_000)
        )
        assertEquals(
            FoldPanel.MAIN,
            FoldSyncPolicy.attributedPanel(FoldPanel.MAIN, FoldPanel.MAIN, startedAt = 10_000, lastTransitionAt = 0)
        )
    }

    @Test fun `write while folding is not credited to either screen`() {
        // Screen changed during the write.
        assertNull(FoldSyncPolicy.attributedPanel(FoldPanel.MAIN, FoldPanel.COVER, 10_000, 9_000))
        // Fold happened during the write and the panel reads the same at both ends.
        assertNull(FoldSyncPolicy.attributedPanel(FoldPanel.COVER, FoldPanel.COVER, 10_000, 10_200))
        // Write started before the fold had settled.
        assertNull(
            FoldSyncPolicy.attributedPanel(FoldPanel.COVER, FoldPanel.COVER, 10_000, 10_000 - FoldSyncPolicy.SETTLE_MS + 1)
        )
        // Not a foldable.
        assertNull(FoldSyncPolicy.attributedPanel(null, null, 10_000, 0))
    }

    @Test fun `write starting right after the settle time is credited`() {
        assertEquals(
            FoldPanel.COVER,
            FoldSyncPolicy.attributedPanel(FoldPanel.COVER, FoldPanel.COVER, 10_000, 10_000 - FoldSyncPolicy.SETTLE_MS)
        )
    }

    @Test fun `fold right after a write is treated as a race`() {
        assertTrue(FoldSyncPolicy.foldRacedWrite(transitionAt = 10_300, writeEndedAt = 10_000))
        assertFalse(FoldSyncPolicy.foldRacedWrite(transitionAt = 10_000 + FoldSyncPolicy.POST_WRITE_GUARD_MS + 1, writeEndedAt = 10_000))
        assertFalse(FoldSyncPolicy.foldRacedWrite(transitionAt = 9_000, writeEndedAt = 10_000))
        assertFalse(FoldSyncPolicy.foldRacedWrite(transitionAt = 10_000, writeEndedAt = 0))
    }

    @Test fun `own writes and screen swaps are not external changes`() {
        assertFalse(FoldSyncPolicy.isExternalChange(50_000, writesInFlight = true, 0, 0, 0))
        assertFalse(FoldSyncPolicy.isExternalChange(50_000, false, lastWriteStartedAt = 46_000, lastWriteEndedAt = 0, lastTransitionAt = 0))
        assertFalse(FoldSyncPolicy.isExternalChange(50_000, false, lastWriteStartedAt = 40_000, lastWriteEndedAt = 47_000, lastTransitionAt = 0))
        // System swapping screens shortly before or after the broadcast.
        assertFalse(FoldSyncPolicy.isExternalChange(50_000, false, 0, 0, lastTransitionAt = 49_000))
        assertFalse(FoldSyncPolicy.isExternalChange(50_000, false, 0, 0, lastTransitionAt = 50_800))
    }

    @Test fun `another app writing the wallpaper is external`() {
        assertTrue(FoldSyncPolicy.isExternalChange(50_000, false, lastWriteStartedAt = 10_000, lastWriteEndedAt = 11_000, lastTransitionAt = 20_000))
        assertTrue(FoldSyncPolicy.isExternalChange(50_000, false, 0, 0, 0))
    }

    @Test fun `only out of date slots need writing`() {
        val expected = mapOf(ScreenType.HOME to "a|look", ScreenType.LOCK to "b|look")
        val recorded = mapOf(ScreenType.HOME to "a|look", ScreenType.LOCK to "old|look")
        assertEquals(setOf(ScreenType.LOCK), FoldSyncPolicy.slotsNeedingWrite(expected) { recorded[it] })
        assertEquals(setOf(ScreenType.HOME, ScreenType.LOCK), FoldSyncPolicy.slotsNeedingWrite(expected) { null })
        assertEquals(emptySet<ScreenType>(), FoldSyncPolicy.slotsNeedingWrite(expected) { expected[it] })
    }

    @Test fun `one write covers both slots when possible`() {
        assertEquals(listOf(ScreenType.BOTH), FoldSyncPolicy.writeTargets(setOf(ScreenType.HOME, ScreenType.LOCK)))
        assertEquals(listOf(ScreenType.HOME), FoldSyncPolicy.writeTargets(setOf(ScreenType.HOME)))
        assertEquals(listOf(ScreenType.LOCK), FoldSyncPolicy.writeTargets(setOf(ScreenType.LOCK)))
        assertEquals(emptyList<ScreenType>(), FoldSyncPolicy.writeTargets(emptySet()))
    }

    @Test fun `signature changes with the image and with the look`() {
        val base = FoldSyncPolicy.slotSignature("image", "look")
        assertTrue(base != FoldSyncPolicy.slotSignature("other", "look"))
        assertTrue(base != FoldSyncPolicy.slotSignature("image", "other"))
    }

    /**
     * The "one behind" bug: a scheduled change ran while the phone was being folded, so the old
     * code credited it to the wrong screen and later skipped syncing that screen. Now such a
     * write is never credited, so the screen is re-checked and gets the current wallpaper.
     */
    @Test fun `change during a fold leaves both screens to be re-checked`() {
        val recorded = mutableMapOf<Pair<FoldPanel, ScreenType>, String>()
        fun write(panel: FoldPanel?, slot: ScreenType, signature: String) {
            if (panel == null) recorded.clear() else recorded[panel to slot] = signature
        }
        // Both screens show image A.
        write(FoldPanel.MAIN, ScreenType.HOME, "A")
        write(FoldPanel.COVER, ScreenType.HOME, "A")
        // Scheduled change to B runs as the phone is folded (screen off from folding).
        val panel = FoldSyncPolicy.attributedPanel(FoldPanel.MAIN, FoldPanel.COVER, startedAt = 100_000, lastTransitionAt = 100_050)
        write(panel, ScreenType.HOME, "B")
        // The cover is checked after the fold settles: it must be written, not skipped.
        val needed = FoldSyncPolicy.slotsNeedingWrite(mapOf(ScreenType.HOME to "B")) { recorded[FoldPanel.COVER to it] }
        assertEquals(setOf(ScreenType.HOME), needed)
        // And so is the main screen the next time it's unfolded.
        val neededMain = FoldSyncPolicy.slotsNeedingWrite(mapOf(ScreenType.HOME to "B")) { recorded[FoldPanel.MAIN to it] }
        assertEquals(setOf(ScreenType.HOME), neededMain)
    }
}
