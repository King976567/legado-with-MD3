package io.legado.app.data.repository

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.AppDatabase
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.usecase.NasBookUploadError
import io.legado.app.domain.usecase.NasBookUploadException
import io.legado.app.domain.usecase.NasBookUploadStage
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

class NasLocalBookUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val title = inputData.getString(NasUploadTaskRepository.KEY_TITLE).orEmpty()
    private val bookUrl = inputData.getString(KEY_BOOK_URL).orEmpty()

    override suspend fun doWork(): Result {
        if (bookUrl.isBlank()) return Result.failure(messageData("Missing book URL"))
        val database = GlobalContext.get().get<AppDatabase>()
        val book = database.bookDao.getBook(bookUrl) ?: return Result.failure(messageData("Book not found"))
        val repository = GlobalContext.get().get<NasLocalBookUploadRepository>()
        val settings = GlobalContext.get().get<NasSettingsGateway>().currentSettings
        return try {
            update(NasBookUploadStage.Preparing)
            val outcome = repository.upload(book, settings) { stage ->
                updateBlocking(stage)
            }
            Result.success(
                Data.Builder()
                    .putString(NasUploadTaskRepository.KEY_STAGE, "Complete")
                    .putString(NasUploadTaskRepository.KEY_REMOTE_PATH, outcome.path)
                    .build(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: NasBookUploadException) {
            val retryable = error.reason == NasBookUploadError.Unavailable ||
                error.reason == NasBookUploadError.UploadFailed
            if (retryable && runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure(messageData(error.message ?: error.reason.name))
            }
        } catch (error: Throwable) {
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure(messageData(error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private suspend fun update(stage: NasBookUploadStage) {
        val stageName = stage.name
        val data = Data.Builder()
            .putString(NasUploadTaskRepository.KEY_STAGE, stageName)
            .build()
        setProgress(data)
        setForeground(createForegroundInfo(stageName))
    }

    private fun updateBlocking(stage: NasBookUploadStage) {
        val data = Data.Builder()
            .putString(NasUploadTaskRepository.KEY_STAGE, stage.name)
            .build()
        setProgressAsync(data)
        setForegroundAsync(createForegroundInfo(stage.name))
    }

    private fun createForegroundInfo(stage: String): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_export)
            .setContentTitle(applicationContext.getString(R.string.feature_book_info_upload_nas))
            .setContentText("$title · $stage")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_stop_black_24dp,
                applicationContext.getString(R.string.cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId(), notification)
        }
    }

    private fun messageData(message: String) =
        Data.Builder().putString(NasUploadTaskRepository.KEY_MESSAGE, message).build()

    private fun notificationId() = 0x4e420000 or (bookUrl.hashCode() and 0xffff)

    companion object {
        const val KEY_BOOK_URL = "nas_upload_book_url"
        const val WORK_PREFIX = "nas-book:"
        private const val MAX_RETRIES = 3
    }
}
