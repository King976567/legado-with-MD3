package io.legado.app.data.repository.manga

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BookChapter
import io.legado.app.domain.gateway.NasUploadSource
import io.legado.app.domain.model.NasComicUploadException
import io.legado.app.domain.model.NasComicUploadFailure
import io.legado.app.domain.model.NasComicUploadStage
import io.legado.app.domain.model.NasHttpException
import io.legado.app.domain.model.NasWriteAccess
import io.legado.app.domain.usecase.isBookUploadEnabled
import io.legado.app.data.repository.NasUploadTaskRepository
import io.legado.app.help.book.BookHelp
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.toList
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.IOException

class NasComicUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository: NasComicUploadRepository = GlobalContext.get().get()
    private val sourceKey = inputData.getString(NasComicUploadRepository.KEY_SOURCE_KEY).orEmpty()

    override suspend fun doWork(): Result {
        if (sourceKey.isBlank()) return Result.failure(failureData(NasComicUploadFailure.Unknown, "Missing source key"))
        return try {
            execute()
        } catch (cancelled: CancellationException) {
            repository.cleanupForRetry(sourceKey)
            throw cancelled
        } catch (error: NasComicUploadException) {
            if (error.retryable && runAttemptCount < MAX_RETRIES) {
                setProgress(failureData(error.reason, error.message))
                Result.retry()
            } else {
                repository.cleanupForRetry(sourceKey)
                Result.failure(failureData(error.reason, error.message))
            }
        } catch (error: IOException) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else {
                repository.cleanupForRetry(sourceKey)
                Result.failure(failureData(NasComicUploadFailure.Network, error.message))
            }
        } catch (error: Throwable) {
            repository.cleanupForRetry(sourceKey)
            Result.failure(failureData(NasComicUploadFailure.Unknown, error.message))
        }
    }

    private suspend fun execute(): Result {
        val snapshot = repository.loadSnapshot(sourceKey)
            ?: throw NasComicUploadException(NasComicUploadFailure.RefreshFailed)
        if (snapshot.bookUrl != inputData.getString(NasComicUploadRepository.KEY_BOOK_URL) ||
            snapshot.configurationFingerprint != inputData.getString(NasComicUploadRepository.KEY_CONFIG_FINGERPRINT) ||
            snapshot.configurationFingerprint != repository.configurationFingerprint()
        ) {
            throw NasComicUploadException(NasComicUploadFailure.ConfigurationChanged)
        }
        val settings = repository.currentSettings()
        if (!settings.isBookUploadEnabled()) throw NasComicUploadException(NasComicUploadFailure.NotConfigured)
        update(NasComicUploadStage.Checking, chapterCount = snapshot.chapters.size)
        val connection = try {
            repository.gateway().checkConnection(settings)
        } catch (error: NasHttpException) {
            if (error.isUnauthorized || error.isForbidden) {
                throw NasComicUploadException(NasComicUploadFailure.Unauthorized, cause = error)
            }
            throw NasComicUploadException(NasComicUploadFailure.Network, error.message, true, error)
        }
        if (connection.writeAccess == NasWriteAccess.DENIED) {
            throw NasComicUploadException(NasComicUploadFailure.ReadOnly)
        }
        if (!connection.capabilities.supportsComicCbzUpload) {
            throw NasComicUploadException(NasComicUploadFailure.UnsupportedBackend)
        }
        val replaceBookId = inputData.getString(NasComicUploadRepository.KEY_REPLACE_BOOK_ID)
            ?.takeIf(String::isNotBlank)
        val remote = repository.gateway().findByClientSourceKey(sourceKey, settings)
        if ((replaceBookId == null && remote != null) || (replaceBookId != null && remote?.id != replaceBookId)) {
            throw NasComicUploadException(NasComicUploadFailure.RemoteChanged)
        }
        val source = repository.database().bookSourceDao.getBookSource(snapshot.book.origin)
            ?: throw NasComicUploadException(NasComicUploadFailure.SourceMissing)
        // Refreshing metadata may change the title and therefore Book.getFolderName().
        // Keep all cache reads/writes anchored to the persisted book identity.
        val cacheBook = snapshot.cacheBook ?: snapshot.book
        val persistedChapters = repository.database().bookChapterDao
            .getChapterList(snapshot.bookUrl)
        val persistedByUrl = persistedChapters.associateBy { it.url }
        val chapterPlans = snapshot.chapters.mapIndexed { chapterIndex, chapter ->
            currentCoroutineContext().ensureActive()
            update(
                NasComicUploadStage.Refreshing,
                chapterIndex + 1,
                snapshot.chapters.size,
            )
            val nextUrl = snapshot.chapters.getOrNull(chapterIndex + 1)?.url
            // Keep the local chapter title/index so existing .nb and image cache
            // paths remain stable even when the source changes its chapter title.
            val cacheChapter = persistedByUrl[chapter.url]?.let { persisted ->
                chapter.copy(
                    bookUrl = snapshot.bookUrl,
                    title = persisted.title,
                    index = persisted.index,
                )
            } ?: chapter.copy(bookUrl = snapshot.bookUrl)
            val content = BookHelp.getContent(cacheBook, cacheChapter) ?: run {
                // A detached bookUrl prevents title-rule parsing from updating
                // the persisted chapter row during an upload-only refresh.
                val fetchChapter = cacheChapter.copy(bookUrl = "${snapshot.bookUrl}#nas-upload")
                val fetched = WebBook.getContentAwait(
                    source,
                    cacheBook,
                    fetchChapter,
                    nextUrl,
                    needSave = false,
                )
                BookHelp.saveText(cacheBook, cacheChapter, fetched)
                fetched
            }
            val urls = BookHelp.flowImages(cacheChapter, content).distinctUntilChanged().toList()
            if (urls.isEmpty()) throw NasComicUploadException(NasComicUploadFailure.EmptyChapter)
            ComicChapterPlan(cacheChapter, urls)
        }
        val totalPages = chapterPlans.sumOf { it.urls.size }
        var completedPages = 0
        var cacheReused = 0
        var downloaded = 0
        val archiveChapters = chapterPlans.mapIndexed { chapterIndex, plan ->
            currentCoroutineContext().ensureActive()
            val pages = plan.urls.mapIndexed { pageIndex, url ->
                val wasCached = BookHelp.isImageExist(cacheBook, url)
                val saved = if (wasCached) {
                    cacheReused += 1
                    true
                } else {
                    try {
                        BookHelp.saveImage(source, cacheBook, url, plan.chapter)
                    } catch (error: Throwable) {
                        throw NasComicUploadException(NasComicUploadFailure.ImageFailed, error.message, true, error)
                    }.also { if (it) downloaded += 1 }
                }
                if (!saved) throw NasComicUploadException(NasComicUploadFailure.ImageFailed)
                val image = BookHelp.getImage(cacheBook, url).takeIf(File::isFile)
                    ?: throw NasComicUploadException(NasComicUploadFailure.ImageFailed)
                update(
                    NasComicUploadStage.Downloading,
                    chapterIndex + 1,
                    chapterPlans.size,
                    pageIndex + 1,
                    plan.urls.size,
                    cacheReused,
                    downloaded,
                    completedPages + pageIndex + 1,
                    totalPages,
                )
                image
            }
            completedPages += plan.urls.size
            NasComicArchiveChapter(plan.chapter.title, pages)
        }
        update(
            NasComicUploadStage.Packaging,
            chapterCount = snapshot.chapters.size,
            overallCurrent = totalPages,
            overallTotal = totalPages,
        )
        val fileName = sanitizeCbzPathSegment(snapshot.book.name, "comic") + ".cbz"
        val archive = try {
            NasComicCbzBuilder.build(
                output = File(repository.taskDirectory(sourceKey), fileName),
                title = snapshot.book.name,
                author = snapshot.book.author,
                summary = snapshot.book.getDisplayIntro().orEmpty(),
                chapters = archiveChapters,
                maxBytes = listOf(snapshot.maxUploadBytes, connection.capabilities.maxUploadBytes)
                    .filter { it > 0 }.minOrNull() ?: 0,
            ) { chapter, chapterCount, page, pageCount ->
                setProgressAsync(
                    progressData(
                        NasComicUploadStage.Packaging,
                        chapter,
                        chapterCount,
                        page,
                        pageCount,
                        overallCurrent = totalPages,
                        overallTotal = totalPages,
                    ),
                )
            }
        } catch (error: NasComicArchiveException) {
            throw NasComicUploadException(
                if (error.reason == NasComicArchiveError.StorageFull) NasComicUploadFailure.StorageFull
                else NasComicUploadFailure.TooLarge,
            )
        }
        update(
            NasComicUploadStage.Uploading,
            chapterCount = snapshot.chapters.size,
            overallCurrent = totalPages,
            overallTotal = totalPages,
        )
        val upload = repository.gateway().uploadBook(
            source = NasUploadSource { sink ->
                archive.file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isStopped) throw CancellationException("NAS comic upload cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                    }
                }
            },
            fileName = archive.file.name,
            contentLength = archive.size,
            duplicatePolicy = if (replaceBookId == null) "skip" else "overwrite",
            title = snapshot.book.name,
            author = snapshot.book.author,
            intro = snapshot.book.getDisplayIntro().orEmpty(),
            directoryPath = connection.capabilities.defaultUploadDirectory.trim('/'),
            settings = settings,
            clientSourceKey = sourceKey,
            chapterCount = snapshot.chapters.size,
            replaceBookId = replaceBookId,
        )
        if (upload.failed.isNotEmpty()) {
            throw NasComicUploadException(NasComicUploadFailure.Unknown, upload.failed.joinToString())
        }
        val uploaded = upload.primaryBook ?: upload.duplicates.firstOrNull()
            ?: throw NasComicUploadException(NasComicUploadFailure.Unknown)
        var warning: String? = null
        if (connection.capabilities.supports("coverUpload")) {
            update(
                NasComicUploadStage.UploadingCover,
                chapterCount = snapshot.chapters.size,
                overallCurrent = totalPages,
                overallTotal = totalPages,
            )
            val coverFile = runCatching {
                repository.prepareCoverFile(snapshot.book, sourceKey)
            }.getOrNull()
            if (coverFile != null) {
                runCatching {
                    repository.gateway().uploadCover(
                        id = uploaded.id,
                        source = NasUploadSource { sink ->
                            coverFile.inputStream().use { input ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    sink.write(buffer, 0, count)
                                }
                            }
                        },
                        fileName = "cover.jpg",
                        contentLength = coverFile.length(),
                        settings = settings,
                    )
                }.onFailure { warning = it.message ?: "Cover upload failed" }
            }
        }
        val path = uploaded.relativePath.ifBlank { uploaded.fileName }
        repository.cleanup(sourceKey)
        return Result.success(
            Data.Builder()
                .putAll(
                    progressData(
                        NasComicUploadStage.Complete,
                        chapterCount = snapshot.chapters.size,
                        overallCurrent = totalPages,
                        overallTotal = totalPages,
                    ),
                )
                .putString(NasComicUploadRepository.KEY_REMOTE_PATH, path)
                .putString(NasComicUploadRepository.KEY_WARNING, warning)
                .build(),
        )
    }

    private suspend fun update(
        stage: NasComicUploadStage,
        chapter: Int = 0,
        chapterCount: Int = 0,
        page: Int = 0,
        pageCount: Int = 0,
        cacheReused: Int = 0,
        downloaded: Int = 0,
        overallCurrent: Int = 0,
        overallTotal: Int = 0,
    ) {
        val data = progressData(
            stage,
            chapter,
            chapterCount,
            page,
            pageCount,
            cacheReused,
            downloaded,
            overallCurrent,
            overallTotal,
        )
        setProgress(data)
        setForeground(
            createForegroundInfo(
                stage,
                chapter,
                chapterCount,
                page,
                pageCount,
                cacheReused,
                downloaded,
                overallCurrent,
                overallTotal,
            ),
        )
    }

    private fun createForegroundInfo(
        stage: NasComicUploadStage,
        chapter: Int,
        chapterCount: Int,
        page: Int,
        pageCount: Int,
        cacheReused: Int,
        downloaded: Int,
        overallCurrent: Int,
        overallTotal: Int,
    ): ForegroundInfo {
        val text = buildString {
            append(stageText(stage))
            if (chapterCount > 0) append("  $chapter/$chapterCount")
            if (pageCount > 0) append("  $page/$pageCount")
            if (cacheReused > 0 || downloaded > 0) {
                append("  ")
                append(applicationContext.getString(
                    R.string.feature_book_info_nas_comic_cache_stats,
                    cacheReused,
                    downloaded,
                ))
            }
        }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification: Notification = NotificationCompat.Builder(applicationContext, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_export)
            .setContentTitle(applicationContext.getString(R.string.feature_book_info_nas_comic_notification))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(
                overallTotal.coerceAtLeast(0),
                overallCurrent.coerceAtLeast(0),
                overallTotal <= 0,
            )
            .addAction(R.drawable.ic_stop_black_24dp, applicationContext.getString(R.string.cancel), cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId(), notification)
        }
    }

    private fun stageText(stage: NasComicUploadStage): String = applicationContext.getString(
        when (stage) {
            NasComicUploadStage.Refreshing -> R.string.feature_book_info_nas_comic_stage_refreshing
            NasComicUploadStage.Downloading -> R.string.feature_book_info_nas_comic_stage_downloading
            NasComicUploadStage.Packaging -> R.string.feature_book_info_nas_comic_stage_packaging
            NasComicUploadStage.Checking -> R.string.feature_book_info_nas_comic_stage_checking
            NasComicUploadStage.Uploading -> R.string.feature_book_info_nas_comic_stage_uploading
            NasComicUploadStage.UploadingCover -> R.string.feature_book_info_nas_comic_stage_cover
            NasComicUploadStage.Complete -> R.string.feature_book_info_nas_comic_stage_complete
        },
    )

    private fun notificationId(): Int = 0x4e410000 or (sourceKey.hashCode() and 0xffff)

    private fun failureData(reason: NasComicUploadFailure, message: String?): Data = Data.Builder()
        .putString(NasComicUploadRepository.KEY_FAILURE, reason.name)
        .putString(NasComicUploadRepository.KEY_MESSAGE, message)
        .putString(NasUploadTaskRepository.KEY_MESSAGE, message)
        .putString(NasUploadTaskRepository.KEY_TITLE, inputData.getString(NasUploadTaskRepository.KEY_TITLE).orEmpty())
        .putString(NasUploadTaskRepository.KEY_KIND, "comic")
        .build()

    private fun progressData(
        stage: NasComicUploadStage,
        chapter: Int = 0,
        chapterCount: Int = 0,
        page: Int = 0,
        pageCount: Int = 0,
        cacheReused: Int = 0,
        downloaded: Int = 0,
        overallCurrent: Int = 0,
        overallTotal: Int = 0,
    ): Data = Data.Builder()
        .putString(NasComicUploadRepository.KEY_STAGE, stage.name)
        .putInt(NasComicUploadRepository.KEY_CHAPTER, chapter)
        .putInt(NasComicUploadRepository.KEY_CHAPTER_COUNT, chapterCount)
        .putInt(NasComicUploadRepository.KEY_PAGE, page)
        .putInt(NasComicUploadRepository.KEY_PAGE_COUNT, pageCount)
        .putInt(NasUploadTaskRepository.KEY_CURRENT, if (overallTotal > 0) overallCurrent else if (page > 0) page else chapter)
        .putInt(NasUploadTaskRepository.KEY_TOTAL, if (overallTotal > 0) overallTotal else if (pageCount > 0) pageCount else chapterCount)
        .putInt(NasUploadTaskRepository.KEY_OVERALL_CURRENT, overallCurrent)
        .putInt(NasUploadTaskRepository.KEY_OVERALL_TOTAL, overallTotal)
        .putInt(NasUploadTaskRepository.KEY_CACHE_REUSED, cacheReused)
        .putInt(NasUploadTaskRepository.KEY_DOWNLOADED, downloaded)
        .putString(NasUploadTaskRepository.KEY_TITLE, inputData.getString(NasUploadTaskRepository.KEY_TITLE).orEmpty())
        .putString(NasUploadTaskRepository.KEY_KIND, "comic")
        .build()

    private companion object {
        const val MAX_RETRIES = 3
    }

    private data class ComicChapterPlan(
        val chapter: BookChapter,
        val urls: List<String>,
    )
}
