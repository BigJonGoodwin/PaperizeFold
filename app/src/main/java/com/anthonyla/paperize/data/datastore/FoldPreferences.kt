package com.anthonyla.paperize.data.datastore

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** PaperizeFold settings (fold sync and quiet changes). */
data class FoldSettings(
    /** Copy the current wallpaper to whichever screen becomes active on fold/unfold. */
    val foldSyncEnabled: Boolean = true,
    /** Prepare the cover version ahead of time so folding is near-instant. */
    val prerenderCover: Boolean = true,
    /** Hold the cover sync while media is playing. */
    val foldWaitForMedia: Boolean = true,
    /** Scheduled changes wait until the screen is off. */
    val waitForScreenOff: Boolean = true,
    /** Scheduled changes and effect edits wait until nothing is playing. */
    val waitForMedia: Boolean = true,
    /** How long to wait for media before changing anyway. */
    val mediaWaitMinutes: Int = DEFAULT_MEDIA_WAIT_MINUTES,
    /** Collect effect/scaling edits and apply them once after leaving the app. */
    val deferEffects: Boolean = true
) {
    companion object {
        const val DEFAULT_MEDIA_WAIT_MINUTES = 30
        const val MIN_MEDIA_WAIT_MINUTES = 5
        const val MAX_MEDIA_WAIT_MINUTES = 120
        const val MEDIA_WAIT_STEP_MINUTES = 5
    }
}

/**
 * Stored in plain SharedPreferences so the background service and the scheduled worker can read
 * the current values instantly, without suspending.
 */
@Singleton
class FoldPreferences @Inject constructor(@param:ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<FoldSettings> = _settings.asStateFlow()

    val current: FoldSettings get() = _settings.value

    @Synchronized
    fun update(transform: (FoldSettings) -> FoldSettings) {
        val updated = transform(_settings.value).let {
            it.copy(
                mediaWaitMinutes = it.mediaWaitMinutes.coerceIn(
                    FoldSettings.MIN_MEDIA_WAIT_MINUTES,
                    FoldSettings.MAX_MEDIA_WAIT_MINUTES
                )
            )
        }
        prefs.edit {
            putBoolean(KEY_FOLD_SYNC, updated.foldSyncEnabled)
            putBoolean(KEY_PRERENDER, updated.prerenderCover)
            putBoolean(KEY_FOLD_WAIT_MEDIA, updated.foldWaitForMedia)
            putBoolean(KEY_WAIT_SCREEN_OFF, updated.waitForScreenOff)
            putBoolean(KEY_WAIT_MEDIA, updated.waitForMedia)
            putInt(KEY_MEDIA_WAIT_MINUTES, updated.mediaWaitMinutes)
            putBoolean(KEY_DEFER_EFFECTS, updated.deferEffects)
        }
        _settings.value = updated
    }

    private fun read(): FoldSettings {
        val defaults = FoldSettings()
        return FoldSettings(
            foldSyncEnabled = prefs.getBoolean(KEY_FOLD_SYNC, defaults.foldSyncEnabled),
            prerenderCover = prefs.getBoolean(KEY_PRERENDER, defaults.prerenderCover),
            foldWaitForMedia = prefs.getBoolean(KEY_FOLD_WAIT_MEDIA, defaults.foldWaitForMedia),
            waitForScreenOff = prefs.getBoolean(KEY_WAIT_SCREEN_OFF, defaults.waitForScreenOff),
            waitForMedia = prefs.getBoolean(KEY_WAIT_MEDIA, defaults.waitForMedia),
            mediaWaitMinutes = prefs.getInt(KEY_MEDIA_WAIT_MINUTES, defaults.mediaWaitMinutes),
            deferEffects = prefs.getBoolean(KEY_DEFER_EFFECTS, defaults.deferEffects)
        )
    }

    private companion object {
        const val PREFS_NAME = "paperize_fold"
        const val KEY_FOLD_SYNC = "fold_sync_enabled"
        const val KEY_PRERENDER = "prerender_cover"
        const val KEY_FOLD_WAIT_MEDIA = "fold_wait_for_media"
        const val KEY_WAIT_SCREEN_OFF = "wait_for_screen_off"
        const val KEY_WAIT_MEDIA = "wait_for_media"
        const val KEY_MEDIA_WAIT_MINUTES = "media_wait_minutes"
        const val KEY_DEFER_EFFECTS = "defer_effects"
    }
}
