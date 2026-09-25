package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.NasLibraryGateway
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.gateway.NasUploadSource
import io.legado.app.domain.model.NasWriteAccess
import io.legado.app.domain.model.settings.NasSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class NasBookUploadStage { Preparing, Checking, Uploading }
enum class NasBookUploadError { Unavailable, ReadOnly, Unsupported, CheckIncomplete, UploadFailed, InvalidFile }
class NasBookUploadException(val reason: NasBookUploadError) : Exception(reason.name)
data class NasBookUploadOutcome(val alreadyExists: Boolean, val path: String)

fun NasSettings.isBookUploadEnabled(): Boolean =
    showHomeCard && connectionVerified && apiUrl.isNotBlank() && apiToken.isNotBlank()

/** Metadata-only preflight, followed by the server's atomic skip-on-duplicate protection. */
class UploadNasBookUseCase(
    private val gateway: NasLibraryGateway,
    private val settingsGateway: NasSettingsGateway,
) {
    suspend fun execute(
        settings: NasSettings,
        source: NasUploadSource,
        fileName: String,
        contentLength: Long,
        contentHash: String,
        title: String,
        author: String,
        intro: String,
        onStage: (NasBookUploadStage) -> Unit = {},
    ): NasBookUploadOutcome {
        val context = currentCoroutineContext()
        fun guard() {
            context.ensureActive()
            val current = settingsGateway.currentSettings
            if (!settings.isBookUploadEnabled() || !current.isBookUploadEnabled() ||
                gateway.normalizeUrl(settings.apiUrl) == null ||
                gateway.normalizeUrl(current.apiUrl) != gateway.normalizeUrl(settings.apiUrl) ||
                current.apiToken.trim() != settings.apiToken.trim()
            ) throw NasBookUploadException(NasBookUploadError.Unavailable)
        }
        guard()
        require(contentHash.matches(Regex("[a-fA-F0-9]{64}")) && contentLength > 0)
        onStage(NasBookUploadStage.Checking)
        val connection = gateway.checkConnection(settings)
        guard()
        if (!connection.capabilities.supports("upload"))
            throw NasBookUploadException(NasBookUploadError.Unsupported)
        if (connection.writeAccess == NasWriteAccess.DENIED)
            throw NasBookUploadException(NasBookUploadError.ReadOnly)

        val directory = connection.capabilities.defaultUploadDirectory.trim('/')
        val targetPath = listOf(directory, fileName).filter(String::isNotEmpty).joinToString("/")
        val seen = hashSetOf<String>()
        var page = 1
        while (true) {
            guard()
            val result = gateway.listBooks(page = page, pageSize = 200,
                sortBy = "created_at", sortOrder = "ASC", settings = settings)
            guard()
            result.items.firstOrNull {
                it.contentHash.equals(contentHash, ignoreCase = true) ||
                    it.relativePath.trimStart('/') == targetPath
            }?.let { return NasBookUploadOutcome(true, it.relativePath.ifBlank { it.fileName }) }
            val newItems = result.items.count { seen.add(it.id) }
            // Never treat an incomplete/broken page stream as proof of absence.
            if (result.total == 0 && result.items.isEmpty() && page == 1) break
            if (result.page != page || result.pageSize <= 0 || newItems == 0 || result.total < 0)
                throw NasBookUploadException(NasBookUploadError.CheckIncomplete)
            if (seen.size >= result.total) break
            if (page >= 500) throw NasBookUploadException(NasBookUploadError.CheckIncomplete)
            page++
        }
        guard()
        onStage(NasBookUploadStage.Uploading)
        val guardedSource = NasUploadSource { sink ->
            guard()
            source.writeTo { bytes, offset, length -> guard(); sink.write(bytes, offset, length) }
        }
        val result = gateway.uploadBook(guardedSource, fileName, contentLength,
            duplicatePolicy = "skip", title = title, author = author, intro = intro,
            directoryPath = directory, settings = settings)
        guard()
        if (result.failed.isNotEmpty()) throw NasBookUploadException(NasBookUploadError.UploadFailed)
        (result.duplicates + result.skipped).firstOrNull()?.let {
            return NasBookUploadOutcome(true, it.relativePath.ifBlank { targetPath })
        }
        result.uploaded.firstOrNull()?.let {
            return NasBookUploadOutcome(false, it.relativePath.ifBlank { targetPath })
        }
        throw NasBookUploadException(NasBookUploadError.UploadFailed)
    }
}
