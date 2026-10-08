package com.anthonyla.paperize.presentation.screens.wallpaper

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.FoldPanel
import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.AlbumSummary
import com.anthonyla.paperize.domain.model.AppSettings
import com.anthonyla.paperize.domain.model.WallpaperEffects
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.presentation.common.components.SettingSwitchItem
import com.anthonyla.paperize.presentation.screens.wallpaper.components.AlbumSelectionBottomSheet
import com.anthonyla.paperize.presentation.screens.wallpaper.components.CurrentWallpaperPreview
import com.anthonyla.paperize.presentation.screens.wallpaper.components.FoldWallpaperPreview
import com.anthonyla.paperize.presentation.screens.wallpaper.components.SettingSwitchWithSlider
import com.anthonyla.paperize.presentation.screens.wallpaper.components.TimeIntervalPicker
import com.anthonyla.paperize.presentation.screens.wallpaper.components.panelLabel
import com.anthonyla.paperize.presentation.theme.AppSpacing
import com.anthonyla.paperize.service.fold.FoldInfo

private enum class AlbumSelectionContext {
    HOME, LOCK, LIVE
}

/** One visual effect: how to read and change it on a [WallpaperEffects]. */
private class EffectSpec(
    @StringRes val title: Int,
    @StringRes val description: Int,
    val isEnabled: (WallpaperEffects) -> Boolean,
    val percentage: (WallpaperEffects) -> Int,
    val withEnabled: (WallpaperEffects, Boolean) -> WallpaperEffects,
    val withPercentage: (WallpaperEffects, Int) -> WallpaperEffects
)

private val EFFECT_SPECS = listOf(
    EffectSpec(
        R.string.change_brightness, R.string.change_the_image_brightness,
        { it.enableDarken }, { it.darkenPercentage },
        { e, on -> e.copy(enableDarken = on) }, { e, value -> e.copy(darkenPercentage = value) }
    ),
    EffectSpec(
        R.string.change_blur, R.string.add_blur_to_the_image,
        { it.enableBlur }, { it.blurPercentage },
        { e, on -> e.copy(enableBlur = on) }, { e, value -> e.copy(blurPercentage = value) }
    ),
    EffectSpec(
        R.string.change_vignette, R.string.darken_the_edges_of_the_image,
        { it.enableVignette }, { it.vignettePercentage },
        { e, on -> e.copy(enableVignette = on) }, { e, value -> e.copy(vignettePercentage = value) }
    ),
    EffectSpec(
        R.string.gray_filter, R.string.make_the_colors_grayscale,
        { it.enableGrayscale }, { it.grayscalePercentage },
        { e, on -> e.copy(enableGrayscale = on) }, { e, value -> e.copy(grayscalePercentage = value) }
    )
)

/** PaperizeFold: at this width (an unfolded foldable or a tablet) the preview gets its own pane. */
private val TWO_PANE_MIN_WIDTH = 600.dp

