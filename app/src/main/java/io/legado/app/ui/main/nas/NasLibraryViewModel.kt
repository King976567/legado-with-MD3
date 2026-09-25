package io.legado.app.ui.main.nas

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.Book
import io.legado.app.domain.gateway.NasDownloadSink
import io.legado.app.domain.gateway.NasUploadSource
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.model.NasBook
import io.legado.app.domain.model.NasCapabilities
import io.legado.app.domain.model.NasConnection
import io.legado.app.domain.model.NasDiagnosticReport
import io.legado.app.domain.model.NasDirectory
import io.legado.app.domain.model.NasHttpException
import io.legado.app.domain.model.NasTask
import io.legado.app.domain.model.NasTaskConflictException
import io.legado.app.domain.model.NasTaskStatus
import io.legado.app.domain.model.NasWriteAccess
import io.legado.app.domain.model.settings.NasSettings
import io.legado.app.domain.usecase.NasLibraryUseCase
import io.legado.app.model.localBook.LocalBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * State for the Compose NAS library screen.
 *
 * The repository is shared with HomeDashboard.  This view model deliberately
 * keeps no copy of the token and reads the current settings through the
 * gateway for every request, so a token change takes effect immediately.
 */
private const val NAS_DEFAULT_PAGE_SIZE = 30

data class NasLibraryUiState(
    val books: List<NasBook> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = NAS_DEFAULT_PAGE_SIZE,
    val total: Int = 0,
    val query: String = "",
    val isConfigured: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingDetail: Boolean = false,
    val connection: NasConnection? = null,
    val capabilities: NasCapabilities = NasCapabilities(),
    val directories: List<NasDirectory> = emptyList(),
    val directoryPath: String? = null,
    val isLoadingDirectories: Boolean = false,
    val selectedBook: NasBook? = null,
    val editingBook: NasBook? = null,
    val movingBook: NasBook? = null,
    val tasks: List<NasTask> = emptyList(),
    val isActionRunning: Boolean = false,
    /** Result of the explicit, non-mutating connection diagnostics action. */
    val isDiagnosing: Boolean = false,
    val diagnostic: NasDiagnosticReport? = null,
    val diagnosticError: String? = null,
    /** Set after a write endpoint returns 401/403 for this session. */
    val writeAccessDenied: Boolean = false,
    val actionError: String? = null,
    val error: String? = null,
    val detailError: String? = null,
) {
    val pageCount: Int
        get() = if (total <= 0) 0 else ((total + pageSize.coerceAtLeast(1) - 1) /
            pageSize.coerceAtLeast(1)).coerceAtLeast(1)

    val canGoPrevious: Boolean
        get() = page > 1 && !isLoading

    val canGoNext: Boolean
        get() = pageCount > page && !isLoading
}

sealed interface NasLibraryIntent {
    data class QueryChanged(val query: String) : NasLibraryIntent
    data object SubmitSearch : NasLibraryIntent
    data object Refresh : NasLibraryIntent
    data object Diagnose : NasLibraryIntent
    data object PreviousPage : NasLibraryIntent
    data object NextPage : NasLibraryIntent
    data class OpenBook(val book: NasBook) : NasLibraryIntent
    data class DownloadBook(val book: NasBook) : NasLibraryIntent
    data class UploadFileSelected(val uri: Uri) : NasLibraryIntent
    data object RequestUpload : NasLibraryIntent
    data object RefreshIndex : NasLibraryIntent
    data class ScrapeBook(val book: NasBook) : NasLibraryIntent
    data object ScrapePending : NasLibraryIntent
    data object RetryFailedScrape : NasLibraryIntent
    data object LoadTasks : NasLibraryIntent
    data class CancelTask(val taskId: String, val kind: String = "scraper") : NasLibraryIntent
    data object LoadDirectories : NasLibraryIntent
    data class DirectoryChanged(val path: String?) : NasLibraryIntent
    data class SaveMetadata(
        val book: NasBook,
        val title: String,
        val author: String,
        val intro: String,
    ) : NasLibraryIntent
    data class RequestEdit(val book: NasBook) : NasLibraryIntent
    data class RequestMove(val book: NasBook) : NasLibraryIntent
    data class MoveBook(
        val book: NasBook,
        val targetDirectoryPath: String,
        val duplicatePolicy: String,
    ) : NasLibraryIntent
    data object DismissEditor : NasLibraryIntent
    data object DismissMove : NasLibraryIntent
    data object DismissBook : NasLibraryIntent
    data object DismissDiagnostic : NasLibraryIntent
    data object Retry : NasLibraryIntent
}

