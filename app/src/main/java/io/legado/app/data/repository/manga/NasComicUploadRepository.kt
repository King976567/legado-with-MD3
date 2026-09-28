package io.legado.app.data.repository.manga

import android.app.Application
import androidx.lifecycle.Observer
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.domain.gateway.NasLibraryGateway
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.model.NasComicUploadException
import io.legado.app.domain.model.NasComicUploadFailure
import io.legado.app.domain.model.NasComicUploadPreparation
import io.legado.app.domain.model.NasComicUploadStage
import io.legado.app.domain.model.NasComicUploadState
import io.legado.app.domain.model.NasHttpException
import io.legado.app.domain.model.NasWriteAccess
import io.legado.app.domain.usecase.isBookUploadEnabled
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.coil.CoverExtras
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import io.legado.app.data.repository.NasUploadTaskRepository

class NasComicUploadRepository(
    private val application: Application,
    private val database: AppDatabase,
    private val nasGateway: NasLibraryGateway,
    private val settingsGateway: NasSettingsGateway,
    private val imageLoader: ImageLoader,
) {
    private val workManager by lazy(LazyThreadSafetyMode.NONE) {
        WorkManager.getInstance(application)
    }
    private val pending = ConcurrentHashMap<String, ComicSnapshot>()

    suspend fun prepare(book: Book): NasComicUploadPreparation {
        if (book.isLocal || !book.isImage) {
            throw NasComicUploadException(NasComicUploadFailure.SourceMissing)
        }
        val settings = settingsGateway.currentSettings
        if (!settings.isBookUploadEnabled()) {
            throw NasComicUploadException(NasComicUploadFailure.NotConfigured)
        }
        val connection = try {
            nasGateway.checkConnection(settings)
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
        val source = database.bookSourceDao.getBookSource(book.origin)
            ?: throw NasComicUploadException(NasComicUploadFailure.SourceMissing)
        val originalBookUrl = book.bookUrl
        val refreshed = book.copy()
        val chapters = try {
            WebBook.getBookInfoAwait(source, refreshed, canReName = false)
            WebBook.getChapterListAwait(source, refreshed, runPerJs = true).getOrThrow()
                .filterNot(BookChapter::isVolume)
        } catch (error: Throwable) {
            throw NasComicUploadException(NasComicUploadFailure.RefreshFailed, error.message, cause = error)
        }
        if (chapters.isEmpty()) {
            throw NasComicUploadException(NasComicUploadFailure.RefreshFailed)
        }
        val key = nasComicSourceKey(book.origin, originalBookUrl)
        val remote = nasGateway.findByClientSourceKey(key, settings)
        pending[key] = ComicSnapshot(
            bookUrl = originalBookUrl,
            book = refreshed,
            cacheBook = book,
            chapters = chapters,
            clientSourceKey = key,
            configurationFingerprint = configurationFingerprint(),
            maxUploadBytes = connection.capabilities.maxUploadBytes,
        )
        return NasComicUploadPreparation(
            clientSourceKey = key,
            title = refreshed.name,
            chapterCount = chapters.size,
            remoteBook = remote,
            maxUploadBytes = connection.capabilities.maxUploadBytes,
        )
    }

    fun enqueue(
        preparation: NasComicUploadPreparation,
        replaceBookId: String?,
    ) {
        val snapshot = pending.remove(preparation.clientSourceKey)
            ?: throw NasComicUploadException(NasComicUploadFailure.RefreshFailed)
        val queuedSnapshot = snapshot.copy(
            configurationFingerprint = configurationFingerprint(),
            replaceBookId = replaceBookId,
        )
        snapshotFile(queuedSnapshot.clientSourceKey).apply {
            parentFile?.mkdirs()
            writeText(GSON.toJson(queuedSnapshot))
        }
        val createdAt = System.currentTimeMillis()
        val request = OneTimeWorkRequestBuilder<NasComicUploadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_BOOK_URL, queuedSnapshot.bookUrl)
                    .putString(KEY_SOURCE_KEY, queuedSnapshot.clientSourceKey)
                    .putString(KEY_TASK_ID, UUID.randomUUID().toString())
                    .putString(KEY_CONFIG_FINGERPRINT, queuedSnapshot.configurationFingerprint)
                    .putString(KEY_REPLACE_BOOK_ID, replaceBookId)
                    .putString(NasUploadTaskRepository.KEY_TITLE, queuedSnapshot.book.name)
                    .putString(NasUploadTaskRepository.KEY_KIND, "comic")
                    .putLong(NasUploadTaskRepository.KEY_CREATED_AT, createdAt)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    // Comic archives can contain hundreds of images; keep uploads on unmetered Wi-Fi.
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(NasUploadTaskRepository.TAG)
            .addTag(NasUploadTaskRepository.COMIC_TAG)
            .addTag(NasUploadTaskRepository.TITLE_TAG_PREFIX + queuedSnapshot.book.name.take(180))
            .addTag(NasUploadTaskRepository.CREATED_TAG_PREFIX + createdAt)
            .addTag(NasUploadTaskRepository.RETRY_KEY_TAG_PREFIX + queuedSnapshot.clientSourceKey)
            .addTag(workName(queuedSnapshot.clientSourceKey))
            .apply {
                replaceBookId?.takeIf(String::isNotBlank)?.let {
                    addTag(NasUploadTaskRepository.REPLACE_BOOK_ID_TAG_PREFIX + it)
                }
            }
            .build()
        workManager.enqueueUniqueWork(
            workName(queuedSnapshot.clientSourceKey),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    suspend fun retry(sourceKey: String) {
        val previous = loadSnapshot(sourceKey)
            ?: throw NasComicUploadException(NasComicUploadFailure.RefreshFailed)
        val preparation = prepare(previous.book)
        if (preparation.clientSourceKey != sourceKey) {
            discardPreparation(preparation.clientSourceKey)
            throw NasComicUploadException(NasComicUploadFailure.RefreshFailed)
        }
        val remoteId = preparation.remoteBook?.id
        if (remoteId != previous.replaceBookId &&
            (remoteId != null || previous.replaceBookId != null)
        ) {
            discardPreparation(sourceKey)
            throw NasComicUploadException(NasComicUploadFailure.RemoteChanged)
        }
        enqueue(preparation, previous.replaceBookId)
    }

    fun discardPreparation(clientSourceKey: String) {
        pending.remove(clientSourceKey)
    }

    fun cancel(clientSourceKey: String) {
        pending.remove(clientSourceKey)
        workManager.cancelUniqueWork(workName(clientSourceKey))
    }

    fun observe(clientSourceKey: String): Flow<NasComicUploadState> = callbackFlow {
        val liveData = workManager.getWorkInfosForUniqueWorkLiveData(workName(clientSourceKey))
        val observer = Observer<List<WorkInfo>> { infos ->
            trySend(infos.maxByOrNull { it.runAttemptCount }?.toComicState() ?: NasComicUploadState())
        }
        liveData.observeForever(observer)
        awaitClose { liveData.removeObserver(observer) }
    }.distinctUntilChanged()

    internal fun loadSnapshot(sourceKey: String): ComicSnapshot? = snapshotFile(sourceKey)
        .takeIf(File::isFile)
        ?.readText()
        ?.let { runCatching { GSON.fromJson(it, ComicSnapshot::class.java) }.getOrNull() }

    internal fun taskDirectory(sourceKey: String): File =
        File(application.noBackupFilesDir, "nas-comic/$sourceKey").apply { mkdirs() }

    internal fun cleanup(sourceKey: String) {
        taskDirectory(sourceKey).deleteRecursively()
    }

    internal fun cleanupForRetry(sourceKey: String) {
        taskDirectory(sourceKey).listFiles().orEmpty()
            .filterNot { it.name == SNAPSHOT_FILE }
            .forEach(File::deleteRecursively)
    }

    internal fun cleanupRetryDataIfIdle(sourceKey: String): Boolean {
        if (sourceKey.isBlank() || hasActiveWork(sourceKey)) return false
        val root = File(application.noBackupFilesDir, "nas-comic")
        File(root, sourceKey).deleteRecursively()
        if (root.list().isNullOrEmpty()) root.delete()
        return true
    }

    internal fun cleanupAllRetryData() {
        val root = File(application.noBackupFilesDir, "nas-comic")
        root.listFiles().orEmpty()
            .filter(File::isDirectory)
            .filterNot { hasActiveWork(it.name) }
            .forEach(File::deleteRecursively)
        if (root.list().isNullOrEmpty()) root.delete()
    }

    private fun hasActiveWork(sourceKey: String): Boolean = runCatching {
        workManager.getWorkInfosForUniqueWork(workName(sourceKey)).get()
            .any { !it.state.isFinished }
    }.getOrElse {
        // Query failures must preserve task data rather than risking a live upload.
        true
    }

    internal fun currentSettings() = settingsGateway.currentSettings

    internal fun gateway() = nasGateway

    internal fun database() = database

    /**
     * Resolve the book's actual source cover using the same Coil pipeline as
     * the manga details page. The first comic page is deliberately not used as
     * a fallback: it is content, not the cover, and uploading it would leave a
     * misleading NAS thumbnail.
     */
    internal suspend fun prepareCoverFile(book: Book, sourceKey: String): File? {
        val coverUrl = book.getDisplayCover()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val result = imageLoader.execute(
            ImageRequest.Builder(application)
                .data(coverUrl)
                .allowHardware(false)
                .apply {
                    extras[CoverExtras.SourceOrigin] = book.origin
                    extras[CoverExtras.BookUrl] = book.bookUrl
                }
                .build(),
        )
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return null
        val file = File(taskDirectory(sourceKey), "cover.jpg")
        file.parentFile?.mkdirs()
        ByteArrayOutputStream().use { bytes ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, bytes)) {
                "封面图片编码失败"
            }
            file.outputStream().use { output -> bytes.writeTo(output) }
        }
        return file.takeIf(File::isFile)
    }

    internal fun configurationFingerprint(): String {
        val settings = settingsGateway.currentSettings
        val normalized = nasGateway.normalizeUrl(settings.apiUrl).orEmpty()
        return MessageDigest.getInstance("SHA-256")
            .digest("$normalized\u0000${settings.apiToken.trim()}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun snapshotFile(sourceKey: String) = File(taskDirectory(sourceKey), SNAPSHOT_FILE)

    internal data class ComicSnapshot(
        val bookUrl: String,
        val book: Book,
        /** The persisted book identity whose folder contains the existing image cache. */
        val cacheBook: Book? = null,
        val chapters: List<BookChapter>,
        val clientSourceKey: String,
        val configurationFingerprint: String,
        val maxUploadBytes: Long,
        val replaceBookId: String? = null,
    )

    companion object {
        internal const val KEY_BOOK_URL = "book_url"
        internal const val KEY_SOURCE_KEY = "client_source_key"
        internal const val KEY_TASK_ID = "task_id"
        internal const val KEY_CONFIG_FINGERPRINT = "configuration_fingerprint"
        internal const val KEY_REPLACE_BOOK_ID = "replace_book_id"
        internal const val KEY_STAGE = "stage"
        internal const val KEY_CHAPTER = "chapter"
        internal const val KEY_CHAPTER_COUNT = "chapter_count"
        internal const val KEY_PAGE = "page"
        internal const val KEY_PAGE_COUNT = "page_count"
        internal const val KEY_FAILURE = "failure"
        internal const val KEY_MESSAGE = "message"
        internal const val KEY_REMOTE_PATH = "remote_path"
        internal const val KEY_WARNING = "warning"
        internal const val SNAPSHOT_FILE = "snapshot.json"

        internal fun workName(sourceKey: String) = "nas-comic:$sourceKey"
    }
}

private fun WorkInfo.toComicState(): NasComicUploadState {
    val values = if (state == WorkInfo.State.SUCCEEDED || state == WorkInfo.State.FAILED) outputData else progress
    val stage = values.getString(NasComicUploadRepository.KEY_STAGE)
        ?.let { runCatching { NasComicUploadStage.valueOf(it) }.getOrNull() }
    val failure = values.getString(NasComicUploadRepository.KEY_FAILURE)
        ?.let { runCatching { NasComicUploadFailure.valueOf(it) }.getOrNull() }
    return NasComicUploadState(
        running = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED || state == WorkInfo.State.RUNNING,
        stage = stage,
        chapter = values.getInt(NasComicUploadRepository.KEY_CHAPTER, 0),
        chapterCount = values.getInt(NasComicUploadRepository.KEY_CHAPTER_COUNT, 0),
        page = values.getInt(NasComicUploadRepository.KEY_PAGE, 0),
        pageCount = values.getInt(NasComicUploadRepository.KEY_PAGE_COUNT, 0),
        cacheReused = values.getInt(NasUploadTaskRepository.KEY_CACHE_REUSED, 0),
        downloaded = values.getInt(NasUploadTaskRepository.KEY_DOWNLOADED, 0),
        failure = failure,
        message = values.getString(NasComicUploadRepository.KEY_MESSAGE),
        remotePath = values.getString(NasComicUploadRepository.KEY_REMOTE_PATH),
        warning = values.getString(NasComicUploadRepository.KEY_WARNING),
    )
}