@Composable
fun WallpaperScreen(
    albums: List<AlbumSummary>,
    persistedScheduleSettings: ScheduleSettings,
    appSettings: AppSettings,
    wallpaperMode: WallpaperMode,
    onToggleChanger: (Boolean) -> Unit,
    onSelectHomeAlbum: (AlbumSummary?) -> Unit,
    onSelectLockAlbum: (AlbumSummary?) -> Unit,
    onSelectLiveAlbum: (AlbumSummary?) -> Unit,
    onUpdateScheduleSettings: (ScheduleSettings) -> Unit,
    onUpdateScheduleSettingsDebounced: (ScheduleSettings) -> Unit,
    onChangeWallpaperNow: () -> Unit,
    homeWallpaperUri: String?,
    lockWallpaperUri: String?,
    modifier: Modifier = Modifier,
    foldInfo: FoldInfo = FoldInfo()
) {
    var albumSelectionContext by rememberSaveable { mutableStateOf<AlbumSelectionContext?>(null) }
    var showEmptyAlbumWarning by rememberSaveable { mutableStateOf(false) }
    var scheduleSettings by remember { mutableStateOf(persistedScheduleSettings) }
    var editingPanel by rememberSaveable { mutableStateOf(FoldPanel.MAIN) }

    // Keep an immediate local draft so a slider value waiting for the ViewModel debounce
    // is included in a switch or other setting changed before that debounce expires.
    LaunchedEffect(persistedScheduleSettings) {
        scheduleSettings = persistedScheduleSettings
    }

    fun updateSettingsDebounced(newSettings: ScheduleSettings) {
        scheduleSettings = newSettings
        onUpdateScheduleSettingsDebounced(newSettings)
    }

    fun updateSettingsImmediate(newSettings: ScheduleSettings) {
        scheduleSettings = newSettings
        onUpdateScheduleSettings(newSettings)
    }

    val isStatic = wallpaperMode == WallpaperMode.STATIC
    val homeEnabled = scheduleSettings.homeEnabled
    val lockEnabled = scheduleSettings.lockEnabled
    val bothEnabled = isStatic && homeEnabled && lockEnabled

    // PaperizeFold: with a separate cover look, the controls below edit the selected screen.
    val foldControls = foldInfo.foldable && isStatic
    val coverSeparate = foldControls && scheduleSettings.separateCoverSettings
    val editPanel = if (coverSeparate) editingPanel else FoldPanel.MAIN
    val editedHomeEffects = scheduleSettings.effectsFor(ScreenType.HOME, editPanel)
    val editedLockEffects = scheduleSettings.effectsFor(ScreenType.LOCK, editPanel)

    val primaryEffects = when {
        !isStatic -> scheduleSettings.liveEffects
        homeEnabled -> editedHomeEffects
        else -> editedLockEffects
    }

    fun ScheduleSettings.withScreenEffects(
        screen: ScreenType,
        transform: (WallpaperEffects) -> WallpaperEffects
    ): ScheduleSettings = when {
        editPanel == FoldPanel.COVER && screen == ScreenType.LOCK -> copy(coverLockEffects = transform(coverLockEffects))
        editPanel == FoldPanel.COVER -> copy(coverHomeEffects = transform(coverHomeEffects))
        screen == ScreenType.LOCK -> copy(lockEffects = transform(lockEffects))
        else -> copy(homeEffects = transform(homeEffects))
    }

    fun updateEffects(
        home: (WallpaperEffects) -> WallpaperEffects,
        lock: (WallpaperEffects) -> WallpaperEffects = home,
        debounced: Boolean = false
    ) {
        var updated = scheduleSettings
        if (!isStatic) {
            updated = updated.copy(liveEffects = home(updated.liveEffects))
        } else {
            if (homeEnabled) updated = updated.withScreenEffects(ScreenType.HOME, home)
            if (lockEnabled) updated = updated.withScreenEffects(ScreenType.LOCK, lock)
        }
        if (debounced) updateSettingsDebounced(updated) else updateSettingsImmediate(updated)
    }

    fun updateScreenEffects(screen: ScreenType, transform: (WallpaperEffects) -> WallpaperEffects) {
        updateSettingsImmediate(scheduleSettings.withScreenEffects(screen, transform))
    }

    val scalingOptions = listOf(
        ScalingType.FILL to stringResource(R.string.fill),
        ScalingType.FIT to stringResource(R.string.fit),
        ScalingType.STRETCH to stringResource(R.string.stretch),
        ScalingType.NONE to stringResource(R.string.none)
    )
    val selectedScaling = if (!isStatic) scheduleSettings.liveScalingType
        else scheduleSettings.scalingFor(ScreenType.HOME, editPanel)

    fun updateScaling(scalingType: ScalingType) {
        updateSettingsImmediate(
            when {
                !isStatic -> scheduleSettings.copy(liveScalingType = scalingType)
                editPanel == FoldPanel.COVER -> scheduleSettings.copy(coverScalingType = scalingType)
                else -> scheduleSettings.copy(homeScalingType = scalingType, lockScalingType = scalingType)
            }
        )
    }

    val hasAlbumSelected = scheduleSettings.activeScreens(wallpaperMode).isNotEmpty()
    val allRequiredAlbumsSelected = scheduleSettings.hasRequiredAlbums(wallpaperMode)
    val selectPanel: (FoldPanel) -> Unit = { editingPanel = it }

    val scheduleSection: @Composable ColumnScope.() -> Unit = {
        if (isStatic) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.small, vertical = AppSpacing.extraSmall),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)
            ) {
                ScreenToggleCard(
                    title = stringResource(R.string.lock), icon = Icons.Default.Lock, enabled = lockEnabled,
                    onClick = { updateSettingsImmediate(scheduleSettings.copy(lockEnabled = !lockEnabled)) },
                    modifier = Modifier.weight(1f)
                )
                ScreenToggleCard(
                    title = stringResource(R.string.home), icon = Icons.Default.Home, enabled = homeEnabled,
                    onClick = { updateSettingsImmediate(scheduleSettings.copy(homeEnabled = !homeEnabled)) },
                    modifier = Modifier.weight(1f)
                )
            }
            if (lockEnabled) AlbumSelector(
                albumId = scheduleSettings.lockAlbumId, albums = albums,
                label = stringResource(R.string.lock_album_label),
                onClick = { albumSelectionContext = AlbumSelectionContext.LOCK }
            )
            if (homeEnabled) AlbumSelector(
                albumId = scheduleSettings.homeAlbumId, albums = albums,
                label = stringResource(R.string.home_album_label),
                onClick = { albumSelectionContext = AlbumSelectionContext.HOME }
            )
        } else {
            AlbumSelector(
                albumId = scheduleSettings.liveAlbumId, albums = albums,
                label = stringResource(R.string.currently_selected_album),
                onClick = { albumSelectionContext = AlbumSelectionContext.LIVE }
            )
        }
        if (isStatic && scheduleSettings.enableChanger && homeEnabled && lockEnabled) {
            SettingSwitchItem(
                title = stringResource(R.string.individual_scheduling),
                description = stringResource(R.string.show_interval_sliders),
                checked = scheduleSettings.separateSchedules,
                onCheckedChange = { enabled ->
                    updateSettingsImmediate(scheduleSettings.copy(separateSchedules = enabled))
                }
            )
        }

        if (allRequiredAlbumsSelected) {
            SettingSwitchItem(
                title = stringResource(R.string.wallpaper_changer),
                description = stringResource(R.string.wallpaper_changer_description),
                checked = scheduleSettings.enableChanger,
                onCheckedChange = onToggleChanger
            )
        }
        if (hasAlbumSelected) {
            if (isStatic) {
                if (!scheduleSettings.separateSchedules || !homeEnabled || !lockEnabled) {
                    TimeIntervalPicker(
                        title = stringResource(R.string.interval_text),
                        minutes = scheduleSettings.homeIntervalMinutes,
                        onMinutesChange = { minutes ->
                            updateSettingsDebounced(
                                scheduleSettings.copy(
                                    homeIntervalMinutes = minutes,
                                    lockIntervalMinutes = minutes
                                )
                            )
                        }
                    )
                } else {
                    TimeIntervalPicker(
                        title = stringResource(R.string.lock_screen_btn),
                        minutes = scheduleSettings.lockIntervalMinutes,
                        onMinutesChange = { minutes ->
                            updateSettingsImmediate(
                                scheduleSettings.copy(lockIntervalMinutes = minutes)
                            )
                        }
                    )
                    TimeIntervalPicker(
                        title = stringResource(R.string.home_screen_btn),
                        minutes = scheduleSettings.homeIntervalMinutes,
                        onMinutesChange = { minutes ->
                            updateSettingsImmediate(
                                scheduleSettings.copy(homeIntervalMinutes = minutes)
                            )
                        }
                    )
                }
            } else {
                TimeIntervalPicker(
                    title = stringResource(R.string.interval_text),
                    minutes = scheduleSettings.liveIntervalMinutes,
                    minimumMinutes = Constants.MIN_LIVE_INTERVAL_MINUTES,
                    onMinutesChange = { minutes ->
                        updateSettingsImmediate(
                            scheduleSettings.copy(liveIntervalMinutes = minutes)
                        )
                    }
                )
                Text(
                    text = stringResource(R.string.live_short_interval_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = AppSpacing.large)
                )
            }
        }
        SettingSwitchItem(
            title = stringResource(R.string.shuffle),
            description = if (scheduleSettings.shuffleEnabled && !scheduleSettings.separateSchedules) null else stringResource(R.string.randomly_shuffle_the_wallpapers),
            checked = scheduleSettings.shuffleEnabled,
            onCheckedChange = { enabled ->
                updateSettingsImmediate(scheduleSettings.copy(shuffleEnabled = enabled))
            }
        )
    }

    val changeNowButton: @Composable ColumnScope.() -> Unit = {
        if (hasAlbumSelected) {
            Button(
                onClick = onChangeWallpaperNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        PaddingValues(
                            horizontal = AppSpacing.small,
                            vertical = AppSpacing.extraSmall
                        )
                    )
            ) {
                Text(text = stringResource(R.string.change_wallpaper_now))
            }
        }
    }

    val previewSection: @Composable ColumnScope.() -> Unit = {
        if (isStatic) {
            if (foldInfo.foldable) {
                FoldWallpaperPreview(
                    homeWallpaperUri = homeWallpaperUri,
                    lockWallpaperUri = lockWallpaperUri,
                    settings = scheduleSettings,
                    foldInfo = foldInfo,
                    selectedPanel = if (coverSeparate) editingPanel else null,
                    onSelectPanel = if (coverSeparate) selectPanel else null,
                    animate = appSettings.animate
                )
            } else {
                CurrentWallpaperPreview(
                    homeWallpaperUri = homeWallpaperUri,
                    lockWallpaperUri = lockWallpaperUri,
                    settings = scheduleSettings,
                    animate = appSettings.animate
                )
            }
        }
    }

    val lookSection: @Composable ColumnScope.() -> Unit = {
        Text(
            text = stringResource(R.string.wallpaper_effects_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = AppSpacing.large, vertical = AppSpacing.small),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (foldControls) {
            SettingSwitchItem(
                title = stringResource(R.string.fold_separate_cover),
                description = stringResource(R.string.fold_separate_cover_desc),
                checked = scheduleSettings.separateCoverSettings,
                onCheckedChange = { on ->
                    var updated = scheduleSettings.copy(separateCoverSettings = on)
                    // Start the cover from the main screen's look the first time they're split.
                    if (on && scheduleSettings.hasDefaultCoverLook) updated = updated.withCoverLookFromMain()
                    updateSettingsImmediate(updated)
                    editingPanel = if (on) FoldPanel.COVER else FoldPanel.MAIN
                }
            )
            if (coverSeparate) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.small, vertical = AppSpacing.extraSmall)
                ) {
                    FoldPanel.entries.forEachIndexed { index, panel ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = FoldPanel.entries.size),
                            onClick = { editingPanel = panel },
                            selected = editingPanel == panel
                        ) {
                            Text(
                                text = stringResource(panelLabel(panel)),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(horizontal = AppSpacing.small, vertical = AppSpacing.extraSmall))
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
            ) {
                Text(
                    text = stringResource(R.string.scaling),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    scalingOptions.forEachIndexed { index, (scalingType, label) ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = scalingOptions.size
                            ),
                            onClick = { updateScaling(scalingType) },
                            selected = scalingType == selectedScaling
                        ) {
                            Text(
                                text = label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        if (isStatic) {
            SettingSwitchItem(
                title = stringResource(R.string.horizontal_wallpaper_scrolling),
                description = stringResource(R.string.horizontal_wallpaper_scrolling_description),
                checked = scheduleSettings.homeScrollingEnabled,
                onCheckedChange = { enabled ->
                    updateSettingsImmediate(
                        scheduleSettings.copy(homeScrollingEnabled = enabled)
                    )
                }
            )
        }

        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(horizontal = AppSpacing.small, vertical = AppSpacing.extraSmall))
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
            ) {
                Text(
                    text = stringResource(R.string.visual_effects),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = AppSpacing.small),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                EFFECT_SPECS.forEach { spec ->
                    SettingSwitchWithSlider(
                        title = spec.title,
                        description = spec.description,
                        checked = spec.isEnabled(primaryEffects),
                        onCheckedChange = { enabled -> updateEffects({ spec.withEnabled(it, enabled) }) },
                        homeChecked = spec.isEnabled(editedHomeEffects),
                        lockChecked = spec.isEnabled(editedLockEffects),
                        onHomeCheckedChange = { enabled ->
                            updateScreenEffects(ScreenType.HOME) { spec.withEnabled(it, enabled) }
                        },
                        onLockCheckedChange = { enabled ->
                            updateScreenEffects(ScreenType.LOCK) { spec.withEnabled(it, enabled) }
                        },
                        bothEnabled = bothEnabled,
                        homePercentage = spec.percentage(primaryEffects),
                        lockPercentage = spec.percentage(editedLockEffects),
                        onPercentageChange = { home, lock ->
                            updateEffects(
                                { spec.withPercentage(it, home) },
                                { spec.withPercentage(it, lock) },
                                debounced = true
                            )
                        }
                    )
                }
                SettingSwitchItem(
                    title = stringResource(R.string.adaptive_brightness),
                    description = if (scheduleSettings.adaptiveBrightness && !scheduleSettings.separateSchedules) null else stringResource(R.string.adjust_brightness_based_on_mode),
                    checked = scheduleSettings.adaptiveBrightness,
                    onCheckedChange = { enabled ->
                        updateSettingsImmediate(scheduleSettings.copy(adaptiveBrightness = enabled))
                    }
                )
            }
        }
        if (!isStatic) {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PaddingValues(horizontal = AppSpacing.small, vertical = AppSpacing.extraSmall))
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
                ) {
                    Text(
                        text = stringResource(R.string.interactive_effects),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = AppSpacing.small),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    SettingSwitchItem(
                        title = stringResource(R.string.double_tap_to_change),
                        description = if (scheduleSettings.liveEffects.enableDoubleTap) null else stringResource(R.string.double_tap_wallpaper_to_change_it),
                        checked = scheduleSettings.liveEffects.enableDoubleTap,
                        onCheckedChange = { enabled ->
                            updateSettingsImmediate(
                                scheduleSettings.copy(
                                    liveEffects = scheduleSettings.liveEffects.copy(enableDoubleTap = enabled)
                                )
                            )
                        }
                    )
                    SettingSwitchItem(
                        title = stringResource(R.string.change_on_screen_off),
                        description = if (scheduleSettings.liveEffects.enableChangeOnScreenOff) null else stringResource(R.string.change_wallpaper_when_screen_turns_off),
                        checked = scheduleSettings.liveEffects.enableChangeOnScreenOff,
                        onCheckedChange = { enabled ->
                            updateSettingsImmediate(
                                scheduleSettings.copy(
                                    liveEffects = scheduleSettings.liveEffects.copy(enableChangeOnScreenOff = enabled)
                                )
                            )
                        }
                    )
                    SettingSwitchWithSlider(
                        title = R.string.parallax_effect,
                        description = R.string.wallpaper_moves_with_screen_scroll,
                        checked = scheduleSettings.liveEffects.enableParallax,
                        onCheckedChange = { enabled ->
                            updateSettingsImmediate(
                                scheduleSettings.copy(
                                    liveEffects = scheduleSettings.liveEffects.copy(enableParallax = enabled)
                                )
                            )
                        },
                        bothEnabled = false, // Never separate in Live Mode
                        homePercentage = scheduleSettings.liveEffects.parallaxIntensity,
                        lockPercentage = 0,
                        onPercentageChange = { homePercent, _ ->
                            updateSettingsDebounced(
                                scheduleSettings.copy(
                                    liveEffects = scheduleSettings.liveEffects.copy(parallaxIntensity = homePercent)
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // PaperizeFold: on a wide screen (unfolded) keep the preview in view next to the
        // controls, so every slider change can be seen on both screens as it happens.
        if (isStatic && maxWidth >= TWO_PANE_MIN_WIDTH) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.extraSmall)
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.46f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = AppSpacing.small, bottom = AppSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
                ) {
                    previewSection()
                    changeNowButton()
                }
                Column(
                    modifier = Modifier
                        .weight(0.54f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(end = AppSpacing.small, bottom = AppSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
                ) {
                    scheduleSection()
                    HorizontalDivider(modifier = Modifier.padding(vertical = AppSpacing.small))
                    lookSection()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = AppSpacing.small, end = AppSpacing.small, bottom = AppSpacing.small),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
            ) {
                scheduleSection()
                changeNowButton()
                HorizontalDivider(modifier = Modifier.padding(vertical = AppSpacing.small))
                // The preview sits right above the look controls so edits stay in view.
                previewSection()
                lookSection()
            }
        }
    }

    albumSelectionContext?.let { selection ->
        val selectedId = when (selection) {
            AlbumSelectionContext.HOME -> scheduleSettings.homeAlbumId
            AlbumSelectionContext.LOCK -> scheduleSettings.lockAlbumId
            AlbumSelectionContext.LIVE -> scheduleSettings.liveAlbumId
        }
        val selectAlbum = when (selection) {
            AlbumSelectionContext.HOME -> onSelectHomeAlbum
            AlbumSelectionContext.LOCK -> onSelectLockAlbum
            AlbumSelectionContext.LIVE -> onSelectLiveAlbum
        }
        AlbumSelectionBottomSheet(
            albums = albums,
            selectedAlbumId = selectedId,
            onAlbumSelect = { album ->
                when {
                    album.id == selectedId -> selectAlbum(null)
                    album.wallpaperCount == 0 -> showEmptyAlbumWarning = true
                    else -> selectAlbum(album)
                }
            },
            onDismiss = { albumSelectionContext = null }
        )
    }

    if (showEmptyAlbumWarning) {
        AlertDialog(
            onDismissRequest = { showEmptyAlbumWarning = false },
            title = {
                Text(
                    text = stringResource(R.string.empty_album),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.empty_album_message),
                    maxLines = Constants.DIALOG_MESSAGE_MAX_LINES,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                TextButton(onClick = { showEmptyAlbumWarning = false }) {
                    Text(
                        text = stringResource(R.string.ok),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        )
    }
}

@Composable
private fun ScreenToggleCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            val contentColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            Icon(icon, contentDescription = null, tint = contentColor)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = if (enabled) contentColor else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(if (enabled) R.string.enabled else R.string.disabled),
                style = MaterialTheme.typography.bodySmall, color = contentColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AlbumSelector(albumId: String?, albums: List<AlbumSummary>, label: String, onClick: () -> Unit) {
    val album = albums.find { it.id == albumId }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.small),
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(Modifier.fillMaxWidth().padding(AppSpacing.large), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(album?.name ?: stringResource(if (albumId == null) R.string.no_album_selected else R.string.loading_placeholder),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
