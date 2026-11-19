package io.legado.app.data.repository

import android.app.Application
import android.util.Log
import io.legado.app.R
import io.legado.app.data.repository.manga.NasComicUploadRepository
import io.legado.app.domain.model.NasComicUploadException
import io.legado.app.domain.model.NasComicUploadFailure
import io.legado.app.domain.model.NasTransferHistory
import io.legado.app.domain.model.NasUploadTask
import java.util.UUID

/** Recreates failed or cancelled NAS transfers without persisting credentials. */
class NasTransferRetryRepository(
    private val application: Application,
    private val downloadRepository: NasBookDownloadRepository,
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
                try {
                    comicUploadRepository.retry(retryKey, UUID.fromString(item.id), item.replaceBookId)
                } catch (error: NasComicUploadException) {
                    val text = when (error.reason) {
                        NasComicUploadFailure.ConfigurationChanged ->
                            R.string.cache_nas_upload_destination_changed
                        NasComicUploadFailure.RemoteChanged ->
                            R.string.cache_nas_upload_remote_changed
                        NasComicUploadFailure.RefreshFailed ->
                            R.string.feature_book_info_nas_comic_refresh_failed
                        else -> throw error
                    }
                    throw IllegalStateException(application.getString(text), error)
                }
            item.direction == "upload" ->
                localBookUploadRepository.retry(retryKey, item.title, item.destinationFingerprint)
            item.direction == "download" ->
                downloadRepository.retry(item, retryKey)
            else -> error("Unsupported NAS transfer type")
        }
    }

    fun deleteHistoryArtifacts(item: NasTransferHistory) {
        if (item.direction != "upload" || item.kind != "comic") return
        item.retryKey?.takeIf(String::isNotBlank)?.let { sourceKey ->
            cleanupComicArtifacts(sourceKey, item.id)
        }
    }

    internal fun pruneUploadArtifacts(finished: List<NasUploadTask>, retained: List<NasTransferHistory>) {
        val retryable = retained.asSequence().filter { it.state != NasTransferHistoryRepository.SUCCEEDED }
            .map { it.id }.toHashSet()
        finished.filter { it.kind == "comic" && it.id !in retryable }.forEach { task ->
            task.retryKey?.let { cleanupComicArtifacts(it, task.id) }
        }
    }

    private fun cleanupComicArtifacts(retryKey: String, id: String) {
        runCatching { comicUploadRepository.cleanupHistoryArtifacts(retryKey, UUID.fromString(id)) }
            .onFailure { Log.w("NasTransferHistory", "Could not clear local comic upload artifacts", it) }
    }
}
