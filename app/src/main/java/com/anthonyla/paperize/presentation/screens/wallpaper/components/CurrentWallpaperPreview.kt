package com.anthonyla.paperize.presentation.screens.wallpaper.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.util.getDeviceScreenSize
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.presentation.theme.AppBorderWidths
import com.anthonyla.paperize.presentation.theme.AppMaxWidths
import com.anthonyla.paperize.presentation.theme.AppShapes
import com.anthonyla.paperize.presentation.theme.AppSpacing
import kotlin.math.max
import kotlin.math.min

/**
 * Shows the last applied source URIs; WallpaperManager readback is restricted on modern Android.
 * PaperizeFold: scaling and effects are drawn on top, so the preview matches the real wallpaper.
 */
@Composable
fun CurrentWallpaperPreview(
    homeWallpaperUri: String?,
    lockWallpaperUri: String?,
    settings: ScheduleSettings,
    modifier: Modifier = Modifier,
    animate: Boolean = true
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current

    // Portrait-oriented preview: shorter screen dimension as width.
    val screenAspectRatio = remember(configuration) {
        val screenWidth = configuration.screenWidthDp.toFloat()
        val screenHeight = configuration.screenHeightDp.toFloat()
        min(screenWidth, screenHeight) / max(screenWidth, screenHeight)
    }
    val renderWidthPx = remember(configuration) {
        getDeviceScreenSize(context).let { min(it.width, it.height) }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = AppMaxWidths.contentMaxWidth)
            .padding(horizontal = AppSpacing.small, vertical = AppSpacing.extraSmall),
        shape = AppShapes.cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.large)
        ) {
            Text(
                text = stringResource(R.string.current_wallpapers),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = AppSpacing.medium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium)
            ) {
                WallpaperPreviewBox(
                    wallpaperUri = lockWallpaperUri,
                    screen = ScreenType.LOCK,
                    settings = settings,
                    renderWidthPx = renderWidthPx,
                    aspectRatio = screenAspectRatio,
                    contentDescription = stringResource(R.string.content_desc_current_lock_wallpaper),
                    animate = animate,
                    modifier = Modifier.weight(1f)
                )

                WallpaperPreviewBox(
                    wallpaperUri = homeWallpaperUri,
                    screen = ScreenType.HOME,
                    settings = settings,
                    renderWidthPx = renderWidthPx,
                    aspectRatio = screenAspectRatio,
                    contentDescription = stringResource(R.string.content_desc_current_home_wallpaper),
                    animate = animate,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun WallpaperPreviewBox(
    wallpaperUri: String?,
    screen: ScreenType,
    settings: ScheduleSettings,
    renderWidthPx: Int,
    aspectRatio: Float,
    contentDescription: String,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .border(
                width = AppBorderWidths.thick,
                color = Color.Black,
                shape = AppShapes.imageShape
            )
            .clip(AppShapes.imageShape)
    ) {
        EffectedWallpaper(
            uri = wallpaperUri,
            effects = settings.effectsFor(screen),
            scaling = settings.scalingFor(screen),
            renderWidthPx = renderWidthPx,
            animate = animate,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize()
        )
    }
}