sealed interface NasLibraryEffect {
    data class OpenLocalBook(val book: Book) : NasLibraryEffect
    data class ShowMessage(val message: String) : NasLibraryEffect
    data object SelectUploadFile : NasLibraryEffect
}

class NasLibraryViewModel(
    private val context: Context,
    private val nasLibraryUseCase: NasLibraryUseCase,
    private val nasSettingsGateway: NasSettingsGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NasLibraryUiState())
    val uiState: StateFlow<NasLibraryUiState> = _uiState.asStateFlow()
    private val _effects = kotlinx.coroutines.flow.MutableSharedFlow<NasLibraryEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var connectionJob: Job? = null
    private var diagnosticJob: Job? = null
    private var diagnosticSettings: NasSettings? = null

    init {
        // Do not perform network work while the card/page is not visible.  The
        // route invokes refresh() explicitly.  We only mirror configuration
        // changes here, which also clears stale results after disconnecting NAS.
        viewModelScope.launch {
            nasSettingsGateway.settings.collectLatest { settings ->
                // An in-flight report belongs to the exact URL/token snapshot
                // it started with. Cancel it when that snapshot changes so an
                // old result cannot overwrite the newly edited configuration.
                val diagnosticsChanged = diagnosticSettings != null && diagnosticSettings != settings
                if (diagnosticsChanged) {
                    diagnosticJob?.cancel()
                    diagnosticJob = null
                    diagnosticSettings = null
                }
                // WebDAV restore intentionally omits the NAS bearer token. Until the
                // user enters and tests a token, keep the route unavailable even if a
                // NAS happens to expose a public health endpoint.
                val configured = settings.apiUrl.isNotBlank() &&
                    settings.apiToken.isNotBlank() && settings.connectionVerified
                _uiState.update { current ->
                    if (configured) {
                        // A new token/address is a fresh permission context.
                        // Clear a previous 401/403 lock so the user can test
                        // the replacement token without recreating the route.
                        current.copy(
                            isConfigured = true,
                            // A fresh verified configuration starts a new permission
                            // context. Preserve a denial for ordinary settings changes
                            // (for example, toggling the home-card switch) so a known
                            // read-only token does not re-enable writes accidentally.
                            writeAccessDenied = if (!current.isConfigured) {
                                false
                            } else {
                                current.writeAccessDenied
                            },
                            isDiagnosing = if (diagnosticsChanged) false else current.isDiagnosing,
                            actionError = null,
                            diagnostic = if (diagnosticsChanged) null else current.diagnostic,
                            diagnosticError = if (diagnosticsChanged) null else current.diagnosticError,
                        )
                    } else {
                        NasLibraryUiState(query = current.query)
                    }
                }
            }
        }
    }

    fun onIntent(intent: NasLibraryIntent) {
        when (intent) {
            is NasLibraryIntent.QueryChanged -> _uiState.update { it.copy(query = intent.query) }
            NasLibraryIntent.SubmitSearch -> refresh()
            NasLibraryIntent.Refresh,
            NasLibraryIntent.Retry -> refresh()
            NasLibraryIntent.Diagnose -> diagnoseConnection()
            NasLibraryIntent.PreviousPage -> loadPage((_uiState.value.page - 1).coerceAtLeast(1))
            NasLibraryIntent.NextPage -> loadPage(_uiState.value.page + 1)
            is NasLibraryIntent.OpenBook -> selectBook(intent.book)
            is NasLibraryIntent.DownloadBook -> downloadBook(intent.book)
            is NasLibraryIntent.UploadFileSelected -> uploadFile(intent.uri)
            NasLibraryIntent.RequestUpload -> _effects.tryEmit(NasLibraryEffect.SelectUploadFile)
            NasLibraryIntent.RefreshIndex -> runAction("索引刷新") { nasLibraryUseCase.refreshIndex() }
            is NasLibraryIntent.ScrapeBook -> runAction("单本刮削") {
                nasLibraryUseCase.scrapeBook(intent.book.id)
            }
            NasLibraryIntent.ScrapePending -> runAction("批量刮削") { nasLibraryUseCase.scrapeAllPending() }
            NasLibraryIntent.RetryFailedScrape -> runAction("失败刮削重试") { nasLibraryUseCase.scrapeAllFailed() }
            NasLibraryIntent.LoadTasks -> loadTasks()
            is NasLibraryIntent.CancelTask -> runAction("取消任务") {
                if (intent.kind.equals("indexer", ignoreCase = true)) {
                    nasLibraryUseCase.cancelIndexer()
                } else {
                    nasLibraryUseCase.cancelScraperTask(intent.taskId)
                }
            }
            NasLibraryIntent.LoadDirectories -> loadDirectories()
            is NasLibraryIntent.DirectoryChanged -> {
                _uiState.update { it.copy(directoryPath = intent.path) }
                refresh()
            }
            is NasLibraryIntent.SaveMetadata -> saveMetadata(intent)
            is NasLibraryIntent.RequestEdit -> _uiState.update { it.copy(editingBook = intent.book) }
            is NasLibraryIntent.RequestMove -> _uiState.update { it.copy(movingBook = intent.book) }
            is NasLibraryIntent.MoveBook -> moveBook(intent)
            NasLibraryIntent.DismissEditor -> _uiState.update { it.copy(editingBook = null) }
            NasLibraryIntent.DismissMove -> _uiState.update { it.copy(movingBook = null) }
            NasLibraryIntent.DismissBook -> _uiState.update { it.copy(selectedBook = null, detailError = null) }
            NasLibraryIntent.DismissDiagnostic -> {
                diagnosticJob?.cancel()
                diagnosticJob = null
                diagnosticSettings = null
                _uiState.update { it.copy(isDiagnosing = false, diagnostic = null, diagnosticError = null) }
            }
        }
    }

    /** Load the first page and verify health, authentication and books access. */
    fun refresh() {
        loadPage(page = 1, reset = true)
    }

    fun loadNextPage() = onIntent(NasLibraryIntent.NextPage)

    fun loadPreviousPage() = onIntent(NasLibraryIntent.PreviousPage)

    /**
     * Runs the repository's non-mutating URL, health, authentication and
     * books checks. The token is passed only to the repository and is never
     * copied into UI state or included in the resulting report.
     */
    private fun diagnoseConnection() {
        if (diagnosticJob?.isActive == true) return
        val settings = nasSettingsGateway.currentSettings
        diagnosticSettings = settings
        diagnosticJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isDiagnosing = true,
                    diagnostic = null,
                    diagnosticError = null,
                )
            }
            runCatching {
                nasLibraryUseCase.diagnoseConnection(
                    apiUrl = settings.apiUrl,
                    token = settings.apiToken.takeIf(String::isNotBlank),
                )
            }.onSuccess { report ->
                // The settings may have changed before the response arrived;
                // discard a report generated for a stale token or URL.
                if (nasSettingsGateway.currentSettings != settings) {
                    diagnosticSettings = null
                    _uiState.update { it.copy(isDiagnosing = false) }
                    return@onSuccess
                }
                diagnosticSettings = settings
                _uiState.update {
                    it.copy(
                        isDiagnosing = false,
                        diagnostic = report,
                        diagnosticError = null,
                        connection = report.connection ?: it.connection,
                        capabilities = report.connection?.capabilities ?: it.capabilities,
                        writeAccessDenied = report.connection?.writeAccess
                            ?.resolveDenied(it.writeAccessDenied)
                            ?: it.writeAccessDenied,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException && error !is TimeoutCancellationException) {
                    throw error
                }
                if (nasSettingsGateway.currentSettings != settings) {
                    diagnosticSettings = null
                    _uiState.update { it.copy(isDiagnosing = false) }
                    return@onFailure
                }
                diagnosticSettings = settings
                _uiState.update {
                    it.copy(
                        isDiagnosing = false,
                        diagnostic = null,
                        diagnosticError = error.toNasMessage(),
                    )
                }
            }
        }
    }

    private fun loadPage(page: Int, reset: Boolean = false) {
        val settings = nasSettingsGateway.currentSettings
        if (settings.apiUrl.isBlank() || settings.apiToken.isBlank() ||
            !settings.connectionVerified
        ) {
            _uiState.value = NasLibraryUiState(
                query = _uiState.value.query,
                error = if (!settings.connectionVerified && settings.apiUrl.isNotBlank() &&
                    settings.apiToken.isNotBlank()
                ) {
                    "NAS 尚未通过连接测试"
                } else {
                    "NAS 地址或访问令牌未配置"
                },
            )
            return
        }
        if (loadJob?.isActive == true) return
        val targetPage = page.coerceAtLeast(1)
        val query = _uiState.value.query.trim().takeIf { it.isNotEmpty() }
        val pageSize = _uiState.value.pageSize
        val directoryPath = _uiState.value.directoryPath
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isConfigured = true,
                    isLoading = true,
                    isRefreshing = reset,
                    error = null,
                    books = if (reset) emptyList() else it.books,
                    page = targetPage,
                )
            }
            try {
                // A connection check is intentionally done on the first page
                // (and on pull-to-refresh).  Pagination only requests books.
                val connection = if (reset || _uiState.value.connection == null) {
                    nasLibraryUseCase.checkConnection(settings)
                } else {
                    _uiState.value.connection
                }
                val result = nasLibraryUseCase.listBooks(
                    page = targetPage,
                    pageSize = pageSize,
                    search = query,
                    directoryPath = directoryPath,
                    settings = settings,
                )
                if (!isCurrentSettings(settings)) {
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        books = result.items.distinctBy(NasBook::id),
                        page = result.page,
                        pageSize = result.pageSize,
                        total = result.total,
                        isLoading = false,
                        isRefreshing = false,
                        connection = connection,
                        capabilities = connection?.capabilities ?: it.capabilities,
                        writeAccessDenied = connection?.writeAccess
                            ?.resolveDenied(it.writeAccessDenied)
                            ?: it.writeAccessDenied,
                        error = null,
                    )
                }
                if (targetPage == 1 && connection?.capabilities?.supportsLibraryDirectories == true) {
                    loadDirectories()
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                if (!isCurrentSettings(settings)) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = cancelled.toNasMessage(),
                    )
                }
            } catch (error: Throwable) {
                if (!isCurrentSettings(settings)) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = error.toNasMessage(),
                    )
                }
            }
        }
    }

    private fun selectBook(book: NasBook) {
        connectionJob?.cancel()
        _uiState.update {
            it.copy(
                selectedBook = book,
                isLoadingDetail = true,
                detailError = null,
            )
        }
        val settings = nasSettingsGateway.currentSettings
        connectionJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                nasLibraryUseCase.detail(book.id, settings)
            }.onSuccess { detail ->
                if (!isCurrentSettings(settings)) {
                    _uiState.update { it.copy(isLoadingDetail = false) }
                    return@onSuccess
                }
                _uiState.update {
                    it.copy(
                        selectedBook = detail.toBook(),
                        isLoadingDetail = false,
                        detailError = null,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException && error !is TimeoutCancellationException) {
                    throw error
                }
                if (!isCurrentSettings(settings)) {
                    _uiState.update { it.copy(isLoadingDetail = false) }
                    return@onFailure
                }
                // The list item is still useful when an older NAS service does
                // not expose /api/books/:id, so keep it visible and explain the
                // failed enrichment in the dialog.
                _uiState.update {
                    it.copy(
                        isLoadingDetail = false,
                        detailError = error.toNasMessage(),
                    )
                }
            }
        }
    }

    private fun downloadBook(book: NasBook) {
        if (!canReadAction("download")) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isActionRunning = true, actionError = null) }
            var temporaryFile: File? = null
            runCatching {
                val safeName = book.fileName.ifBlank { "${book.displayTitle}.txt" }
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val file = File(context.cacheDir, "nas-download-${System.currentTimeMillis()}-$safeName")
                temporaryFile = file
                FileOutputStream(file).use { output ->
                    nasLibraryUseCase.downloadBook(
                        id = book.id,
                        sink = NasDownloadSink { chunk, offset, length ->
                            output.write(chunk, offset, length)
                        },
                    )
                }
                val uri = FileInputStream(file).use { input ->
                    LocalBook.saveBookFile(input, safeName)
                }
                LocalBook.importFile(uri)
            }.onSuccess { imported ->
                _uiState.update { it.copy(isActionRunning = false, selectedBook = null) }
                _effects.emit(NasLibraryEffect.OpenLocalBook(imported))
            }.onFailure { error ->
                if (error is CancellationException && error !is TimeoutCancellationException) {
                    throw error
                }
                _uiState.update {
                    it.copy(
                        isActionRunning = false,
                        actionError = error.toNasMessage(),
                    )
                }
                _effects.emit(NasLibraryEffect.ShowMessage(error.toNasMessage()))
            }.also {
                temporaryFile?.delete()
            }
        }
    }

    private fun uploadFile(uri: Uri) {
        if (!_uiState.value.isConfigured || _uiState.value.writeAccessDenied ||
            !_uiState.value.capabilities.supports("upload")
        ) return
        val uploadDirectory = _uiState.value.directoryPath.orEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isActionRunning = true, actionError = null) }
            var temporaryFile: File? = null
            try {
                runCatching {
                    val name = queryFileName(uri) ?: "nas-upload.bin"
                    val safeName = sanitizeNasUploadFileName(name)
                    val file = File(context.cacheDir, "nas-upload-${System.currentTimeMillis()}-$safeName")
                    temporaryFile = file
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "无法读取上传文件" }
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    nasLibraryUseCase.uploadBook(
                        source = NasUploadSource { sink ->
                            FileInputStream(file).use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count > 0) sink.write(buffer, 0, count)
                                }
                            }
                        },
                        fileName = safeName,
                        contentLength = file.length(),
                        directoryPath = uploadDirectory,
                    )
                }.onSuccess { result ->
                    _uiState.update { it.copy(isActionRunning = false) }
                    val message = when {
                        result.hasDuplicate -> "NAS 上传发现重复文件，请检查重复处理结果"
                        result.failed.isNotEmpty() -> "NAS 上传完成，但有 ${result.failed.size} 个文件失败"
                        else -> "NAS 上传完成"
                    }
                    _effects.emit(NasLibraryEffect.ShowMessage(message))
                    refresh()
                }.onFailure { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) {
                        throw error
                    }
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            writeAccessDenied = it.writeAccessDenied || error.isWritePermissionError(),
                            actionError = error.toNasMessage(),
                        )
                    }
                    _effects.emit(NasLibraryEffect.ShowMessage(error.toNasMessage()))
                }
            } finally {
                temporaryFile?.delete()
            }
        }
    }

    private fun queryFileName(uri: Uri): String? = context.contentResolver.query(
        uri,
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun runAction(label: String, action: suspend () -> NasTaskStatus) {
        if (_uiState.value.isActionRunning || !_uiState.value.isConfigured ||
            _uiState.value.writeAccessDenied
        ) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isActionRunning = true, actionError = null) }
            runCatching { action() }
                .onSuccess {
                    _uiState.update { it.copy(isActionRunning = false) }
                    _effects.emit(NasLibraryEffect.ShowMessage("${label}已提交"))
                    loadTasks()
                }
                .onFailure { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) {
                        throw error
                    }
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            writeAccessDenied = it.writeAccessDenied || error.isWritePermissionError(),
                            actionError = error.toNasMessage(),
                        )
                    }
                    if (error is NasTaskConflictException) {
                        // A 409 includes the task that is already running. Load
                        // the task center so the user can inspect its progress
                        // instead of seeing a misleading submission success.
                        loadTasks()
                    }
                    _effects.emit(NasLibraryEffect.ShowMessage(error.toNasMessage()))
                }
        }
    }

    private fun loadTasks() {
        val settings = nasSettingsGateway.currentSettings
        if (settings.apiUrl.isBlank() || settings.apiToken.isBlank() ||
            !settings.connectionVerified
        ) {
            _uiState.update { it.copy(tasks = emptyList()) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { nasLibraryUseCase.tasks() }
                .onSuccess { tasks ->
                    if (isCurrentSettings(settings)) {
                        _uiState.update { it.copy(tasks = tasks) }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) {
                        throw error
                    }
                    if (isCurrentSettings(settings)) {
                        _uiState.update { it.copy(actionError = error.toNasMessage()) }
                    }
                }
        }
    }

    private fun loadDirectories() {
        val settings = nasSettingsGateway.currentSettings
        if (settings.apiUrl.isBlank() || settings.apiToken.isBlank() ||
            !settings.connectionVerified
        ) return
        if (_uiState.value.isLoadingDirectories ||
            !_uiState.value.capabilities.supportsLibraryDirectories
        ) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingDirectories = true) }
            runCatching { nasLibraryUseCase.libraryDirectories() }
                .onSuccess { directories ->
                    if (isCurrentSettings(settings)) {
                        _uiState.update {
                            it.copy(directories = directories, isLoadingDirectories = false)
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) {
                        throw error
                    }
                    if (isCurrentSettings(settings)) {
                        _uiState.update {
                            it.copy(isLoadingDirectories = false, actionError = error.toNasMessage())
                        }
                    }
                }
        }
    }

    private fun saveMetadata(intent: NasLibraryIntent.SaveMetadata) {
        if (!canWriteAction("books")) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isActionRunning = true, actionError = null) }
            runCatching {
                nasLibraryUseCase.updateMetadata(
                    id = intent.book.id,
                    title = intent.title,
                    author = intent.author,
                    intro = intent.intro,
                )
            }.onSuccess { detail ->
                val updated = detail.toBook()
                _uiState.update { state ->
                    state.copy(
                        isActionRunning = false,
                        editingBook = null,
                        selectedBook = updated,
                        books = state.books.map { if (it.id == updated.id) updated else it },
                    )
                }
                _effects.emit(NasLibraryEffect.ShowMessage("NAS 元数据已保存"))
            }.onFailure { error ->
                if (error is CancellationException && error !is TimeoutCancellationException) {
                    throw error
                }
                _uiState.update {
                    it.copy(
                        isActionRunning = false,
                        writeAccessDenied = it.writeAccessDenied || error.isWritePermissionError(),
                        actionError = error.toNasMessage(),
                    )
                }
                _effects.emit(NasLibraryEffect.ShowMessage(error.toNasMessage()))
            }
        }
    }

    private fun moveBook(intent: NasLibraryIntent.MoveBook) {
        if (!canWriteAction("libraryDirectories")) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isActionRunning = true, actionError = null) }
            runCatching {
                nasLibraryUseCase.moveBook(
                    id = intent.book.id,
                    targetDirectoryPath = intent.targetDirectoryPath,
                    duplicatePolicy = intent.duplicatePolicy,
                )
            }.onSuccess { result ->
                val message = when {
                    result.isDuplicate -> "目标分类存在重复文件，未移动"
                    result.isSuccess -> "NAS 书籍已移动"
                    else -> "NAS 移动结果：${result.status.ifBlank { "未知" }}"
                }
                _uiState.update { it.copy(isActionRunning = false, movingBook = null) }
                _effects.emit(NasLibraryEffect.ShowMessage(message))
                refresh()
            }.onFailure { error ->
                if (error is CancellationException && error !is TimeoutCancellationException) {
                    throw error
                }
                _uiState.update {
                    it.copy(
                        isActionRunning = false,
                        writeAccessDenied = it.writeAccessDenied || error.isWritePermissionError(),
                        actionError = error.toNasMessage(),
                    )
                }
                _effects.emit(NasLibraryEffect.ShowMessage(error.toNasMessage()))
            }
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        connectionJob?.cancel()
        diagnosticJob?.cancel()
        diagnosticSettings = null
        super.onCleared()
    }

    private fun Throwable.toNasMessage(): String = when (this) {
        is TimeoutCancellationException -> "NAS 请求超时，请检查服务是否在线"
        is IOException -> "无法连接 NAS，请检查网络和服务地址"
        is NasHttpException -> when (statusCode) {
            401 -> "NAS 访问令牌无效或已过期（401）"
            403 -> "NAS 令牌没有访问权限（403）"
            else -> message ?: "NAS 请求失败（HTTP $statusCode）"
        }
        else -> localizedMessage?.takeIf { it.isNotBlank() } ?: "NAS 连接失败，请检查网络和服务地址"
    }

    private fun Throwable.isWritePermissionError(): Boolean =
        this is NasHttpException && statusCode in 401..403

    private fun NasWriteAccess.resolveDenied(previous: Boolean): Boolean = when (this) {
        NasWriteAccess.ALLOWED -> false
        NasWriteAccess.DENIED -> true
        NasWriteAccess.UNKNOWN -> previous
    }

    private fun isCurrentSettings(snapshot: NasSettings): Boolean =
        credentialsMatch(nasSettingsGateway.currentSettings, snapshot)

    private fun credentialsMatch(left: NasSettings, right: NasSettings): Boolean =
        nasLibraryUseCase.normalizeUrl(left.apiUrl) == nasLibraryUseCase.normalizeUrl(right.apiUrl) &&
            left.apiToken.trim() == right.apiToken.trim()

    private fun canReadAction(capability: String): Boolean {
        val state = _uiState.value
        return state.isConfigured && !state.isActionRunning && state.capabilities.supports(capability)
    }

    private fun canWriteAction(capability: String): Boolean =
        canReadAction(capability) && !_uiState.value.writeAccessDenied

}

/**
 * Keeps a document-provider display name within the app cache directory.
 * Providers are not required to return a filesystem-safe basename.
 */
internal fun sanitizeNasUploadFileName(rawName: String): String {
    val baseName = rawName
        .trim()
        .replace('\\', '/')
        .substringAfterLast('/')
    val sanitized = buildString(baseName.length) {
        baseName.forEach { character ->
            val unsafe = character.code < 0x20 || character in NAS_UNSAFE_FILENAME_CHARS
            append(if (unsafe) '_' else character)
        }
    }.trim('.', ' ')
    return sanitized.ifBlank { "nas-upload.bin" }
}

private val NAS_UNSAFE_FILENAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
