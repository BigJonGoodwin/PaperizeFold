package com.anthonyla.paperize.service.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.service.WallpaperChangeLock
import com.anthonyla.paperize.service.quiet.QuietChangeGate
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.anthonyla.paperize.service.wallpaper.WallpaperController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

@HiltWorker
class WallpaperChangeWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val wallpaperController: WallpaperController,
    private val settingsRepository: SettingsRepository,
    private val wallpaperChangeLock: WallpaperChangeLock,
    private val quietChangeGate: QuietChangeGate
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val screenType = inputData.getString(Constants.EXTRA_SCREEN_TYPE)
                ?.let(ScreenType::fromString)
                ?: ScreenType.HOME

            // PaperizeFold: don't change (and trigger a recolor) while the screen is on or media
            // is playing. The gate runs this change later, when it won't interrupt anything.
            if (quietChangeGate.deferScheduledIfNeeded(screenType)) {
                Log.d(TAG, "Deferred wallpaper change for $screenType")
                return Result.success()
            }

            Log.d(TAG, "Starting wallpaper change for $screenType")
            wallpaperChangeLock.mutex.withLock {
                wallpaperController.change(screenType, settingsRepository.getScheduleSettings())
            }
            Log.d(TAG, "Wallpaper change completed successfully for $screenType")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error changing wallpaper", e)
            if (runAttemptCount < Constants.MAX_WORK_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private companion object {
        const val TAG = "WallpaperChangeWorker"
    }
}
