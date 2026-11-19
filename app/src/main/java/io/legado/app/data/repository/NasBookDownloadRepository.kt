package io.legado.app.data.repository

import android.app.Application
import android.net.Uri
import io.legado.app.R
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.domain.gateway.NasDownloadSink
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.model.NasBook
import io.legado.app.domain.model.NasTransferHistory
import io.legado.app.domain.model.settings.NasSettings
import io.legado.app.domain.usecase.NasLibraryUseCase
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.isImage
import io.legado.app.model.localBook.LocalBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Downloads and imports through one path, never replacing a user file based on its name. */
class NasBookDownloadRepository(
    private val application: Application,
    private val nas: NasLibraryUseCase,
    private val settingsGateway: NasSettingsGateway,
    private val history: NasTransferHistoryRepository,
) {
    private val publishLock = Mutex()

    suspend fun download(book: NasBook, settings: NasSettings): Book = withContext(Dispatchers.IO) {
        transfer(book, settings, fetchDetail = false)
    }

    suspend fun retry(item: NasTransferHistory, bookId: String): Book = withContext(Dispatchers.IO) {
        val settings = settingsGateway.currentSettings
        val source = verifiedSource(settings)
        // Legacy rows do not prove which NAS originally supplied the ID. Never guess.
        if (item.destinationFingerprint != nasDownloadFingerprint(source)) throw sourceChanged()
        transfer(
            NasBook(bookId, title = item.title, relativePath = item.remotePath.orEmpty()),
            settings,
            fetchDetail = true,
            knownKind = item.kind,
        )
    }

    private suspend fun transfer(
        initial: NasBook,
        settings: NasSettings,
        fetchDetail: Boolean,
        knownKind: String = nasDownloadFormat(initial).historyKind,
    ): Book {
        val source = verifiedSource(settings)
        val scope = currentCoroutineContext()
        scope.ensureActive()
        val id = "nas-download:${UUID.randomUUID()}"
        history.recordStarted(
            id, "download", initial.displayTitle, knownKind, initial.relativePath,
            retryKey = initial.id, destinationFingerprint = nasDownloadFingerprint(source),
        )
        var staging: File? = null
        val (book, imported) = try {
            val book = if (fetchDetail) nas.detail(initial.id, settings).toBook() else initial
            if (book.id != initial.id) throw sourceChanged()
            val file = File.createTempFile("nas-download-", ".part", application.cacheDir)
            staging = file
            val hash = MessageDigest.getInstance("SHA-256")
            file.outputStream().buffered().use { output ->
                nas.downloadBook(book.id, NasDownloadSink { bytes, offset, size ->
                    scope.ensureActive()
                    output.write(bytes, offset, size)
                    hash.update(bytes, offset, size)
                }, settings)
            }
            scope.ensureActive()
            val format = nasDownloadFormat(book, file)
            if (file.length() == 0L || !format.supported ||
                (format.fileName.endsWith(".epub", true) && !file.isEpubContainer())
            ) throw NoStackTraceException(application.getString(R.string.nas_download_invalid_book))
            val target = nasDownloadTarget(
                File(application.filesDir, "nas-books"), source, book.id,
                hash.digest().toHexString(), format.fileName,
            )
            val imported = publishLock.withLock {
                scope.ensureActive()
                verifiedSource(settings)
                // Both directories are inside app storage. No replace-existing option is used.
                val created = !target.exists()
                if (created) {
                    Files.createDirectories(target.parentFile!!.toPath())
                    Files.move(file.toPath(), target.toPath())
                }
                try {
                    LocalBook.importNasDownload(Uri.fromFile(target), format.comicArchive)
                } catch (error: Exception) {
                    if (created) {
                        target.delete()
                        target.parentFile?.delete()
                    }
                    throw error
                }
            }
            history.recordFinished(id, NasTransferHistoryRepository.SUCCEEDED,
                remotePath = book.relativePath, kind = format.historyKind)
            book to imported
        } catch (error: Exception) {
            history.recordFinished(
                id,
                if (error is CancellationException && error !is TimeoutCancellationException) {
                    NasTransferHistoryRepository.CANCELLED
                } else NasTransferHistoryRepository.FAILED,
                message = error.localizedMessage,
            )
            throw error
        } finally {
            staging?.delete()
        }
        // Cover transfer is best-effort and never changes a committed import's result.
        if (imported.isImage && imported.coverUrl.isNullOrBlank() && !book.coverUrl.isNullOrBlank()) {
            downloadCover(book.id, imported.bookUrl, settings)
        }
        return imported
    }

    private suspend fun downloadCover(id: String, localBookUrl: String, settings: NasSettings) {
        var staging: File? = null
        try {
            val scope = currentCoroutineContext()
            val file = File.createTempFile("nas-cover-", ".part", application.cacheDir)
            staging = file
            file.outputStream().buffered().use { output ->
                nas.downloadCover(id, NasDownloadSink { bytes, offset, size ->
                    scope.ensureActive()
                    output.write(bytes, offset, size)
                }, settings)
            }
            scope.ensureActive()
            verifiedSource(settings)
            if (file.length() == 0L) return
            LocalBook.installNasCover(localBookUrl, file)
        } catch (error: CancellationException) {
            if (error !is TimeoutCancellationException) throw error
        } catch (_: Exception) {
            // Reading remains available even if the optional cover cannot be fetched.
        } finally {
            staging?.delete()
        }
    }

    private fun verifiedSource(snapshot: NasSettings): String {
        val current = settingsGateway.currentSettings
        val source = nas.normalizeUrl(snapshot.apiUrl) ?: throw sourceChanged()
        if (!snapshot.connectionVerified || !current.connectionVerified || snapshot.apiToken.isBlank() ||
            snapshot.apiToken.trim() != current.apiToken.trim() || nas.normalizeUrl(current.apiUrl) != source
        ) throw sourceChanged()
        return source
    }

    private fun sourceChanged() = NoStackTraceException(application.getString(R.string.nas_download_source_changed))
}

