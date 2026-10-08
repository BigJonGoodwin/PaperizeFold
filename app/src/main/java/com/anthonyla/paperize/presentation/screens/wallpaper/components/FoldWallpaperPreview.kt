package com.anthonyla.paperize.presentation.screens.wallpaper.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.FoldPanel
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.presentation.theme.AppBorderWidths
import com.anthonyla.paperize.presentation.theme.AppShapes
import com.anthonyla.paperize.presentation.theme.AppSpacing
import com.anthonyla.paperize.service.fold.FoldInfo

/**
 * PaperizeFold: both screens of the foldable side by side, at their real shapes and the same
 * height, each showing the current wallpaper with that screen's scaling and effects applied.
 *
 * @param selectedPanel the screen whose look is being edited (outlined), or null
 * @param onSelectPanel tap a screen to edit it; null when the screens share one look
 */
@Composable
fun FoldWallpaperPreview(
    homeWallpaperUri: String?,
    lockWallpaperUri: String?,
    settings: ScheduleSettings,
    foldInfo: FoldInfo,
    selectedPanel: FoldPanel?,
    onSelectPanel: ((FoldPanel) -> Unit)?,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val bothScreens = settings.homeEnabled && settings.lockEnabled
    var showLock by rememberSaveable { mutableStateOf(false) }
    val screen = when {
        bothScreens -> if (showLock) ScreenType.LOCK else ScreenType.HOME
        settings.lockEnabled -> ScreenType.LOCK
        else -> ScreenType.HOME
    }
    val uri = if (screen == ScreenType.LOCK) lockWallpaperUri else homeWallpaperUri

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.small, vertical = AppSpacing.extraSmall),
        shape = AppShapes.cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)
            ) {
                Text(
                    text = stringResource(R.string.fold_preview_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (bothScreens) {
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = !showLock,
                            onClick = { showLock = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text(stringResource(R.string.home), maxLines = 1) }
                        SegmentedButton(
                            selected = showLock,
                            onClick = { showLock = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text(stringResource(R.string.lock), maxLines = 1) }
                    }
                }
            }

            // Weights proportional to each screen's width/height ratio give both the same height.
            Row(
                modifier = Modifier
                    .widthIn(max = MAX_PREVIEW_WIDTH)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium),
                verticalAlignment = Alignment.Bottom
            ) {
                FoldPanel.entries.forEach { panel ->
                    val aspect = foldInfo.aspectRatio(panel)
                    PanelFrame(
                        label = stringResource(panelLabel(panel)),
                        aspect = aspect,
                        uri = uri,
                        settings = settings,
                        screen = screen,
                        panel = panel,
                        renderWidthPx = foldInfo.renderWidth(panel),
                        selected = selectedPanel == panel,
                        inUse = foldInfo.activePanel == panel,
                        onClick = onSelectPanel?.let { select -> { select(panel) } },
                        animate = animate,
                        modifier = Modifier.weight(aspect)
                    )
                }
            }
        }
    }
}

fun panelLabel(panel: FoldPanel): Int = when (panel) {
    FoldPanel.MAIN -> R.string.fold_panel_main
    FoldPanel.COVER -> R.string.fold_panel_cover
}

@Composable
private fun PanelFrame(
    label: String,
    aspect: Float,
    uri: String?,
    settings: ScheduleSettings,
    screen: ScreenType,
    panel: FoldPanel,
    renderWidthPx: Int,
    selected: Boolean,
    inUse: Boolean,
    onClick: (() -> Unit)?,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Black,
        label = "panel_border_color"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (selected) AppBorderWidths.thick + 1.dp else AppBorderWidths.thick,
        label = "panel_border_width"
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.extraSmall)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .border(width = borderWidth, color = borderColor, shape = AppShapes.imageShape)
                .clip(AppShapes.imageShape)
                .then(
                    if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
                )
        ) {
            EffectedWallpaper(
                uri = uri,
                effects = settings.effectsFor(screen, panel),
                scaling = settings.scalingFor(screen, panel),
                renderWidthPx = renderWidthPx,
                animate = animate,
                contentDescription = label,
                modifier = Modifier.matchParentSize()
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.extraSmall)
        ) {
            if (inUse) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val MAX_PREVIEW_WIDTH = 440.dp
