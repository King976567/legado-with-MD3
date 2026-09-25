package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.NasLibraryGateway
import io.legado.app.domain.gateway.NasDownloadSink
import io.legado.app.domain.gateway.NasUploadSource
import io.legado.app.domain.gateway.NasSettingsGateway
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

/** Use-case facade shared by HomeDashboard, settings, and the NAS library. */
class NasLibraryUseCase(
    private val gateway: NasLibraryGateway,
    private val settingsGateway: NasSettingsGateway,
) {
    suspend fun checkConnection(settings: NasSettings = settingsGateway.currentSettings): NasConnection =
        gateway.checkConnection(settings)

    suspend fun diagnoseConnection(
        apiUrl: String,
        token: String?,
    ): NasDiagnosticReport = gateway.diagnoseConnection(apiUrl, token)

    suspend fun listBooks(
        page: Int = 1,
        pageSize: Int = 30,
        search: String? = null,
        directoryPath: String? = null,
        scrapeStatus: String? = null,
        manualConfirmed: Boolean? = null,
        sortBy: String = "updated_at",
        sortOrder: String = "DESC",
        settings: NasSettings = settingsGateway.currentSettings,
    ): NasBookPage = gateway.listBooks(
        page,
        pageSize,
        search,
        directoryPath,
        scrapeStatus,
        manualConfirmed,
        sortBy,
        sortOrder,
        settings,
    )

    suspend fun detail(id: String, settings: NasSettings = settingsGateway.currentSettings): NasBookDetail =
        gateway.detail(id, settings)

    suspend fun updateMetadata(
        id: String,
        title: String,
        author: String,
        intro: String,
        settings: NasSettings = settingsGateway.currentSettings,
    ): NasBookDetail = gateway.updateMetadata(id, title, author, intro, settings)

    suspend fun moveBook(
        id: String,
        targetDirectoryPath: String,
        duplicatePolicy: String = "skip",
        settings: NasSettings = settingsGateway.currentSettings,
    ): NasMoveResult = gateway.moveBook(id, targetDirectoryPath, duplicatePolicy, settings)

    suspend fun uploadBook(
        source: NasUploadSource,
        fileName: String,
        contentLength: Long? = null,
        duplicatePolicy: String = "skip",
        title: String = "",
        author: String = "",
        intro: String = "",
        directoryPath: String = "",
        settings: NasSettings = settingsGateway.currentSettings,
    ): NasUploadResult = gateway.uploadBook(
        source,
        fileName,
        contentLength,
        duplicatePolicy,
        title,
        author,
        intro,
        directoryPath,
        settings,
    )

    suspend fun downloadBook(
        id: String,
        sink: NasDownloadSink,
        settings: NasSettings = settingsGateway.currentSettings,
    ) = gateway.downloadBook(id, sink, settings)

    suspend fun refreshIndex(settings: NasSettings = settingsGateway.currentSettings): NasTaskStatus =
        gateway.refreshIndex(settings)

    suspend fun indexerStatus(settings: NasSettings = settingsGateway.currentSettings): NasTaskStatus =
        gateway.indexerStatus(settings)

    suspend fun indexerTasks(
        limit: Int = 20,
        settings: NasSettings = settingsGateway.currentSettings,
    ): List<NasTask> = gateway.indexerTasks(limit, settings)

    suspend fun cancelIndexer(settings: NasSettings = settingsGateway.currentSettings): NasTaskStatus =
        gateway.cancelIndexer(settings)

    suspend fun scrapeAllPending(
        directoryPath: String? = null,
        settings: NasSettings = settingsGateway.currentSettings,
    ): NasTaskStatus = gateway.scrapeAllPending(directoryPath, settings)

    suspend fun scrapeAllFailed(
        directoryPath: String? = null,
        settings: NasSettings = settingsGateway.currentSettings,
    ): NasTaskStatus = gateway.scrapeAllFailed(directoryPath, settings)

    suspend fun scrapeBook(
        id: String,
        settings: NasSettings = settingsGateway.currentSettings,
    ): NasTaskStatus = gateway.scrapeBook(id, settings)

    suspend fun cancelScraperTask(
        taskId: String,
        settings: NasSettings = settingsGateway.currentSettings,
    ): NasTaskStatus = gateway.cancelScraperTask(taskId, settings)

    suspend fun tasks(
        limit: Int = 20,
        settings: NasSettings = settingsGateway.currentSettings,
    ): List<NasTask> = gateway.tasks(limit, settings)

    suspend fun libraryDirectories(settings: NasSettings = settingsGateway.currentSettings): List<NasDirectory> =
        gateway.libraryDirectories(settings)

    fun normalizeUrl(raw: String): String? = gateway.normalizeUrl(raw)
}
