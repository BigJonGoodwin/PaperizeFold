package com.anthonyla.paperize.core.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import androidx.core.net.toUri
import com.anthonyla.paperize.core.FoldPanel
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.service.fold.FoldState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

class WallpaperRenderer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val foldState: FoldState
) {
    /**
     * @param panel PaperizeFold: the foldable screen to render for (its size and, if the cover
     *   has its own look, its effects and scaling). Null means the screen in use right now.
     * @param targetSize Canvas size override. Null renders at the panel's size (upstream behavior
     *   on regular phones: the largest built-in display).
     */
    suspend fun render(
        wallpaper: Wallpaper,
        screen: ScreenType,
        settings: ScheduleSettings,
        panel: FoldPanel? = null,
        targetSize: Size? = null
    ): Bitmap? {
        currentCoroutineContext().ensureActive()
        val resolvedPanel = panel ?: foldState.activePanel() ?: FoldPanel.MAIN
        val effects = settings.effectsFor(screen, resolvedPanel)
        val scaling = settings.scalingFor(screen, resolvedPanel)
        val size = targetSize ?: foldState.renderSize(resolvedPanel) ?: getDeviceScreenSize(context)
        var bitmap = retrieveBitmap(context, wallpaper.uri.toUri(), size.width, size.height, scaling,
            usesLauncherManagedScrolling(screen, scaling, settings.homeScrollingEnabled)) ?: return null
        try {
            val processed = processBitmap(
                source = bitmap,
                enableDarken = effects.enableDarken, darkenPercent = effects.darkenPercentage,
                enableBlur = effects.enableBlur, blurPercent = effects.blurPercentage,
                enableVignette = effects.enableVignette, vignettePercent = effects.vignettePercentage,
                enableGrayscale = effects.enableGrayscale, grayscalePercent = effects.grayscalePercentage
            )
            if (processed !== bitmap) bitmap.recycle()
            bitmap = processed
            if (settings.adaptiveBrightness) {
                val adjusted = adaptiveBrightnessAdjustment(context, bitmap)
                if (adjusted !== bitmap) bitmap.recycle()
                bitmap = adjusted
            }
            currentCoroutineContext().ensureActive()
            return bitmap
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
    }
}
