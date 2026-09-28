package io.legado.app.data.repository

import android.app.Application
import io.legado.app.data.repository.manga.NasComicUploadRepository
import io.legado.app.domain.gateway.NasDownloadSink
import io.legado.app.domain.model.NasBook
import io.legado.app.domain.model.NasTransferHistory
import io.legado.app.domain.usecase.NasLibraryUseCase
import io.legado.app.model.localBook.LocalBook
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Recreates failed or cancelled NAS transfers without persisting credentials. */
class NasTransferRetryRepository(
    private val application: Application,
    private val nasLibraryUseCase: NasLibraryUseCase,
    private val historyRepository: NasTransferHistoryRepository,
    private val comicUploadRepository: NasComicUploadRepository,
    private val localBookUploadRepository: NasLocalBookUploadRepository,
) {
    suspend fun retry(item: NasTransferHistory) {
        require(item.state == NasTransferHistoryRepository.FAILED ||
            item.state == NasTransferHistoryRepository.CANCELLED
        ) { "Only failed or cancelled transfers can be retried" }
        val retryKey = item.retryKey?.takeIf(String::isNotBlank)
            ?: error("This transfer has no retry information")
        when {
            item.direction == "upload" && item.kind == "comic" ->
                comicUploadRepository.retry(retryKey)
            item.direction == "upload" ->
                localBookUploadRepository.retry(retryKey, item.title)
            item.direction == "download" ->
                retryDownload(item, retryKey)
            else -> error("Unsupported NAS transfer type")
        }
    }

    fun deleteHistoryArtifacts(item: NasTransferHistory) {
        if (item.direction != "upload" || item.kind != "comic") return
        item.retryKey?.takeIf(String::isNotBlank)?.let { sourceKey ->
            comicUploadRepository.cleanupRetryDataIfIdle(sourceKey)
        }
    }

    fun clearHistoryArtifacts() {
        comicUploadRepository.cleanupAllRetryData()
    }

    private suspend fun retryDownload(item: NasTransferHistory, bookId: String) {
        val historyId = "nas-download:" + bookId + ":" + System.currentTimeMillis()
        historyRepository.recordStarted(
            id = historyId,
            direction = "download",
            title = item.title,
            kind = item.kind,
            remotePath = item.remotePath,
            retryKey = bookId,
        )
        var temporaryFile: File? = null
        try {
            val book = nasLibraryUseCase.detail(bookId).toBook()
            val safeName = retryDownloadFileName(book)
            val declaredComic = item.kind == "comic" || retryIsComicArchive(book, safeName)
            val file = File(
                application.cacheDir,
                "nas-download-retry-" + System.currentTimeMillis() + "-" + safeName,
            )
            temporaryFile = file
            FileOutputStream(file).use { output ->
                nasLibraryUseCase.downloadBook(
                    id = bookId,
                    sink = NasDownloadSink { chunk, offset, length ->
                        output.write(chunk, offset, length)
                    },
                )
            }
            val comicArchive = declaredComic || retryIsZipArchive(file)
            val importName = if (comicArchive && !safeName.endsWith(".cbz", ignoreCase = true)) {
                safeName + ".cbz"
            } else {
                safeName
            }
            val uri = saveNasBookFile(file, importName)
            if (comicArchive) {
                LocalBook.importFiles(uri).singleOrNull()
                    ?: throw IOException("NAS 漫画压缩包导入失败")
            } else {
                LocalBook.importFile(uri)
            }
            historyRepository.recordFinished(
                id = historyId,
                state = NasTransferHistoryRepository.SUCCEEDED,
                remotePath = book.relativePath,
            )
        } catch (error: Throwable) {
            historyRepository.recordFinished(
                id = historyId,
                state = if (error is CancellationException) {
                    NasTransferHistoryRepository.CANCELLED
                } else {
                    NasTransferHistoryRepository.FAILED
                },
                message = error.localizedMessage,
            )
            throw error
        } finally {
            temporaryFile?.delete()
        }
    }

    private fun saveNasBookFile(file: File, fileName: String): android.net.Uri =
        runCatching { FileInputStream(file).use { LocalBook.saveBookFile(it, fileName) } }.getOrElse { error ->
            if (error is SecurityException || error is io.legado.app.exception.NoBooksDirException) {
                FileInputStream(file).use { LocalBook.saveBookFileInAppStorage(it, fileName) }
            } else {
                throw error
            }
        }
}

private fun retryDownloadFileName(book: NasBook): String {
    val rawName = book.fileName.ifBlank {
        book.relativePath.substringAfterLast('/').ifBlank { book.displayTitle }
    }
    val safeBaseName = rawName
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .trim()
        .ifBlank { "nas-download" }
    val declaredExtension = book.extension.trim().trimStart('.')
        .ifBlank { book.kind.trim().trimStart('.') }
        .lowercase()
    val isComicArchive = declaredExtension == "cbz" ||
        safeBaseName.substringAfterLast('.', "").equals("cbz", ignoreCase = true)
    val suffix = if (isComicArchive) "cbz" else declaredExtension
    return if (suffix.isNotBlank() &&
        !safeBaseName.endsWith("." + suffix, ignoreCase = true)
    ) {
        safeBaseName + "." + suffix
    } else {
        safeBaseName
    }
}

private fun retryIsComicArchive(book: NasBook, fileName: String): Boolean =
    book.chapterCount > 0 ||
        book.kind.equals("cbz", ignoreCase = true) ||
        book.extension.trimStart('.').equals("cbz", ignoreCase = true) ||
        fileName.substringAfterLast('.', "").equals("cbz", ignoreCase = true)

private fun retryIsZipArchive(file: File): Boolean = runCatching {
    file.inputStream().use { input ->
        val header = ByteArray(4)
        input.read(header) == 4 && header[0] == 'P'.code.toByte() &&
            header[1] == 'K'.code.toByte() &&
            (header[2] == 3.toByte() || header[2] == 5.toByte() || header[2] == 7.toByte())
    }
}.getOrDefault(false)
