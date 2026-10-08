package com.anthonyla.paperize.service.fold

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Size
import android.view.Display
import androidx.core.content.edit
import com.anthonyla.paperize.core.FoldPanel
import com.anthonyla.paperize.core.util.getDeviceScreenSize
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the UI needs to draw both screens of a foldable. */
data class FoldInfo(
    val foldable: Boolean = false,
    val activePanel: FoldPanel? = null,
    val mainSize: Size? = null,
    val coverSize: Size? = null
) {
    private fun sizeOf(panel: FoldPanel) = if (panel == FoldPanel.MAIN) mainSize else coverSize

    /** Portrait width / height of [panel]; typical book-style foldable values until it's been seen. */
    fun aspectRatio(panel: FoldPanel): Float {
        val size = sizeOf(panel)?.takeIf { it.width > 0 && it.height > 0 }
            ?: return if (panel == FoldPanel.MAIN) DEFAULT_MAIN_ASPECT else DEFAULT_COVER_ASPECT
        return minOf(size.width, size.height).toFloat() / maxOf(size.width, size.height)
    }

    /** Portrait width in pixels the wallpaper is rendered at for [panel]. */
    fun renderWidth(panel: FoldPanel): Int {
        val size = sizeOf(panel)?.takeIf { it.width > 0 && it.height > 0 }
            ?: return if (panel == FoldPanel.MAIN) DEFAULT_MAIN_WIDTH else DEFAULT_COVER_WIDTH
        return minOf(size.width, size.height)
    }

    private companion object {
        // Galaxy Z Fold6 panels, used until the real sizes have been measured.
        const val DEFAULT_MAIN_WIDTH = 1856
        const val DEFAULT_COVER_WIDTH = 968
        const val DEFAULT_MAIN_ASPECT = 1856f / 2160f
        const val DEFAULT_COVER_ASPECT = 968f / 2376f
    }
}

/**
 * PaperizeFold: which screen of a foldable is in use, when that last changed, and how big each
 * screen is. Readable from any thread without the fold service running.
 *
 * Cover screens are tall and narrow (~2.4:1) while inner screens are nearly square (~1.2:1), so
 * the default display's shape tells them apart.
 */
@Singleton
class FoldState @Inject constructor(@param:ApplicationContext private val context: Context) {

    /** True on devices with a hinge sensor (foldables). */
    val isFoldable: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)

    private val displayManager: DisplayManager? = context.getSystemService(DisplayManager::class.java)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile private var panel: FoldPanel? = null

    /** Elapsed time of the most recent fold or unfold seen; 0 until one is seen. */
    @Volatile var lastTransitionAt: Long = 0L
        private set

    private val sizes = mutableMapOf<FoldPanel, Size>().apply {
        storedSize(FoldPanel.MAIN)?.let { put(FoldPanel.MAIN, it) }
        storedSize(FoldPanel.COVER)?.let { put(FoldPanel.COVER, it) }
    }

    private val _info = MutableStateFlow(
        FoldInfo(foldable = isFoldable, mainSize = sizes[FoldPanel.MAIN], coverSize = sizes[FoldPanel.COVER])
    )

    init {
        refresh()
    }
    val info: StateFlow<FoldInfo> = _info.asStateFlow()

    /** The screen in use right now, or null on regular phones (or if the display can't be read). */
    fun activePanel(): FoldPanel? = refresh()

    /** True when no fold or unfold happened within the last [FoldSyncPolicy.SETTLE_MS]. */
    fun isSettled(): Boolean =
        lastTransitionAt == 0L || SystemClock.elapsedRealtime() - lastTransitionAt >= FoldSyncPolicy.SETTLE_MS

    /** Re-reads the display, noting a fold or unfold if the screen in use changed. */
    fun refresh(): FoldPanel? {
        if (!isFoldable) return null
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return panel
        val real = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(real)
        if (real.x <= 0 || real.y <= 0) return panel
        val ratio = maxOf(real.x, real.y).toFloat() / minOf(real.x, real.y)
        val current = if (ratio >= FOLDED_ASPECT_THRESHOLD) FoldPanel.COVER else FoldPanel.MAIN
        var changed = false
        synchronized(lock) {
            val previous = panel
            if (previous != current) {
                panel = current
                changed = true
                if (previous != null) lastTransitionAt = SystemClock.elapsedRealtime()
            }
        }
        if (changed || !knowsSize(current)) {
            measure(current, display)?.let { remember(current, it) }
            publish(current)
        }
        return current
    }

    private fun publish(active: FoldPanel?) {
        val (main, cover) = synchronized(lock) { sizes[FoldPanel.MAIN] to sizes[FoldPanel.COVER] }
        _info.value = FoldInfo(foldable = isFoldable, activePanel = active, mainSize = main, coverSize = cover)
    }

    /**
     * Size to render a wallpaper at for [panel]: measured live while it's in use, otherwise the
     * last measurement. Null on regular phones or when the panel hasn't been seen yet.
     */
    fun renderSize(panel: FoldPanel): Size? {
        if (!isFoldable) return null
        val active = refresh()
        if (panel == active) {
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) measure(panel, display)?.let { return it }
        }
        return synchronized(lock) { sizes[panel] }
    }

    /** True once [panel] has been measured, so wallpapers can be prepared for it while it's off. */
    fun knowsSize(panel: FoldPanel): Boolean = synchronized(lock) { panel in sizes }

    /**
     * The panel's size in its natural orientation. The main screen keeps upstream Paperize's
     * measurement; the cover uses the current display mode.
     */
    private fun measure(panel: FoldPanel, display: Display): Size? {
        if (panel == FoldPanel.MAIN) return getDeviceScreenSize(context).takeIf { it.width > 1 && it.height > 1 }
        val mode = display.mode
        if (mode.physicalWidth > 0 && mode.physicalHeight > 0) return Size(mode.physicalWidth, mode.physicalHeight)
        val real = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(real)
        return if (real.x > 0 && real.y > 0) Size(minOf(real.x, real.y), maxOf(real.x, real.y)) else null
    }

    private fun remember(panel: FoldPanel, size: Size) {
        synchronized(lock) {
            if (sizes[panel] == size) return
            sizes[panel] = size
        }
        val (widthKey, heightKey) = keys(panel)
        prefs.edit {
            putInt(widthKey, size.width)
            putInt(heightKey, size.height)
        }
    }

    private fun storedSize(panel: FoldPanel): Size? {
        val (widthKey, heightKey) = keys(panel)
        val width = prefs.getInt(widthKey, 0)
        val height = prefs.getInt(heightKey, 0)
        return if (width > 0 && height > 0) Size(width, height) else null
    }

    private fun keys(panel: FoldPanel) = when (panel) {
        FoldPanel.MAIN -> KEY_MAIN_WIDTH to KEY_MAIN_HEIGHT
        FoldPanel.COVER -> KEY_COVER_WIDTH to KEY_COVER_HEIGHT
    }

    companion object {
        /** Shared with FoldPreferences and PanelTracker. */
        const val PREFS_NAME = "paperize_fold"
        private const val FOLDED_ASPECT_THRESHOLD = 1.8f
        private const val KEY_COVER_WIDTH = "cover_width"
        private const val KEY_COVER_HEIGHT = "cover_height"
        private const val KEY_MAIN_WIDTH = "main_width"
        private const val KEY_MAIN_HEIGHT = "main_height"
    }
}