internal data class NasDownloadFormat(val fileName: String, val comicArchive: Boolean) {
    val supported: Boolean get() = comicArchive || AppPattern.bookFileRegex.matches(fileName)
    val historyKind: String get() = if (comicArchive) "comic" else "book"
}

internal fun nasDownloadFormat(book: NasBook, staged: File? = null): NasDownloadFormat {
    val raw = book.fileName.ifBlank { book.relativePath.substringAfterLast('/').ifBlank { book.displayTitle } }
    val safe = raw.map { if (it.code < 32 || it in "\\/:*?\"<>|") '_' else it }
        .joinToString("").trim('.', ' ').ifBlank { "nas-download" }
    val nameExtension = safe.substringAfterLast('.', "").lowercase()
    val allowedExtensions = setOf("txt", "epub", "umd", "pdf", "mobi", "azw3", "azw", "md", "markdown", "cbz", "zip", "rar", "7z")
    val declared = sequenceOf(nameExtension, book.extension, book.kind)
        .map { it.trim().trimStart('.').lowercase() }.firstOrNull { it in allowedExtensions }
    val extension = when {
        // Recognize real EPUB even when an older server only reports ZIP or no extension.
        declared == "epub" || staged?.isEpubContainer() == true -> "epub"
        declared != null -> declared
        book.chapterCount > 0 || staged?.isZipContainer() == true -> "cbz"
        else -> ""
    }
    val stem = if (nameExtension in allowedExtensions) safe.substringBeforeLast('.') else safe
    val end = stem.offsetByCodePoints(0, stem.codePointCount(0, stem.length).coerceAtMost(56))
    val fileName = stem.substring(0, end).trimEnd('.', ' ').ifBlank { "nas-download" } +
        extension.takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
    return NasDownloadFormat(fileName, extension in setOf("cbz", "zip", "rar", "7z"))
}

private fun File.isZipContainer() = runCatching { ZipFile(this).use { true } }.getOrDefault(false)

private fun File.isEpubContainer() = runCatching {
    ZipFile(this).use { it.getEntry("META-INF/container.xml")?.isDirectory == false }
}.getOrDefault(false)

internal fun nasDownloadTarget(root: File, source: String, bookId: String, version: String, name: String): File =
    File(File(File(root, nasDownloadFingerprint(source)), sha256(bookId)), "$version/$name")

internal fun nasDownloadFingerprint(source: String): String = sha256("nas-download:$source")

private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).toHexString()
