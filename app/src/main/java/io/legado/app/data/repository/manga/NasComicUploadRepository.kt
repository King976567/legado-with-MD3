package io.legado.app.data.repository.manga

import android.app.Application
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.gson.annotations.SerializedName
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.domain.gateway.NasLibraryGateway
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.model.NasBookDetail
import io.legado.app.domain.model.NasComicUploadException
import io.legado.app.domain.model.NasComicUploadFailure
import io.legado.app.domain.model.NasComicUploadPreparation
import io.legado.app.domain.model.NasComicUploadStage
import io.legado.app.domain.model.NasComicUploadState
import io.legado.app.domain.model.NasHttpException
import io.legado.app.domain.model.NasWriteAccess
import io.legado.app.domain.model.normalizeNasSha256
import io.legado.app.domain.model.settings.NasSettings
import io.legado.app.domain.usecase.isBookUploadEnabled
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.coil.CoverExtras
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import io.legado.app.data.repository.NasUploadTaskRepository
import io.legado.app.data.repository.enqueueNasUpload

class NasComicUploadRepository(
    private val application: Application,
    private val database: AppDatabase,
    private val nasGateway: NasLibraryGateway,
    private val settingsGateway: NasSettingsGateway,
    private val imageLoader: ImageLoader,
) {
    private val workManager by lazy {
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw NasComicUploadException(NasComicUploadFailure.RefreshFailed, error.message, cause = error)
        }
        if (chapters.isEmpty()) {
            throw NasComicUploadException(NasComicUploadFailure.RefreshFailed)
        }
        val key = nasComicSourceKey(book.origin, originalBookUrl)
        val remote = nasGateway.findByClientSourceKey(key, settings)
        val snapshot = ComicSnapshot(
            bookUrl = originalBookUrl,
            book = refreshed,
            cacheBook = book,
            chapters = chapters,
            clientSourceKey = key,
            configurationFingerprint = configurationFingerprint(settings),
            maxUploadBytes = connection.capabilities.maxUploadBytes,
            expectedRemoteHash = normalizeNasSha256(remote?.contentHash),
        )
        return NasComicUploadPreparation(
            preparationId = UUID.randomUUID().toString(),
            clientSourceKey = key,
            title = refreshed.name,
            chapterCount = chapters.size,
            remoteBook = remote,
            maxUploadBytes = connection.capabilities.maxUploadBytes,
        ).also { pending[it.preparationId] = snapshot }
    }

    suspend fun enqueue(
        preparation: NasComicUploadPreparation,
        replaceBookId: String?,
    ): UUID = withContext(Dispatchers.IO) {
        val snapshot = pending.remove(preparation.preparationId)
            ?: throw NasComicUploadException(NasComicUploadFailure.RefreshFailed)
        if (snapshot.configurationFingerprint != configurationFingerprint(currentSettings())) {
            throw NasComicUploadException(NasComicUploadFailure.ConfigurationChanged)
        }
        if (!matchesComicReplacement(replaceBookId, snapshot.expectedRemoteHash, preparation.remoteBook)) {
            throw NasComicUploadException(NasComicUploadFailure.RemoteChanged)
        }
        val queuedSnapshot = snapshot.copy(replaceBookId = replaceBookId)
        val createdAt = System.currentTimeMillis()
        val request = OneTimeWorkRequestBuilder<NasComicUploadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_BOOK_URL, queuedSnapshot.bookUrl)
                    .putString(KEY_SOURCE_KEY, queuedSnapshot.clientSourceKey)
                    .putBoolean(KEY_ISOLATED_SNAPSHOT, true)
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
            .keepResultsForAtLeast(7, TimeUnit.DAYS)
            .addTag(NasUploadTaskRepository.TAG)
            .addTag(NasUploadTaskRepository.COMIC_TAG)
            .addTag(NasUploadTaskRepository.TITLE_TAG_PREFIX + queuedSnapshot.book.name.take(180))
            .addTag(NasUploadTaskRepository.CREATED_TAG_PREFIX + createdAt)
            .addTag(NasUploadTaskRepository.RETRY_KEY_TAG_PREFIX + SCOPED_RETRY_PREFIX + queuedSnapshot.clientSourceKey)
            .addTag(workName(queuedSnapshot.clientSourceKey))
            .apply {
                replaceBookId?.takeIf(String::isNotBlank)?.let {
                    addTag(NasUploadTaskRepository.REPLACE_BOOK_ID_TAG_PREFIX + it)
                }
            }
            .build()
        val file = snapshotFile(queuedSnapshot.clientSourceKey, request.id)
        try {
            FileUtils.writeTextAtomic(file.absolutePath, GSON.toJson(queuedSnapshot))
            workManager.enqueueNasUpload(workName(queuedSnapshot.clientSourceKey), request, application)
        } catch (error: Exception) {
            // Do not remove a snapshot if enqueue may have committed despite the error.
            val queued = runCatching { workManager.getWorkInfoById(request.id).get() != null }.getOrDefault(true)
            if (!queued) file.parentFile?.deleteRecursively()
            throw error
        }
        request.id
    }

    suspend fun retry(retryKey: String, taskId: UUID, replaceBookId: String?) {
        val sourceKey = retryKey.removePrefix(SCOPED_RETRY_PREFIX)
        // Legacy tasks used one shared snapshot. Never fall back to it for a scoped request.
        val snapshotId = taskId.takeIf { retryKey.startsWith(SCOPED_RETRY_PREFIX) }
        val previous = loadSnapshot(sourceKey, snapshotId)
            ?: throw NasComicUploadException(NasComicUploadFailure.RefreshFailed)
        if (previous.configurationFingerprint != configurationFingerprint(currentSettings())) {
            throw NasComicUploadException(NasComicUploadFailure.ConfigurationChanged)
        }
        if (previous.replaceBookId != replaceBookId ||
            (replaceBookId != null && normalizeNasSha256(previous.expectedRemoteHash) == null)
        ) {
            throw NasComicUploadException(NasComicUploadFailure.RemoteChanged)
        }
        val preparation = prepare(previous.cacheBook ?: previous.book)
        try {
            if (preparation.clientSourceKey != sourceKey ||
                !matchesComicReplacement(replaceBookId, previous.expectedRemoteHash, preparation.remoteBook)
            ) {
                throw NasComicUploadException(NasComicUploadFailure.RemoteChanged)
            }
            // Check again after the network calls, in case the NAS was switched during prepare().
            if (pending[preparation.preparationId]?.configurationFingerprint != previous.configurationFingerprint) {
                throw NasComicUploadException(NasComicUploadFailure.ConfigurationChanged)
            }
            enqueue(preparation, replaceBookId)
        } finally {
            discardPreparation(preparation)
        }
    }

    fun discardPreparation(preparation: NasComicUploadPreparation) {
        pending.remove(preparation.preparationId)
    }

    fun cancel(taskId: UUID) {
        workManager.cancelWorkById(taskId)
    }

    fun observe(taskId: UUID): Flow<NasComicUploadState> = workManager.getWorkInfoByIdFlow(taskId)
        .map { it?.toComicState() ?: NasComicUploadState() }
        .distinctUntilChanged()

    internal fun loadSnapshot(sourceKey: String, taskId: UUID?): ComicSnapshot? = runCatching {
        GSON.fromJson(snapshotFile(sourceKey, taskId).readText(), ComicSnapshot::class.java)
            ?.takeIf { it.clientSourceKey == sourceKey }
    }.getOrNull()

    internal fun taskDirectory(sourceKey: String, taskId: UUID?): File {
        require(sourceKey.matches(Regex("[0-9a-f]{64}")))
        val sourceDirectory = File(application.noBackupFilesDir, "nas-comic/$sourceKey")
        return if (taskId == null) sourceDirectory else File(sourceDirectory, taskId.toString())
    }

    internal fun cleanupHistoryArtifacts(retryKey: String, taskId: UUID) {
        // Never remove the shared snapshot used by legacy tasks or any other task's files.
        if (retryKey.startsWith(SCOPED_RETRY_PREFIX)) {
            taskDirectory(retryKey.removePrefix(SCOPED_RETRY_PREFIX), taskId).deleteRecursively()
        }
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
    internal suspend fun prepareCoverFile(book: Book, directory: File): File? {
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
        val file = File(directory, "cover.jpg")
        file.parentFile?.mkdirs()
        ByteArrayOutputStream().use { bytes ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, bytes)) {
                "封面图片编码失败"
            }
            file.outputStream().use { output -> bytes.writeTo(output) }
        }
        return file.takeIf(File::isFile)
    }

    internal fun configurationFingerprint(settings: NasSettings): String {
        val normalized = nasGateway.normalizeUrl(settings.apiUrl).orEmpty()
        return MessageDigest.getInstance("SHA-256")
            .digest("$normalized\u0000${settings.apiToken.trim()}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun snapshotFile(sourceKey: String, taskId: UUID?) = File(taskDirectory(sourceKey, taskId), SNAPSHOT_FILE)

    /** Persistent WorkManager snapshot; fixed JSON keys keep pending uploads readable after upgrades. */
    internal data class ComicSnapshot(
        @SerializedName("bookUrl") val bookUrl: String,
        @SerializedName("book") val book: Book,
        /** The persisted book identity whose folder contains the existing image cache. */
        @SerializedName("cacheBook") val cacheBook: Book? = null,
        @SerializedName("chapters") val chapters: List<BookChapter>,
        @SerializedName("clientSourceKey") val clientSourceKey: String,
        @SerializedName("configurationFingerprint") val configurationFingerprint: String,
        @SerializedName("maxUploadBytes") val maxUploadBytes: Long,
        @SerializedName("replaceBookId") val replaceBookId: String? = null,
        @SerializedName("expectedRemoteHash") val expectedRemoteHash: String? = null,
    )

    companion object {
        internal const val KEY_BOOK_URL = "book_url"
        internal const val KEY_SOURCE_KEY = "client_source_key"
        internal const val KEY_ISOLATED_SNAPSHOT = "isolated_snapshot"
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
        private const val SNAPSHOT_FILE = "snapshot.json"
        private const val SCOPED_RETRY_PREFIX = "scoped:"

        internal fun workName(sourceKey: String) = "nas-comic:$sourceKey"
    }
}

private fun WorkInfo.toComicState(): NasComicUploadState {
    val values = if (state.isFinished) outputData else progress
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

/** IDs remain unchanged on NAS overwrites; the exact approved file must still be present. */
internal fun matchesComicReplacement(bookId: String?, expectedHash: String?, remote: NasBookDetail?): Boolean {
    if (bookId == null) return remote == null
    val hash = normalizeNasSha256(expectedHash) ?: return false
    return remote?.id == bookId && normalizeNasSha256(remote.contentHash) == hash
}
