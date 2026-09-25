package io.legado.app.ui.main.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.data.repository.BookRepository
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.domain.model.HomeDashboardSection
import io.legado.app.domain.model.HomeReadingBook
import io.legado.app.domain.model.NasHttpException
import io.legado.app.domain.model.settings.NasSettings
import io.legado.app.domain.model.WebDavBackup
import io.legado.app.domain.usecase.BackupRestoreUseCase
import io.legado.app.domain.usecase.HomeDashboardUseCase
import io.legado.app.domain.usecase.NasLibraryUseCase
import io.legado.app.domain.usecase.WebDavBackupUseCase
import io.legado.app.utils.isContentScheme
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class HomeViewModel(
    private val homeDashboardUseCase: HomeDashboardUseCase,
    private val bookRepository: BookRepository,
    private val webDavBackupUseCase: WebDavBackupUseCase,
    private val backupRestoreUseCase: BackupRestoreUseCase,
    private val backupSettingsGateway: BackupSettingsGateway,
    private val nasLibraryUseCase: NasLibraryUseCase,
    private val nasSettingsGateway: NasSettingsGateway,
) : ViewModel() {

    private val _backupState = MutableStateFlow(HomeBackupState())
    private val _nasState = MutableStateFlow(NasHomeUiState())
    private val _activeDialog = MutableStateFlow<HomeDialog?>(null)
    private val _activeSheet = MutableStateFlow<HomeSheet?>(null)
    private val _effects = MutableSharedFlow<HomeEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()
    private var backupRefreshJob: Job? = null
    private var nasRefreshJob: Job? = null
    private var observedNasSettings: NasSettings? = null
    private val backupActionMutex = Mutex()

    private val dashboardData = combine(
        homeDashboardUseCase.observe(),
        homeDashboardUseCase.observeSelectedSourceSetUrl(),
        homeDashboardUseCase.observeVisibleSections(),
    ) { dashboard, selectedSourceUrl, visibleSections ->
        Triple(dashboard, selectedSourceUrl, visibleSections)
    }

    val uiState = combine(
        dashboardData,
        _backupState,
        _nasState,
        _activeDialog,
        _activeSheet,
    ) { dashboardData, backup, nas, dialog, sheet ->
        val (dashboard, selectedSourceUrl, visibleSections) = dashboardData
        HomeUiState(
            totalReadBooks = dashboard.totalReadBooks,
            totalReadTimeMillis = dashboard.totalReadTimeMillis,
            todayReadTimeMillis = dashboard.todayReadTimeMillis,
            dailyGoalMinutes = dashboard.dailyGoalMinutes,
            recentBook = dashboard.recentBooks.firstOrNull()?.toUi(),
            recentBooks = dashboard.recentBooks
                .drop(1)
                .take(6)
                .map { it.toUi() }
                .toImmutableList(),
            selectedSourceSetUrl = selectedSourceUrl,
            visibleSections = visibleSections.toImmutableSet(),
            latestBackup = backup.latest?.toUi(),
            isBackupLoading = backup.isLoading,
            isBackupLoadError = backup.isLoadError,
            isBackupActionRunning = backup.isActionRunning,
            nas = nas,
            activeDialog = dialog,
            activeSheet = sheet,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    init {
        viewModelScope.launch {
            homeDashboardUseCase.observeVisibleSections()
                .map { HomeDashboardSection.WebDavBackup in it }
                .distinctUntilChanged()
                .collect { visible ->
                    if (visible) {
                        refreshLatestBackup()
                    } else {
                        backupRefreshJob?.cancel()
                        _backupState.update { it.copy(isLoading = false) }
                    }
                }
        }
        viewModelScope.launch {
            nasSettingsGateway.settings.collect { settings ->
                val previousSettings = observedNasSettings
                val connectionInputsChanged = previousSettings == null ||
                    !sameNasConnectionInputs(previousSettings, settings)
                if (connectionInputsChanged) {
                    // A settings edit or restore invalidates any request started
                    // with the previous URL/token snapshot. Updating only the
                    // persisted error below must not cancel and restart a request.
                    nasRefreshJob?.cancel()
                    nasRefreshJob = null
                }
                // A restored URL/home preference is not enough to activate the card:
                // the bearer token is intentionally excluded from WebDAV backups and
                // must be entered and tested again on the new device.
                val configured = settings.apiUrl.isNotBlank() &&
                    settings.apiToken.isNotBlank() && settings.connectionVerified
                _nasState.update { current ->
                    if (connectionInputsChanged) {
                        NasHomeUiState(
                            configured = configured,
                            error = settings.lastConnectionError,
                        )
                    } else {
                        current.copy(
                            configured = configured,
                            error = settings.lastConnectionError,
                        )
                    }
                }
                // The NAS switch is the source of truth for the card. Keep the
                // dashboard section in sync so a successful first connection
                // immediately makes the card visible, while turning it off in
                // NAS settings removes it without touching other sections.
                val visible = homeDashboardUseCase.observeVisibleSections()
                val current = visible.first()
                // Keep an explicitly enabled card visible even while it is
                // unconfigured, so the user can see the state and open NAS
                // settings. The default remains hidden because showHomeCard is
                // false for new installs.
                val shouldContain = settings.showHomeCard
                val next = if (shouldContain) current + HomeDashboardSection.NasLibrary
                else current - HomeDashboardSection.NasLibrary
                if (next != current) homeDashboardUseCase.updateVisibleSections(next)
                if (settings.showHomeCard && configured && connectionInputsChanged) refreshNas()
                observedNasSettings = settings
            }
        }
    }

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            HomeIntent.RecentBookClick -> openRecentBook()
            is HomeIntent.RecentHistoryBookClick -> openBook(intent.bookUrl)
            is HomeIntent.SelectSourceSet -> selectSourceSet(intent.sourceUrl)
            HomeIntent.DashboardSettingsClick -> {
                _activeSheet.value = HomeSheet.DashboardSettings
            }

            is HomeIntent.SetSectionVisible -> {
                setSectionVisible(intent.section, intent.visible)
            }
            HomeIntent.ReadingGoalClick -> {
                _activeDialog.value = HomeDialog.SetReadingGoal(
                    uiState.value.dailyGoalMinutes
                )
            }

            is HomeIntent.UpdateReadingGoal -> updateReadingGoal(intent.minutes)
            HomeIntent.BackupClick -> _activeSheet.value = HomeSheet.BackupOptions
            is HomeIntent.BackupDestinationSelected -> {
                requestBackup(intent.destination)
            }

            is HomeIntent.BackupDirectorySelected -> {
                backup(
                    destination = intent.destination,
                    path = intent.path,
                    savePath = true,
                )
            }

            HomeIntent.RestoreClick -> _activeSheet.value = HomeSheet.RestoreOptions
            HomeIntent.RestoreFromLocal -> {
                _activeSheet.value = null
                _effects.tryEmit(HomeEffect.SelectRestoreFile)
            }

            HomeIntent.RestoreFromNetwork -> {
                _activeSheet.value = null
                requestRestore()
            }

            is HomeIntent.RestoreLocalFileSelected -> restoreLocal(intent.uri)
            HomeIntent.ConfirmRestore -> restore()
            HomeIntent.BackupSettingsClick -> {
                _effects.tryEmit(HomeEffect.OpenBackupSettings)
            }

            HomeIntent.RetryBackupInfo -> refreshLatestBackup()
            HomeIntent.NasCardClick -> _effects.tryEmit(HomeEffect.OpenNasLibrary)
            HomeIntent.NasSettingsClick -> _effects.tryEmit(HomeEffect.OpenNasSettings)
            HomeIntent.RetryNasConnection -> refreshNas()
            HomeIntent.DismissDialog -> _activeDialog.value = null
            HomeIntent.DismissSheet -> _activeSheet.value = null
        }
    }

    private fun openRecentBook() {
        val bookUrl = uiState.value.recentBook?.bookUrl ?: return
        openBook(bookUrl)
    }

    private fun openBook(bookUrl: String) {
        viewModelScope.launch {
            bookRepository.getBook(bookUrl)?.let {
                _effects.emit(HomeEffect.OpenBook(it))
            }
        }
    }

    private fun updateReadingGoal(minutes: Int) {
        _activeDialog.value = null
        viewModelScope.launch {
            homeDashboardUseCase.updateDailyGoal(minutes)
        }
    }

    private fun selectSourceSet(sourceUrl: String) {
        viewModelScope.launch {
            homeDashboardUseCase.updateSelectedSourceSetUrl(sourceUrl)
        }
    }

    private fun setSectionVisible(
        section: HomeDashboardSection,
        visible: Boolean,
    ) {
        // NAS is intentionally opt-in only after a successful connection test.
        // A restored URL or a token that has not been verified must never cause
        // the home page to start probing the service.
        if (section == HomeDashboardSection.NasLibrary && visible &&
            !_nasState.value.isConnected
        ) {
            return
        }
        val sections = uiState.value.visibleSections.toMutableSet().apply {
            if (visible) add(section) else remove(section)
        }
        viewModelScope.launch {
            homeDashboardUseCase.updateVisibleSections(sections)
            if (section == HomeDashboardSection.NasLibrary) {
                nasSettingsGateway.update { it.copy(showHomeCard = visible) }
            }
        }
    }

    private fun requestRestore() {
        val backup = _backupState.value.latest
        if (backup == null) {
            _effects.tryEmit(
                HomeEffect.ShowMessage(
                    if (_backupState.value.isLoadError) {
                        R.string.home_webdav_backup_load_error
                    } else {
                        R.string.home_no_webdav_backup
                    }
                )
            )
            return
        }
        _activeDialog.value = HomeDialog.ConfirmRestore(backup.name)
    }

    private fun requestBackup(destination: HomeBackupDestination) {
        _activeSheet.value = null
        if (destination == HomeBackupDestination.WebDav) {
            backup(destination = destination, path = null)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val path = backupSettingsGateway.currentSettings.backupPath
            if (path.isNullOrBlank()) {
                _effects.emit(HomeEffect.SelectBackupDirectory(destination))
            } else if (!path.isContentScheme()) {
                _effects.emit(
                    HomeEffect.RequestBackupStoragePermission(
                        destination = destination,
                        path = path,
                    )
                )
            } else {
                backup(destination = destination, path = path)
            }
        }
    }

    private fun backup(
        destination: HomeBackupDestination,
        path: String?,
        savePath: Boolean = false,
    ) {
        if (!backupActionMutex.tryLock()) return
        _backupState.update { it.copy(isActionRunning = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching {
                    if (savePath) {
                        backupSettingsGateway.update { it.copy(backupPath = path) }
                    }
                    if (destination != HomeBackupDestination.Local) {
                        webDavBackupUseCase.refreshConfig()
                    }
                    backupRestoreUseCase.backup(path, destination.mode)
                }.onSuccess {
                    _effects.emit(HomeEffect.ShowMessage(R.string.backup_success))
                    if (destination != HomeBackupDestination.Local) {
                        refreshLatestBackup()
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    _effects.emit(
                        HomeEffect.ShowMessage(
                            messageRes = R.string.backup_error,
                            detail = error.localizedMessage,
                        )
                    )
                }
            } finally {
                _backupState.update { it.copy(isActionRunning = false) }
                backupActionMutex.unlock()
            }
        }
    }

    private fun restoreLocal(uri: String) {
        if (!backupActionMutex.tryLock()) return
        _backupState.update { it.copy(isActionRunning = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching {
                    backupRestoreUseCase.restoreLocal(uri)
                }.onSuccess {
                    _effects.emit(HomeEffect.ShowMessage(R.string.restore_success))
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    _effects.emit(
                        HomeEffect.ShowMessage(
                            messageRes = R.string.restore_error,
                            detail = error.localizedMessage,
                        )
                    )
                }
            } finally {
                _backupState.update { it.copy(isActionRunning = false) }
                backupActionMutex.unlock()
            }
        }
    }

    private fun restore() {
        val backup = _backupState.value.latest ?: return
        _activeDialog.value = null
        if (!backupActionMutex.tryLock()) return
        _backupState.update { it.copy(isActionRunning = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching {
                    webDavBackupUseCase.restore(backup.name)
                }.onSuccess {
                    _effects.emit(HomeEffect.ShowMessage(R.string.restore_success))
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    _effects.emit(
                        HomeEffect.ShowMessage(
                            messageRes = R.string.restore_error,
                            detail = error.localizedMessage,
                        )
                    )
                }
            } finally {
                _backupState.update { it.copy(isActionRunning = false) }
                backupActionMutex.unlock()
            }
        }
    }

    private fun refreshLatestBackup() {
        backupRefreshJob?.cancel()
        backupRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            loadLatestBackup()
        }
    }

    fun refreshNas() {
        nasRefreshJob?.cancel()
        nasRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            val settings = nasSettingsGateway.currentSettings
            if (!settings.showHomeCard || settings.apiUrl.isBlank() ||
                settings.apiToken.isBlank() || !settings.connectionVerified
            ) {
                _nasState.value = NasHomeUiState(configured = false)
                return@launch
            }
            _nasState.update { it.copy(configured = true, isLoading = true, error = null) }
            runCatching {
                val connection = nasLibraryUseCase.checkConnection(settings)
                val total = nasLibraryUseCase.listBooks(
                    page = 1,
                    pageSize = 1,
                    settings = settings,
                ).total
                Pair(total, connection)
            }.onSuccess { result ->
                if (nasSettingsGateway.currentSettings != settings) return@onSuccess
                val total = result.first
                _nasState.update { it.copy(isLoading = false, isConnected = true, bookCount = total, error = null) }
                persistNasConnectionError(settings, null)
            }.onFailure { error ->
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (nasSettingsGateway.currentSettings != settings) return@onFailure
                val message = error.toNasErrorMessage()
                _nasState.update {
                    it.copy(
                        isLoading = false,
                        isConnected = false,
                        error = message,
                    )
                }
                persistNasConnectionError(settings, message)
            }
        }
    }

    private suspend fun persistNasConnectionError(
        snapshot: NasSettings,
        message: String?,
    ) {
        runCatching {
            nasSettingsGateway.update { current ->
                if (sameNasConnectionInputs(current, snapshot)) {
                    current.copy(lastConnectionError = message)
                } else {
                    current
                }
            }
        }
    }

    private fun sameNasConnectionInputs(left: NasSettings, right: NasSettings): Boolean =
        left.apiUrl == right.apiUrl &&
            left.apiToken == right.apiToken &&
            left.showHomeCard == right.showHomeCard &&
            left.connectionVerified == right.connectionVerified

    private suspend fun loadLatestBackup() {
        _backupState.update {
            it.copy(
                isLoading = true,
                isLoadError = false,
            )
        }
        try {
            val latest = webDavBackupUseCase.getLatestBackup()
            _backupState.update {
                it.copy(
                    latest = latest,
                    isLoading = false,
                    isLoadError = false,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _backupState.update {
                it.copy(
                    isLoading = false,
                    isLoadError = true,
                )
            }
        }
    }

    private data class HomeBackupState(
        val latest: WebDavBackup? = null,
        val isLoading: Boolean = true,
        val isLoadError: Boolean = false,
        val isActionRunning: Boolean = false,
    )

    private fun WebDavBackup.toUi() = HomeBackupUi(
        name = name,
        lastModify = lastModify,
    )

    private fun HomeReadingBook.toUi() = HomeRecentBookUi(
        bookUrl = bookUrl,
        name = name,
        author = author,
        origin = origin,
        coverPath = coverPath,
        chapterTitle = chapterTitle,
        chapterProgress = chapterProgress,
    )
}

private fun Throwable.toNasErrorMessage(): String = when (this) {
    is TimeoutCancellationException -> "NAS 请求超时，请检查服务是否在线"
    is java.io.IOException -> "无法连接 NAS，请检查网络和服务地址"
    is NasHttpException -> when (statusCode) {
        401 -> "NAS 访问令牌无效或已过期（401）"
        403 -> "NAS 令牌没有访问权限（403）"
        else -> message ?: "NAS 请求失败（HTTP $statusCode）"
    }
    else -> localizedMessage?.takeIf { it.isNotBlank() } ?: "NAS 连接失败，请检查网络和服务地址"
}
