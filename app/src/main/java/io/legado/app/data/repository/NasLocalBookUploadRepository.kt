package io.legado.app.data.repository

import android.content.Context
import io.legado.app.data.entities.Book
import io.legado.app.domain.gateway.NasUploadSource
import io.legado.app.domain.model.settings.NasSettings
import io.legado.app.domain.usecase.NasBookUploadError
import io.legado.app.domain.usecase.NasBookUploadException
import io.legado.app.domain.usecase.NasBookUploadOutcome
import io.legado.app.domain.usecase.NasBookUploadStage
import io.legado.app.domain.usecase.UploadNasBookUseCase
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isLocal
import io.legado.app.utils.inputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Spools a local/content URI so the hashed bytes and uploaded bytes cannot diverge. */
class NasLocalBookUploadRepository(
    private val context: Context,
    private val upload: UploadNasBookUseCase,
) {
    suspend fun upload(book: Book, settings: NasSettings, onStage: (NasBookUploadStage) -> Unit): NasBookUploadOutcome =
        withContext(Dispatchers.IO) {
            if (!book.isLocal) throw NasBookUploadException(NasBookUploadError.InvalidFile)
            onStage(NasBookUploadStage.Preparing)
            val uri = book.getLocalUri()
            val name = safeNasBookFileName(book.originName.ifBlank { uri.lastPathSegment.orEmpty() })
            val temp = File.createTempFile("nas-book-upload-", ".tmp", context.cacheDir)
            try {
                val hash = MessageDigest.getInstance("SHA-256")
                var size = 0L
                uri.inputStream(context).getOrThrow().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            if (size > 512L * 1024 * 1024)
                                throw NasBookUploadException(NasBookUploadError.InvalidFile)
                            hash.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (size == 0L) throw NasBookUploadException(NasBookUploadError.InvalidFile)
                upload.execute(settings, NasUploadSource { sink ->
                    temp.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                        }
                    }
                }, name, size, hash.digest().joinToString("") { "%02x".format(it) },
                    book.name, book.author, book.intro.orEmpty(), onStage)
            } finally {
                temp.delete()
            }
        }
}

internal fun safeNasBookFileName(raw: String): String {
    val name = raw.replace('\\', '/').substringAfterLast('/').trim()
        .map { if (it.code < 32 || it in "\"<>:|?*") '_' else it }.joinToString("").trim('.', ' ')
    if (name.isBlank() || name == "." || name == "..")
        throw NasBookUploadException(NasBookUploadError.InvalidFile)
    return name
}
