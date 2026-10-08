package com.anthonyla.paperize.service.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.anthonyla.paperize.core.EmptyAlbumException
import com.anthonyla.paperize.core.FoldPanel
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.staticSlots
import com.anthonyla.paperize.core.util.setBitmapChecked
import com.anthonyla.paperize.domain.model.PreparedWallpaper
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import com.anthonyla.paperize.domain.usecase.ChangeWallpaperUseCase
import com.anthonyla.paperize.domain.usecase.ReapplyEffectsUseCase
import com.anthonyla.paperize.service.fold.PanelTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject

data class WallpaperChangeOutcome(val changed: Boolean = false, val emptyAlbum: Boolean = false)

/** Call while holding WallpaperChangeLock so applying and resetting schedules stay ordered. */
class WallpaperController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wallpaperManager: WallpaperManager,
    private val prepare: ChangeWallpaperUseCase,
    private val render: ReapplyEffectsUseCase,
    private val settingsRepository: SettingsRepository,
    private val wallpaperRepository: WallpaperRepository,
    private val panelTracker: PanelTracker
) {
    /**
     * PaperizeFold: one public operation. [panel] is the foldable screen it renders for (null on
     * regular phones); [written] collects the home/lock slots Android actually accepted and the
     * image written to each (null: the slot's current image), reported to [PanelTracker] when the
     * operation ends.
     */
    private class Op(val panel: FoldPanel?) {
        val written = linkedMapOf<ScreenType, String?>()
        val presentationPanel: FoldPanel get() = panel ?: FoldPanel.MAIN
        fun wrote(screen: ScreenType, wallpaperId: String? = null) {
            for (slot in screen.staticSlots()) written[slot] = wallpaperId
        }
    }

    private suspend fun <T> tracked(panel: FoldPanel? = null, block: suspend (Op) -> T): T {
        val ticket = panelTracker.begin()
        val op = Op(panel ?: ticket?.panel)
        try {
            return block(op)
        } finally {
            withContext(NonCancellable) { panelTracker.end(ticket, op.written) }
        }
    }

    suspend fun change(screen: ScreenType, settings: ScheduleSettings): WallpaperChangeOutcome {
        if (screen == ScreenType.LIVE) {
            context.sendBroadcast(Intent(Constants.ACTION_RELOAD_WALLPAPER).setPackage(context.packageName))
            return WallpaperChangeOutcome(changed = true)
        }
        return tracked { op ->
            when (screen) {
                ScreenType.HOME -> changeSelected(op, settings.homeAlbumId, screen)
                ScreenType.LOCK -> changeSelected(op, settings.lockAlbumId, screen)
                else -> {
                    val home = settings.homeAlbumId
                    if (home != null && home == settings.lockAlbumId && !settings.separateSchedules) {
                        changeSynchronized(op, home, settings)
                    } else {
                        combine(
                            changeSelected(op, home, ScreenType.HOME),
                            changeSelected(op, settings.lockAlbumId, ScreenType.LOCK)
                        )
                    }
                }
            }
        }
    }

    private suspend fun changeSelected(op: Op, albumId: String?, screen: ScreenType): WallpaperChangeOutcome {
        if (albumId == null) return WallpaperChangeOutcome()
        val prepared = prepareOrDisable(op, albumId, screen) ?: return WallpaperChangeOutcome(emptyAlbum = true)
        applyPrepared(op, prepared, screen)
        return WallpaperChangeOutcome(changed = true)
    }

    private suspend fun changeSynchronized(op: Op, albumId: String, settings: ScheduleSettings): WallpaperChangeOutcome {
        val prepared = prepareOrDisable(op, albumId, ScreenType.BOTH) ?: return WallpaperChangeOutcome(emptyAlbum = true)
        if (settings.samePresentation(op.presentationPanel)) {
            applyPrepared(op, prepared, ScreenType.BOTH)
        } else {
            applyPrepared(op, prepared, ScreenType.HOME)
            val lockBitmap = render(albumId, ScreenType.LOCK, prepared.wallpaperId, op.panel).getOrThrow()
            applyBitmap(op, lockBitmap, ScreenType.LOCK, prepared.wallpaperId) { prepare.complete(prepared, ScreenType.LOCK) }
        }
        return WallpaperChangeOutcome(changed = true)
    }

    private suspend fun prepareOrDisable(op: Op, albumId: String, screen: ScreenType): PreparedWallpaper? {
        try {
            return prepare(albumId, if (screen == ScreenType.BOTH) ScreenType.HOME else screen, op.panel).getOrThrow()
        } catch (_: EmptyAlbumException) {
            settingsRepository.clearEmptyAlbumSelection(albumId, screen)
            return null
        }
    }

    private suspend fun applyPrepared(op: Op, prepared: PreparedWallpaper, screen: ScreenType) {
        var accepted = false
        try {
            currentCoroutineContext().ensureActive()
            wallpaperManager.setBitmapChecked(prepared.bitmap, screen.flags())
            accepted = true
            op.wrote(screen, prepared.wallpaperId)
            // Once Android accepts the bitmap, cancellation must not leave our current item stale.
            withContext(NonCancellable) {
                screen.staticScreens().forEach { prepare.complete(prepared, it) }
            }
        } catch (e: Exception) {
            if (!accepted) withContext(NonCancellable) { prepare.restore(prepared) }
            throw e
        } finally {
            prepared.bitmap.recycle()
        }
    }

    suspend fun applySpecific(albumId: String, wallpaperId: String, screen: ScreenType, settings: ScheduleSettings) {
        tracked { op ->
            val targets = if (screen == ScreenType.BOTH && !settings.samePresentation(op.presentationPanel)) {
                listOf(ScreenType.HOME, ScreenType.LOCK)
            } else listOf(screen)
            for (target in targets) {
                val renderScreen = if (target == ScreenType.BOTH) ScreenType.HOME else target
                val bitmap = render(albumId, renderScreen, wallpaperId, op.panel).getOrThrow()
                applyBitmap(op, bitmap, target, wallpaperId) {
                    target.staticScreens().forEach {
                        prepare.completeSpecific(albumId, it, wallpaperId, settings.shuffleEnabled)
                    }
                }
            }
        }
    }

    /**
     * @param panel PaperizeFold: the foldable screen to render for; null means the one in use.
     * @param advanceIfMissing When the current image can't be rendered, move to the next one.
     *   Fold syncing passes false so folding the phone never skips a wallpaper.
     */
    suspend fun reapply(
        screen: ScreenType,
        settings: ScheduleSettings,
        panel: FoldPanel? = null,
        advanceIfMissing: Boolean = true
    ): WallpaperChangeOutcome {
        if (screen == ScreenType.LIVE) return WallpaperChangeOutcome()
        return tracked(panel) { op ->
            var outcome = WallpaperChangeOutcome()
            for (target in reapplyTargets(screen, settings, op.presentationPanel)) {
                val albumId = albumFor(target, settings) ?: continue
                val bitmap = render(albumId, target.renderScreen(), panel = op.panel).getOrNull()
                if (bitmap == null && !advanceIfMissing) continue
                val result = if (bitmap == null) changeSelected(op, albumId, target) else {
                    applyBitmap(op, bitmap, target)
                    WallpaperChangeOutcome(changed = true)
                }
                outcome = combine(outcome, result)
            }
            outcome
        }
    }

    /**
     * PaperizeFold: render the current wallpaper(s) for [panel] ahead of time and keep them as
     * encoded PNGs, so a later [applyEncoded] only has to hand bytes to Android.
     */
    suspend fun renderEncoded(
        screen: ScreenType,
        settings: ScheduleSettings,
        panel: FoldPanel
    ): List<Pair<ScreenType, ByteArray>> {
        if (screen == ScreenType.LIVE) return emptyList()
        val encoded = mutableListOf<Pair<ScreenType, ByteArray>>()
        for (target in reapplyTargets(screen, settings, panel)) {
            val albumId = albumFor(target, settings) ?: continue
            val bitmap = render(albumId, target.renderScreen(), panel = panel).getOrNull() ?: continue
            try {
                currentCoroutineContext().ensureActive()
                val bytes = ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
                encoded += target to bytes
            } finally {
                bitmap.recycle()
            }
        }
        return encoded
    }

    /** PaperizeFold: apply output of [renderEncoded]. Call while holding WallpaperChangeLock. */
    suspend fun applyEncoded(encoded: List<Pair<ScreenType, ByteArray>>) {
        tracked { op ->
            for ((target, bytes) in encoded) {
                currentCoroutineContext().ensureActive()
                val id = ByteArrayInputStream(bytes).use { stream ->
                    wallpaperManager.setStream(stream, null, true, target.flags())
                }
                if (id == 0) throw IOException("WallpaperManager rejected the wallpaper")
                op.wrote(target)
            }
        }
    }

    /**
     * PaperizeFold: BOTH collapses into a single wallpaper write when home and lock would get the
     * identical image and presentation. One write means one "wallpaper changed" broadcast, so
     * apps like ColorBlendr recolor once instead of twice.
     */
    private suspend fun reapplyTargets(screen: ScreenType, settings: ScheduleSettings, panel: FoldPanel): List<ScreenType> {
        if (screen != ScreenType.BOTH) return listOf(screen)
        val albumId = settings.homeAlbumId
        if (albumId != null && albumId == settings.lockAlbumId && settings.samePresentation(panel)) {
            val homeId = wallpaperRepository.getCurrentWallpaper(albumId, ScreenType.HOME)?.id
            val lockId = wallpaperRepository.getCurrentWallpaper(albumId, ScreenType.LOCK)?.id
            if (homeId != null && homeId == lockId) return listOf(ScreenType.BOTH)
        }
        return listOf(ScreenType.HOME, ScreenType.LOCK)
    }

    private fun albumFor(target: ScreenType, settings: ScheduleSettings): String? =
        if (target == ScreenType.LOCK) settings.lockAlbumId else settings.homeAlbumId

    private fun ScreenType.renderScreen() = if (this == ScreenType.BOTH) ScreenType.HOME else this

    private suspend fun applyBitmap(
        op: Op,
        bitmap: Bitmap,
        screen: ScreenType,
        wallpaperId: String? = null,
        onApplied: suspend () -> Unit = {}
    ) {
        try {
            currentCoroutineContext().ensureActive()
            wallpaperManager.setBitmapChecked(bitmap, screen.flags())
            op.wrote(screen, wallpaperId)
            withContext(NonCancellable) { onApplied() }
        } finally {
            bitmap.recycle()
        }
    }

    private fun combine(first: WallpaperChangeOutcome, second: WallpaperChangeOutcome) = WallpaperChangeOutcome(
        changed = first.changed || second.changed, emptyAlbum = first.emptyAlbum || second.emptyAlbum
    )

    private fun ScreenType.staticScreens() = if (this == ScreenType.BOTH) listOf(ScreenType.HOME, ScreenType.LOCK) else listOf(this)

    private fun ScreenType.flags(): Int = when (this) {
        ScreenType.HOME -> WallpaperManager.FLAG_SYSTEM
        ScreenType.LOCK -> WallpaperManager.FLAG_LOCK
        ScreenType.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        ScreenType.LIVE -> error("Live wallpaper is applied by its engine")
    }
}
