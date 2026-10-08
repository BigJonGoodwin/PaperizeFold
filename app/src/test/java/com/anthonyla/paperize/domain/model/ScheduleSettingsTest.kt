package com.anthonyla.paperize.domain.model

import com.anthonyla.paperize.core.FoldPanel
import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.core.ScreenType
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleSettingsTest {
    @Test
    fun `validation applies distinct static and live minimums and validates all screens`() {
        val effects = WallpaperEffects(darkenPercentage = 150, blurPercentage = -10)
        val settings = ScheduleSettings(
            homeIntervalMinutes = 5,
            lockIntervalMinutes = 120,
            liveIntervalMinutes = 0,
            homeEffects = effects,
            lockEffects = effects,
            liveEffects = effects
        )
        val validEffects = effects.copy(darkenPercentage = 100, blurPercentage = 0)
        assertEquals(
            settings.copy(
                homeIntervalMinutes = 15,
                liveIntervalMinutes = 1,
                homeEffects = validEffects,
                lockEffects = validEffects,
                liveEffects = validEffects
            ),
            settings.validate()
        )
    }

    @Test
    fun `visible live timer is used only below WorkManager minimum`() {
        assertFalse(usesVisibleLiveTimer(0))
        assertTrue(usesVisibleLiveTimer(1))
        assertTrue(usesVisibleLiveTimer(14))
        assertFalse(usesVisibleLiveTimer(15))
    }

    @Test
    fun `schedule edits require rescheduling without reapplying display effects`() {
        val current = ScheduleSettings()
        listOf(
            current.copy(enableChanger = true),
            current.copy(homeAlbumId = "home"),
            current.copy(lockAlbumId = "lock"),
            current.copy(liveAlbumId = "live"),
            current.copy(homeEnabled = true),
            current.copy(lockEnabled = true),
            current.copy(homeIntervalMinutes = 120),
            current.copy(lockIntervalMinutes = 120),
            current.copy(liveIntervalMinutes = 120),
            current.copy(separateSchedules = true)
        ).forEach { edited ->
            assertTrue("Scheduling: $edited", current.hasSchedulingChanges(edited))
            assertFalse("Display: $edited", current.hasDisplayChanges(edited))
        }
    }

    @Test
    fun `display edits require reapplication without resetting schedules`() {
        val current = ScheduleSettings()
        val effects = WallpaperEffects(enableBlur = true)
        listOf(
            current.copy(homeScalingType = ScalingType.FIT),
            current.copy(lockScalingType = ScalingType.STRETCH),
            current.copy(liveScalingType = ScalingType.NONE),
            current.copy(homeScrollingEnabled = true),
            current.copy(homeEffects = effects),
            current.copy(lockEffects = effects),
            current.copy(liveEffects = effects),
            current.copy(adaptiveBrightness = true)
        ).forEach { edited ->
            assertTrue("Display: $edited", current.hasDisplayChanges(edited))
            assertFalse("Scheduling: $edited", current.hasSchedulingChanges(edited))
        }
        assertFalse(current.hasSchedulingChanges(current))
        assertFalse(current.hasDisplayChanges(current))
    }

    @Test
    fun `cover screen uses its own look only when separated`() {
        val coverEffects = WallpaperEffects(enableBlur = true, blurPercentage = 40)
        val shared = ScheduleSettings(
            homeEffects = WallpaperEffects(enableDarken = true, darkenPercentage = 30),
            coverHomeEffects = coverEffects,
            coverScalingType = ScalingType.FIT
        )
        assertEquals(shared.homeEffects, shared.effectsFor(ScreenType.HOME, FoldPanel.COVER))
        assertEquals(ScalingType.FILL, shared.scalingFor(ScreenType.HOME, FoldPanel.COVER))
        assertEquals(shared.lookKey(FoldPanel.MAIN), shared.lookKey(FoldPanel.COVER))

        val separate = shared.copy(separateCoverSettings = true)
        assertEquals(coverEffects, separate.effectsFor(ScreenType.HOME, FoldPanel.COVER))
        assertEquals(coverEffects, separate.effectsFor(ScreenType.BOTH, FoldPanel.COVER))
        assertEquals(ScalingType.FIT, separate.scalingFor(ScreenType.LOCK, FoldPanel.COVER))
        assertEquals(separate.homeEffects, separate.effectsFor(ScreenType.HOME, FoldPanel.MAIN))
        assertNotEquals(separate.lookKey(FoldPanel.MAIN), separate.lookKey(FoldPanel.COVER))
        // Editing the cover leaves the main screen's look untouched.
        assertEquals(shared.lookKey(FoldPanel.MAIN), separate.lookKey(FoldPanel.MAIN))
    }

    @Test
    fun `look keys are stable and track every visible setting`() {
        val settings = ScheduleSettings()
        assertEquals(ScheduleSettings().lookKey(FoldPanel.MAIN), settings.lookKey(FoldPanel.MAIN))
        listOf(
            settings.copy(homeScalingType = ScalingType.FIT),
            settings.copy(lockEffects = WallpaperEffects(enableGrayscale = true, grayscalePercentage = 10)),
            settings.copy(homeScrollingEnabled = true),
            settings.copy(adaptiveBrightness = true),
            settings.copy(homeEffects = WallpaperEffects(enableVignette = true, vignettePercentage = 1))
        ).forEach { edited ->
            assertNotEquals("Look: $edited", settings.lookKey(FoldPanel.MAIN), edited.lookKey(FoldPanel.MAIN))
        }
    }

    @Test
    fun `cover look edits count as display changes`() {
        val current = ScheduleSettings()
        listOf(
            current.copy(separateCoverSettings = true),
            current.copy(coverScalingType = ScalingType.FIT),
            current.copy(coverHomeEffects = WallpaperEffects(enableBlur = true)),
            current.copy(coverLockEffects = WallpaperEffects(enableBlur = true))
        ).forEach { edited ->
            assertTrue("Display: $edited", current.hasDisplayChanges(edited))
            assertFalse("Scheduling: $edited", current.hasSchedulingChanges(edited))
        }
    }

    @Test
    fun `splitting the cover starts from the main look`() {
        val settings = ScheduleSettings(
            homeScalingType = ScalingType.STRETCH,
            lockScalingType = ScalingType.STRETCH,
            homeEffects = WallpaperEffects(enableBlur = true, blurPercentage = 20),
            lockEffects = WallpaperEffects(enableDarken = true, darkenPercentage = 50)
        )
        assertTrue(settings.hasDefaultCoverLook)
        val split = settings.withCoverLookFromMain().copy(separateCoverSettings = true)
        assertFalse(split.hasDefaultCoverLook)
        assertEquals(settings.lookKey(FoldPanel.MAIN), split.lookKey(FoldPanel.COVER))
    }

    @Test
    fun `home and lock share one write only when they look the same on that screen`() {
        val settings = ScheduleSettings(separateCoverSettings = true, coverLockEffects = WallpaperEffects(enableBlur = true))
        assertTrue(settings.samePresentation(FoldPanel.MAIN))
        assertFalse(settings.samePresentation(FoldPanel.COVER))
        assertFalse(settings.copy(homeScrollingEnabled = true).samePresentation(FoldPanel.MAIN))
    }
}
