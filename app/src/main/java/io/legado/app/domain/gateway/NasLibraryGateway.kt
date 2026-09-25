package io.legado.app.domain.gateway

import io.legado.app.domain.model.NasBookDetail
import io.legado.app.domain.model.NasBookPage
import io.legado.app.domain.model.NasConnection
import io.legado.app.domain.model.NasDiagnosticReport
import io.legado.app.domain.model.NasDirectory
import io.legado.app.domain.model.NasMoveResult
import io.legado.app.domain.model.NasTask
import io.legado.app.domain.model.NasTaskStatus
import io.legado.app.domain.model.NasUploadResult
import io.legado.app.domain.model.settings.NasSettings
/** Platform-neutral upload source. Implementations feed bounded chunks to the sink. */
fun interface NasUploadSource {
    fun writeTo(sink: NasChunkSink)
}

/** Platform-neutral sink used while downloading a book. */
fun interface NasDownloadSink {
    fun write(chunk: ByteArray, offset: Int, length: Int)
}

fun interface NasChunkSink {
    fun write(chunk: ByteArray, offset: Int, length: Int)
}

/**
 * Domain-facing contract for the dedicated NAS library service.
 *
 * The presentation layer intentionally knows nothing about OkHttp, JSON, or
 * the concrete repository. Keeping the settings argument explicit here also
 * makes request snapshots testable and prevents a stale token from being
 * captured by a long-lived screen.
 */
interface NasLibraryGateway {
    suspend fun checkConnection(settings: NasSettings): NasConnection

    suspend fun diagnoseConnection(apiUrl: String, token: String?): NasDiagnosticReport

    suspend fun listBooks(
        page: Int = 1,
        pageSize: Int = 30,
        search: String? = null,
        directoryPath: String? = null,
        scrapeStatus: String? = null,
        manualConfirmed: Boolean? = null,
        sortBy: String = "updated_at",
        sortOrder: String = "DESC",
        settings: NasSettings,
    ): NasBookPage

    suspend fun detail(id: String, settings: NasSettings): NasBookDetail

    suspend fun updateMetadata(
        id: String,
        title: String,
        author: String,
        intro: String,
        settings: NasSettings,
    ): NasBookDetail

    suspend fun moveBook(
        id: String,
        targetDirectoryPath: String,
        duplicatePolicy: String = "skip",
        settings: NasSettings,
    ): NasMoveResult

    suspend fun uploadBook(
        source: NasUploadSource,
        fileName: String,
        contentLength: Long? = null,
        duplicatePolicy: String = "skip",
        title: String = "",
        author: String = "",
        intro: String = "",
        directoryPath: String = "",
        settings: NasSettings,
    ): NasUploadResult

    suspend fun downloadBook(id: String, sink: NasDownloadSink, settings: NasSettings)

    suspend fun refreshIndex(settings: NasSettings): NasTaskStatus

    suspend fun indexerStatus(settings: NasSettings): NasTaskStatus

    suspend fun indexerTasks(limit: Int = 20, settings: NasSettings): List<NasTask>

    suspend fun cancelIndexer(settings: NasSettings): NasTaskStatus

    suspend fun scrapeAllPending(
        directoryPath: String? = null,
        settings: NasSettings,
    ): NasTaskStatus

    suspend fun scrapeAllFailed(
        directoryPath: String? = null,
        settings: NasSettings,
    ): NasTaskStatus

    suspend fun scrapeBook(id: String, settings: NasSettings): NasTaskStatus

    suspend fun cancelScraperTask(taskId: String, settings: NasSettings): NasTaskStatus

    suspend fun tasks(limit: Int = 20, settings: NasSettings): List<NasTask>

    suspend fun libraryDirectories(settings: NasSettings): List<NasDirectory>

    fun normalizeUrl(raw: String): String?
}
