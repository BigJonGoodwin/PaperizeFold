package com.anthonyla.paperize.domain.model

import com.anthonyla.paperize.core.FoldPanel
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.core.constants.Constants

data class ScheduleSettings(
    val enableChanger: Boolean = false,
    val separateSchedules: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val homeEnabled: Boolean = false,
    val lockEnabled: Boolean = false,
    val homeAlbumId: String? = null,
    val lockAlbumId: String? = null,
    val homeIntervalMinutes: Int = Constants.DEFAULT_INTERVAL_MINUTES,
    val lockIntervalMinutes: Int = Constants.DEFAULT_INTERVAL_MINUTES,
    val liveIntervalMinutes: Int = Constants.DEFAULT_INTERVAL_MINUTES,
    val homeScalingType: ScalingType = ScalingType.FILL,
    val lockScalingType: ScalingType = ScalingType.FILL,
    val homeScrollingEnabled: Boolean = false,
    val homeEffects: WallpaperEffects = WallpaperEffects(),
    val lockEffects: WallpaperEffects = WallpaperEffects(),
    val liveAlbumId: String? = null,
    val liveScalingType: ScalingType = ScalingType.FILL,
    val liveEffects: WallpaperEffects = WallpaperEffects(),
    val adaptiveBrightness: Boolean = false,
    // PaperizeFold: an optional separate look for the cover screen of a foldable.
    val separateCoverSettings: Boolean = false,
    val coverScalingType: ScalingType = ScalingType.FILL,
    val coverHomeEffects: WallpaperEffects = WallpaperEffects(),
    val coverLockEffects: WallpaperEffects = WallpaperEffects()
) {
    /** True when the cover screen uses its own scaling and effects. */
    private fun usesCoverLook(panel: FoldPanel) = panel == FoldPanel.COVER && separateCoverSettings

    /** Effects for [screen] as shown on [panel]. LIVE always uses the live settings. */
    fun effectsFor(screen: ScreenType, panel: FoldPanel = FoldPanel.MAIN): WallpaperEffects = when {
        screen == ScreenType.LIVE -> liveEffects
        usesCoverLook(panel) -> if (screen == ScreenType.LOCK) coverLockEffects else coverHomeEffects
        screen == ScreenType.LOCK -> lockEffects
        else -> homeEffects
    }

    /** Scaling for [screen] as shown on [panel]. LIVE always uses the live settings. */
    fun scalingFor(screen: ScreenType, panel: FoldPanel = FoldPanel.MAIN): ScalingType = when {
        screen == ScreenType.LIVE -> liveScalingType
        usesCoverLook(panel) -> coverScalingType
        screen == ScreenType.LOCK -> lockScalingType
        else -> homeScalingType
    }

    /** Home and lock look identical on [panel], so one wallpaper write can cover both. */
    fun samePresentation(panel: FoldPanel = FoldPanel.MAIN): Boolean =
        effectsFor(ScreenType.HOME, panel) == effectsFor(ScreenType.LOCK, panel) &&
            scalingFor(ScreenType.HOME, panel) == scalingFor(ScreenType.LOCK, panel) &&
            !homeScrollingEnabled

    /**
     * Everything that changes how the static [screen] wallpaper looks on [panel], as a string that
     * stays the same across app restarts (it is stored to remember what each screen shows).
     */
    fun lookKey(screen: ScreenType, panel: FoldPanel): String {
        val slot = if (screen == ScreenType.LOCK) ScreenType.LOCK else ScreenType.HOME
        val effects = effectsFor(slot, panel)
        return listOf(
            slot.name,
            scalingFor(slot, panel).name,
            effects.enableDarken, effects.darkenPercentage,
            effects.enableBlur, effects.blurPercentage,
            effects.enableVignette, effects.vignettePercentage,
            effects.enableGrayscale, effects.grayscalePercentage,
            slot == ScreenType.HOME && homeScrollingEnabled,
            adaptiveBrightness
        ).joinToString(",")
    }

    /** How static wallpapers look on [panel], covering both home and lock. */
    fun lookKey(panel: FoldPanel): String =
        lookKey(ScreenType.HOME, panel) + ";" + lookKey(ScreenType.LOCK, panel)

    /** Copies the main screen look into the cover settings (used when they are first split). */
    fun withCoverLookFromMain(): ScheduleSettings = copy(
        coverScalingType = homeScalingType,
        coverHomeEffects = homeEffects,
        coverLockEffects = lockEffects
    )

    /** The cover look has never been edited (all defaults). */
    val hasDefaultCoverLook: Boolean
        get() = coverScalingType == ScalingType.FILL &&
            coverHomeEffects == WallpaperEffects() && coverLockEffects == WallpaperEffects()

    val effectiveLockIntervalMinutes: Int
        get() = if (homeEnabled && lockEnabled && separateSchedules) lockIntervalMinutes else homeIntervalMinutes

    fun hasRequiredAlbums(mode: WallpaperMode): Boolean = when (mode) {
        WallpaperMode.LIVE -> liveAlbumId != null
        WallpaperMode.STATIC -> (homeEnabled || lockEnabled) &&
            (!homeEnabled || homeAlbumId != null) && (!lockEnabled || lockAlbumId != null)
    }

    fun activeScreens(mode: WallpaperMode): Set<ScreenType> {
        if (mode == WallpaperMode.LIVE) return if (liveAlbumId != null) setOf(ScreenType.LIVE) else emptySet()
        val home = homeEnabled && homeAlbumId != null
        val lock = lockEnabled && lockAlbumId != null
        if (home && lock && homeAlbumId == lockAlbumId && !separateSchedules) return setOf(ScreenType.BOTH)
        return buildSet {
            if (home) add(ScreenType.HOME)
            if (lock) add(ScreenType.LOCK)
        }
    }

    fun intervalMinutes(screen: ScreenType): Int = when (screen) {
        ScreenType.HOME, ScreenType.BOTH -> homeIntervalMinutes
        ScreenType.LOCK -> effectiveLockIntervalMinutes
        ScreenType.LIVE -> liveIntervalMinutes
    }

    fun validate(): ScheduleSettings = copy(
        homeIntervalMinutes = homeIntervalMinutes.coerceAtLeast(Constants.MIN_INTERVAL_MINUTES),
        lockIntervalMinutes = lockIntervalMinutes.coerceAtLeast(Constants.MIN_INTERVAL_MINUTES),
        liveIntervalMinutes = liveIntervalMinutes.coerceAtLeast(Constants.MIN_LIVE_INTERVAL_MINUTES),
        homeEffects = homeEffects.validate(),
        lockEffects = lockEffects.validate(),
        liveEffects = liveEffects.validate(),
        coverHomeEffects = coverHomeEffects.validate(),
        coverLockEffects = coverLockEffects.validate()
    )

    fun hasSchedulingChanges(other: ScheduleSettings): Boolean {
        return enableChanger != other.enableChanger ||
               homeAlbumId != other.homeAlbumId ||
               lockAlbumId != other.lockAlbumId ||
               liveAlbumId != other.liveAlbumId ||
               homeEnabled != other.homeEnabled ||
               lockEnabled != other.lockEnabled ||
               homeIntervalMinutes != other.homeIntervalMinutes ||
               lockIntervalMinutes != other.lockIntervalMinutes ||
               separateSchedules != other.separateSchedules ||
               liveIntervalMinutes != other.liveIntervalMinutes
    }

    /** Display changes reapply the current wallpaper without rescheduling periodic work. */
    fun hasDisplayChanges(other: ScheduleSettings): Boolean {
        return homeScalingType != other.homeScalingType ||
               lockScalingType != other.lockScalingType ||
               homeScrollingEnabled != other.homeScrollingEnabled ||
               homeEffects != other.homeEffects ||
               lockEffects != other.lockEffects ||
               liveEffects != other.liveEffects ||
               liveScalingType != other.liveScalingType ||
               adaptiveBrightness != other.adaptiveBrightness ||
               separateCoverSettings != other.separateCoverSettings ||
               coverScalingType != other.coverScalingType ||
               coverHomeEffects != other.coverHomeEffects ||
               coverLockEffects != other.coverLockEffects
    }

}

/**
 * WorkManager cannot run periodic jobs more often than every 15 minutes. Short live-wallpaper
 * intervals are therefore driven by the visible wallpaper engine and stop when it is hidden.
 */
fun usesVisibleLiveTimer(intervalMinutes: Int): Boolean =
    intervalMinutes in Constants.MIN_LIVE_INTERVAL_MINUTES until Constants.MIN_INTERVAL_MINUTES
