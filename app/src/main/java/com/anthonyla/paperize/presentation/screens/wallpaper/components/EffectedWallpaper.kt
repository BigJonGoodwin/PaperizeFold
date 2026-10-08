package com.anthonyla.paperize.presentation.screens.wallpaper.components

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.util.getExifOrientation
import com.anthonyla.paperize.domain.model.WallpaperEffects
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PaperizeFold: a wallpaper thumbnail drawn the way it will look once applied: same scaling, and
 * the same effect chain as WallpaperUtil's GPU pipeline (vignette, then darken, blur, grayscale),
 * live as sliders move. Adaptive brightness depends on the image and theme, so it isn't shown.
 *
 * @param renderWidthPx width the real wallpaper is rendered at, so blur and "original size"
 *   scaling are shown at the right strength for this smaller preview.
 */
@Composable
fun EffectedWallpaper(
    uri: String?,
    effects: WallpaperEffects,
    scaling: ScalingType,
    renderWidthPx: Int,
    animate: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sourceWidth = rememberSourceWidth(uri, needed = scaling == ScalingType.NONE)
    val contentScale = remember(scaling, sourceWidth, renderWidthPx) {
        scaling.toContentScale(sourceWidth, renderWidthPx)
    }
    val request = remember(uri, animate) {
        ImageRequest.Builder(context)
            .data(uri)
            .size(coil3.size.Size(Constants.PREVIEW_THUMBNAIL_WIDTH, Constants.PREVIEW_THUMBNAIL_HEIGHT))
            .crossfade(animate)
            .build()
    }
    Box(
        modifier = modifier.background(
            if (uri == null) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Black
        )
    ) {
        if (uri != null) {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier
                    .matchParentSize()
                    .wallpaperEffects(effects, renderWidthPx)
            )
        }
    }
}

private fun Modifier.wallpaperEffects(effects: WallpaperEffects, renderWidthPx: Int): Modifier {
    val darken = effects.enableDarken && effects.darkenPercentage > 0
    val blur = effects.enableBlur && effects.blurPercentage > 0
    val vignette = effects.enableVignette && effects.vignettePercentage > 0
    val grayscale = effects.enableGrayscale && effects.grayscalePercentage > 0
    if (!darken && !blur && !vignette && !grayscale) return this
    return this
        .graphicsLayer {
            val previewScale = size.width / renderWidthPx.coerceAtLeast(1)
            renderEffect = effectChain(effects, darken, blur, grayscale, previewScale)
            clip = true
        }
        .drawWithContent {
            drawContent()
            if (vignette) drawRect(brush = vignetteBrush(size, effects.vignettePercentage))
        }
}

/** Mirrors processBitmapGpu: darken, then blur, then grayscale, over the vignetted image. */
private fun effectChain(
    effects: WallpaperEffects,
    darken: Boolean,
    blur: Boolean,
    grayscale: Boolean,
    previewScale: Float
): androidx.compose.ui.graphics.RenderEffect? {
    var effect: RenderEffect? = null
    if (darken) {
        val factor = (100 - effects.darkenPercentage.coerceIn(0, 100)) / 100f
        val matrix = ColorMatrix().apply { setScale(factor, factor, factor, 1f) }
        effect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
    }
    if (blur) {
        val radius = effects.blurPercentage.coerceIn(0, 100) / 100f * Constants.MAX_BLUR_RADIUS * previewScale
        if (radius >= Constants.BLUR_MIN_THRESHOLD) {
            val blurEffect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            effect = effect?.let { RenderEffect.createChainEffect(blurEffect, it) } ?: blurEffect
        }
    }
    if (grayscale) {
        val matrix = ColorMatrix().apply { setSaturation(1 - effects.grayscalePercentage.coerceIn(0, 100) / 100f) }
        val grayEffect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
        effect = effect?.let { RenderEffect.createChainEffect(grayEffect, it) } ?: grayEffect
    }
    return effect?.asComposeRenderEffect()
}

/** Same geometry and colors as WallpaperUtil's vignette, relative to the preview size. */
private fun vignetteBrush(size: Size, percent: Int): Brush {
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val radius = (hypot(centerX, centerY) * (1 - percent.coerceIn(0, 100) / Constants.VIGNETTE_DIVISOR))
        .coerceAtLeast(Constants.VIGNETTE_MIN_RADIUS)
    val stops = Constants.VIGNETTE_GRADIENT_POSITIONS
    return Brush.radialGradient(
        stops[0] to Color.Transparent,
        stops[1] to Color.Black.copy(alpha = Constants.VIGNETTE_INNER_ALPHA),
        stops[2] to Color.Black.copy(alpha = Constants.VIGNETTE_OUTER_ALPHA),
        center = Offset(centerX, centerY),
        radius = radius,
        tileMode = TileMode.Clamp
    )
}

private fun ScalingType.toContentScale(sourceWidth: Int?, renderWidthPx: Int): ContentScale = when (this) {
    ScalingType.FILL -> ContentScale.Crop
    ScalingType.FIT -> ContentScale.Fit
    ScalingType.STRETCH -> ContentScale.FillBounds
    ScalingType.NONE ->
        if (sourceWidth != null && sourceWidth > 0) ActualPixels(sourceWidth, renderWidthPx) else ContentScale.Crop
}

/** "Original size": one image pixel per screen pixel, shrunk with the rest of the preview. */
private data class ActualPixels(val sourceWidth: Int, val renderWidthPx: Int) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        if (srcSize.width <= 0f || renderWidthPx <= 0) return ScaleFactor(1f, 1f)
        val scale = sourceWidth * (dstSize.width / renderWidthPx) / srcSize.width
        return ScaleFactor(scale, scale)
    }
}

/** Width of the original image (after EXIF rotation), read only when "original size" needs it. */
@Composable
private fun rememberSourceWidth(uri: String?, needed: Boolean): Int? {
    val context = LocalContext.current
    val width by produceState<Int?>(initialValue = null, uri, needed) {
        value = if (uri == null || !needed) null else withContext(Dispatchers.IO) { readSourceWidth(context, uri) }
    }
    return width
}

private fun readSourceWidth(context: Context, uri: String): Int? = try {
    val parsed = uri.toUri()
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, options) }
    if (options.outWidth <= 0 || options.outHeight <= 0) {
        null
    } else {
        when (parsed.getExifOrientation(context)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> options.outHeight
            else -> options.outWidth
        }
    }
} catch (_: Exception) {
    null
}
