package io.legado.app.ui.config.backupConfig

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.model.NasHttpException
import io.legado.app.domain.model.settings.BackupSettings
import io.legado.app.domain.model.settings.NasSettings
import io.legado.app.domain.usecase.BackupRestoreUseCase
import io.legado.app.domain.usecase.NasLibraryUseCase
import io.legado.app.domain.usecase.WebDavBackupUseCase
import io.legado.app.utils.isContentScheme
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupConfigViewModel(
    private val settingsGateway: BackupSettingsGateway,
    private val webDavBackupUseCase: WebDavBackupUseCase,
    private val backupRestoreUseCase: BackupRestoreUseCase,
    private val nasSettingsGateway: NasSettingsGateway,
    private val nasLibraryUseCase: NasLibraryUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        BackupConfigUiState(
            settings = settingsGateway.currentSettings,
            nasSettings = nasSettingsGateway.currentSettings,
            nasConnectionState = deriveNasConnectionState(nasSettingsGateway.currentSettings),
            ignoreItems = loadIgnoreItems(),
            backupIgnoreItems = loadBackupIgnoreItems(),
            dbIgnoreItems = loadDbIgnoreItems(),
            backupDbIgnoreItems = loadBackupDbIgnoreItems(),
        )
    )
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<BackupConfigEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()
    private var nasTestJob: Job? = null
    private var nasSettingsWriteJob: Job? = null

    init {
        viewModelScope.launch {
            settingsGateway.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            nasSettingsGateway.settings.collect { settings ->
                _uiState.update { state ->
                    state.copy(
                        nasSettings = settings,
                        // Editing a value invalidates a previous successful test. Keep the
                        // explicit failure message until the next edit or test, but never show
                        // Connected for a changed endpoint/token.
                        nasConnectionState = when {
                            state.nasConnectionState == NasConnectionState.Testing &&
                                settings.apiUrl.isNotBlank() && settings.apiToken.isNotBlank() ->
                                NasConnectionState.Testing
                            else -> deriveNasConnectionState(settings)
                        },
                    )
                }
            }
        }
    }

    fun onIntent(intent: BackupConfigIntent) {
        when (intent) {
            is BackupConfigIntent.SetWebDavUrl -> update { it.copy(webDavUrl = intent.value) }
            is BackupConfigIntent.SetWebDavDir -> update { it.copy(webDavDir = intent.value) }
            is BackupConfigIntent.SetWebDavDeviceName ->
                update { it.copy(webDavDeviceName = intent.value) }
            is BackupConfigIntent.SetSyncBookProgress -> setSyncBookProgress(intent.value)
            is BackupConfigIntent.SetSyncBookProgressPlus ->
                update { it.copy(syncBookProgressPlus = intent.value) }
            is BackupConfigIntent.SetAutoCheckNewBackup ->
                update { it.copy(autoCheckNewBackup = intent.value) }
            is BackupConfigIntent.SetOnlyLatestBackup ->
                update { it.copy(onlyLatestBackup = intent.value) }
            is BackupConfigIntent.SetBackupSyncMode ->
                update { it.copy(backupSyncMode = intent.value) }
            is BackupConfigIntent.OpenSheet -> _uiState.update { it.copy(activeSheet = intent.sheet) }
            BackupConfigIntent.DismissSheet -> _uiState.update { it.copy(activeSheet = null) }
            BackupConfigIntent.OpenWebDavAuth -> openWebDavAuth()
            is BackupConfigIntent.EditWebDavAccount -> updateAuth { it.copy(account = intent.value) }
            is BackupConfigIntent.EditWebDavPassword -> updateAuth { it.copy(password = intent.value) }
            BackupConfigIntent.TogglePasswordVisibility ->
                updateAuth { it.copy(passwordVisible = !it.passwordVisible) }
            BackupConfigIntent.SaveWebDavAuth -> saveWebDavAuth()
            BackupConfigIntent.TestWebDav -> testWebDav()
            BackupConfigIntent.OpenNasToken -> openNasToken()
            is BackupConfigIntent.EditNasToken -> updateNasToken {
                it.copy(token = intent.value)
            }
            BackupConfigIntent.ToggleNasTokenVisibility -> updateNasToken {
                it.copy(tokenVisible = !it.tokenVisible)
            }
            BackupConfigIntent.SaveNasToken -> saveNasToken()
            is BackupConfigIntent.SetNasUrl -> updateNas {
                it.copy(
                    apiUrl = intent.value,
                    // A changed endpoint must be tested again before the card can be enabled.
                    showHomeCard = false,
                    connectionVerified = false,
                    lastConnectionError = null,
                )
            }
            is BackupConfigIntent.SetNasToken -> updateNas {
                it.copy(
                    apiToken = intent.value,
                    // Tokens are intentionally not restored from WebDAV; entering one does
                    // not count as a successful connection test.
                    showHomeCard = false,
                    connectionVerified = false,
                    lastConnectionError = null,
                )
            }
            is BackupConfigIntent.SetNasShowHomeCard -> {
                if (!intent.value || _uiState.value.nasConnectionState == NasConnectionState.Connected) {
                    updateNas { it.copy(showHomeCard = intent.value) }
                }
            }
            BackupConfigIntent.TestNasConnection -> testNasConnection()
            BackupConfigIntent.OpenIgnoreDialog ->
                _uiState.update { it.copy(activeSheet = BackupConfigSheet.IgnoreRestoreItems) }
            is BackupConfigIntent.ToggleIgnoreItem -> toggleIgnoreItem(intent.key, intent.value)
            BackupConfigIntent.SaveIgnoreItems -> saveIgnoreItems()
            BackupConfigIntent.OpenBackupIgnoreDialog ->
                _uiState.update { it.copy(activeSheet = BackupConfigSheet.IgnoreBackupItems) }

            is BackupConfigIntent.ToggleBackupIgnoreItem -> toggleBackupIgnoreItem(
                intent.key,
                intent.value
            )

            BackupConfigIntent.SaveBackupIgnoreItems -> saveBackupIgnoreItems()
            is BackupConfigIntent.ToggleDbIgnoreItem -> toggleDbIgnoreItem(intent.key, intent.value)
            BackupConfigIntent.SaveDbIgnoreItems -> saveDbIgnoreItems()
            is BackupConfigIntent.ToggleBackupDbIgnoreItem -> toggleBackupDbIgnoreItem(
                intent.key,
                intent.value
            )

            BackupConfigIntent.SaveBackupDbIgnoreItems -> saveBackupDbIgnoreItems()
            BackupConfigIntent.DismissDialog -> _uiState.update { it.copy(activeDialog = null) }
            BackupConfigIntent.SelectBackupDirectory -> launchDirectoryPicker(runBackup = false)
            BackupConfigIntent.SelectBackupAndRunDirectory -> launchDirectoryPicker(runBackup = true)
            is BackupConfigIntent.BackupDirectorySelected -> saveBackupPath(intent.path, intent.runBackup)
            is BackupConfigIntent.RequestBackup -> requestBackup(intent.mode)
            is BackupConfigIntent.PerformBackup -> performBackup(intent.path, intent.mode)
            BackupConfigIntent.RequestLocalRestore -> requestLocalRestore()
            is BackupConfigIntent.RestoreLocal -> restoreLocal(intent.uri)
            BackupConfigIntent.RequestNetworkRestore -> loadNetworkBackups()
            is BackupConfigIntent.RestoreNetwork -> restoreNetwork(intent.name)
            BackupConfigIntent.ConfirmLocalRestoreFallback -> requestLocalRestore()
            BackupConfigIntent.RequestImportOldData ->
                _effects.tryEmit(BackupConfigEffect.LaunchImportOldDataPicker)
        }
    }

    private fun update(transform: (BackupSettings) -> BackupSettings) {
        viewModelScope.launch { settingsGateway.update(transform) }
    }

    private fun updateNas(transform: (NasSettings) -> NasSettings) {
        // A changed URL/token invalidates any request using the old snapshot.
        nasTestJob?.cancel()
        // Keep the UI responsive while AppConfigStore serializes the write. The collector
        // below remains the source of truth for the persisted snapshot.
        _uiState.update { state ->
            val next = transform(state.nasSettings)
            state.copy(
                nasSettings = next,
                nasConnectionState = deriveNasConnectionState(next),
            )
        }
        nasSettingsWriteJob = viewModelScope.launch { nasSettingsGateway.update(transform) }
    }

    private fun testNasConnection() {
        nasTestJob?.cancel()
        val entered = _uiState.value.nasSettings
        val normalizedUrl = nasLibraryUseCase.normalizeUrl(entered.apiUrl)
        if (normalizedUrl == null) {
            finishNasTestWithoutRequest(
                snapshot = entered,
                state = NasConnectionState.NotConfigured,
                message = if (entered.apiUrl.isBlank()) "NAS 地址未配置" else "NAS 地址无效",
            )
            return
        }
        val settings = entered.copy(apiUrl = normalizedUrl)
        if (entered.apiToken.isBlank()) {
            finishNasTestWithoutRequest(
                snapshot = entered,
                state = NasConnectionState.Failed,
                message = "NAS 访问令牌未配置",
            )
            return
        }
        _uiState.update {
            it.copy(
                nasConnectionState = NasConnectionState.Testing,
                nasSettings = it.nasSettings.copy(apiUrl = normalizedUrl),
            )
        }
        nasTestJob = viewModelScope.launch(Dispatchers.IO) {
            // Wait for the latest edit to reach the persistent gateway. A fast
            // response must not be discarded as an old URL/token snapshot.
            nasSettingsWriteJob?.join()
            val result = runCatching { nasLibraryUseCase.checkConnection(settings) }
            val failure = result.exceptionOrNull()
            if (failure is CancellationException && failure !is TimeoutCancellationException) {
                throw failure
            }
            // A request may outlive an edit in the settings screen. Never let an
            // old response mark a newer endpoint/token as trusted.
            if (!nasCredentialsStillMatch(settings)) return@launch
            if (result.isSuccess) {
                // A successful test is the only operation that enables the home card. It
                // never exports or copies the token into WebDAV settings.
                nasSettingsGateway.update {
                    if (nasCredentialsStillMatch(it, settings)) {
                        it.copy(
                            apiUrl = normalizedUrl,
                            showHomeCard = true,
                            connectionVerified = true,
                            lastConnectionError = null,
                        )
                    } else it
                }
                if (nasCredentialsStillMatch(settings) &&
                    credentialsMatch(nasSettingsGateway.currentSettings, settings)
                ) {
                    _uiState.update {
                        it.copy(
                            nasConnectionState = NasConnectionState.Connected,
                            nasSettings = it.nasSettings.copy(
                                apiUrl = normalizedUrl,
                                showHomeCard = true,
                                connectionVerified = true,
                                lastConnectionError = null,
                            ),
                        )
                    }
                    _effects.tryEmit(BackupConfigEffect.ShowMessage(R.string.nas_test_success))
                }
            } else {
                val message = failure?.toNasErrorMessage() ?: "NAS 连接失败"
                // Preserve the user's switch choice on failure; a failed test must not
                // unexpectedly make the card visible.
                nasSettingsGateway.update {
                    if (nasCredentialsStillMatch(it, settings)) {
                        it.copy(
                            apiUrl = normalizedUrl,
                            connectionVerified = false,
                            lastConnectionError = message,
                        )
                    } else it
                }
                if (nasCredentialsStillMatch(settings) &&
                    credentialsMatch(nasSettingsGateway.currentSettings, settings)
                ) {
                    _uiState.update {
                        it.copy(
                            nasConnectionState = NasConnectionState.Failed,
                            nasSettings = it.nasSettings.copy(
                                apiUrl = normalizedUrl,
                                connectionVerified = false,
                                lastConnectionError = message,
                            ),
                        )
                    }
                    _effects.tryEmit(BackupConfigEffect.ShowMessage(R.string.nas_test_failed, message))
                }
            }
        }
    }

    private fun finishNasTestWithoutRequest(
        snapshot: NasSettings,
        state: NasConnectionState,
        message: String,
    ) {
        _uiState.update {
            if (!nasCredentialsStillMatch(snapshot, it.nasSettings)) it
            else it.copy(
                nasConnectionState = state,
                nasSettings = it.nasSettings.copy(
                    connectionVerified = false,
                    lastConnectionError = message,
                ),
            )
        }
        viewModelScope.launch {
            nasSettingsGateway.update {
                if (nasCredentialsStillMatch(it, snapshot)) {
                    it.copy(connectionVerified = false, lastConnectionError = message)
                } else it
            }
        }
        _effects.tryEmit(BackupConfigEffect.ShowMessage(R.string.nas_test_failed, message))
    }

    private fun nasCredentialsStillMatch(snapshot: NasSettings): Boolean =
        credentialsMatch(_uiState.value.nasSettings, snapshot)

    private fun nasCredentialsStillMatch(left: NasSettings, right: NasSettings): Boolean =
        credentialsMatch(left, right)

    private fun credentialsMatch(left: NasSettings, right: NasSettings): Boolean =
        nasLibraryUseCase.normalizeUrl(left.apiUrl) == nasLibraryUseCase.normalizeUrl(right.apiUrl) &&
            left.apiToken.trim() == right.apiToken.trim()

    private fun setSyncBookProgress(value: Boolean) {
        viewModelScope.launch {
            settingsGateway.update {
                it.copy(
                    syncBookProgress = value,
                    syncBookProgressPlus = it.syncBookProgressPlus && value,
                )
            }
        }
    }

    private fun openWebDavAuth() {
        val settings = _uiState.value.settings
        _uiState.update {
            it.copy(
                activeDialog = BackupConfigDialog.WebDavAuth(
                    account = settings.webDavAccount,
                    password = settings.webDavPassword,
                )
            )
        }
    }

    private fun updateAuth(transform: (BackupConfigDialog.WebDavAuth) -> BackupConfigDialog.WebDavAuth) {
        _uiState.update { state ->
            val dialog = state.activeDialog as? BackupConfigDialog.WebDavAuth ?: return@update state
            state.copy(activeDialog = transform(dialog))
        }
    }

    private fun openNasToken() {
        _uiState.update {
            it.copy(
                activeDialog = BackupConfigDialog.NasToken(
                    token = it.nasSettings.apiToken,
                )
            )
        }
    }

    private fun updateNasToken(
        transform: (BackupConfigDialog.NasToken) -> BackupConfigDialog.NasToken,
    ) {
        _uiState.update { state ->
            val dialog = state.activeDialog as? BackupConfigDialog.NasToken
                ?: return@update state
            state.copy(activeDialog = transform(dialog))
        }
    }

    private fun saveNasToken() {
        val dialog = _uiState.value.activeDialog as? BackupConfigDialog.NasToken ?: return
        // Reuse the normal setting path so changing a token invalidates the local trust
        // marker and never enables the home card before a fresh connection test.
        if (dialog.token.trim() != _uiState.value.nasSettings.apiToken.trim()) {
            onIntent(BackupConfigIntent.SetNasToken(dialog.token))
        }
        _uiState.update { it.copy(activeDialog = null) }
    }

    private fun saveWebDavAuth() {
        val dialog = _uiState.value.activeDialog as? BackupConfigDialog.WebDavAuth ?: return
        viewModelScope.launch {
            settingsGateway.update {
                it.copy(webDavAccount = dialog.account, webDavPassword = dialog.password)
            }
            testWebDav()
        }
    }

    private fun testWebDav() {
        _uiState.update { it.copy(activeDialog = BackupConfigDialog.Loading(R.string.test_sync_loading_text)) }
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching { webDavBackupUseCase.test() }.getOrDefault(false)
            }
            _uiState.update { it.copy(activeDialog = null) }
            _effects.tryEmit(
                BackupConfigEffect.ShowMessage(
                    if (success) R.string.test_sync_status_success else R.string.test_sync_status_fail
                )
            )
        }
    }

    private fun toggleIgnoreItem(key: String, value: Boolean) {
        _uiState.update { state ->
            state.copy(
                ignoreItems = state.ignoreItems.map { item ->
                    if (item.key == key) item.copy(checked = value) else item
                }.toImmutableList()
            )
        }
    }

    private fun saveIgnoreItems() {
        _uiState.value.ignoreItems.forEach { item ->
            io.legado.app.help.storage.BackupConfig.ignoreConfig[item.key] = item.checked
        }
        _uiState.value.dbIgnoreItems.forEach { item ->
            io.legado.app.help.storage.BackupConfig.dbIgnoreConfig[item.key] = item.checked
        }
        io.legado.app.help.storage.BackupConfig.saveIgnoreConfig()
        io.legado.app.help.storage.BackupConfig.saveDbIgnoreConfig()
        _uiState.update { it.copy(activeSheet = null) }
    }

    private fun toggleBackupIgnoreItem(key: String, value: Boolean) {
        _uiState.update { state ->
            state.copy(
                backupIgnoreItems = state.backupIgnoreItems.map { item ->
                    if (item.key == key) item.copy(checked = value) else item
                }.toImmutableList()
            )
        }
    }

    private fun saveBackupIgnoreItems() {
        _uiState.value.backupIgnoreItems.forEach { item ->
            io.legado.app.help.storage.BackupConfig.backupIgnoreConfig[item.key] = item.checked
        }
        _uiState.value.backupDbIgnoreItems.forEach { item ->
            io.legado.app.help.storage.BackupConfig.backupDbIgnoreConfig[item.key] = item.checked
        }
        io.legado.app.help.storage.BackupConfig.saveBackupIgnoreConfig()
        io.legado.app.help.storage.BackupConfig.saveBackupDbIgnoreConfig()
        _uiState.update { it.copy(activeSheet = null) }
    }

    private fun toggleDbIgnoreItem(key: String, value: Boolean) {
        _uiState.update { state ->
            state.copy(
                dbIgnoreItems = state.dbIgnoreItems.map { item ->
                    if (item.key == key) item.copy(checked = value) else item
                }.toImmutableList()
            )
        }
    }

    private fun saveDbIgnoreItems() {
        _uiState.value.dbIgnoreItems.forEach { item ->
            io.legado.app.help.storage.BackupConfig.dbIgnoreConfig[item.key] = item.checked
        }
        io.legado.app.help.storage.BackupConfig.saveDbIgnoreConfig()
    }

    private fun toggleBackupDbIgnoreItem(key: String, value: Boolean) {
        _uiState.update { state ->
            state.copy(
                backupDbIgnoreItems = state.backupDbIgnoreItems.map { item ->
                    if (item.key == key) item.copy(checked = value) else item
                }.toImmutableList()
            )
        }
    }

    private fun saveBackupDbIgnoreItems() {
        _uiState.value.backupDbIgnoreItems.forEach { item ->
            io.legado.app.help.storage.BackupConfig.backupDbIgnoreConfig[item.key] = item.checked
        }
        io.legado.app.help.storage.BackupConfig.saveBackupDbIgnoreConfig()
    }

    private fun launchDirectoryPicker(runBackup: Boolean) {
        _uiState.update { it.copy(activeSheet = null) }
        _effects.tryEmit(
            if (runBackup) BackupConfigEffect.LaunchBackupAndRunDirectoryPicker
            else BackupConfigEffect.LaunchBackupDirectoryPicker
        )
    }

    private fun saveBackupPath(path: String, runBackup: Boolean) {
        viewModelScope.launch {
            settingsGateway.update { it.copy(backupPath = path) }
            if (runBackup && path.isNotEmpty()) requestBackup("both", path)
        }
    }

    private fun requestBackup(mode: String, selectedPath: String? = null) {
        _uiState.update { it.copy(activeSheet = null) }
        val path = selectedPath ?: _uiState.value.settings.backupPath.orEmpty()
        if (path.isEmpty() && mode != "webdav") return
        if (path.isNotEmpty() && !path.isContentScheme()) {
            _effects.tryEmit(BackupConfigEffect.RequestStoragePermission(path, mode))
        } else {
            performBackup(path, mode)
        }
    }

    private fun performBackup(path: String, mode: String) {
        _uiState.update { it.copy(activeDialog = BackupConfigDialog.Loading(R.string.backup)) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { backupRestoreUseCase.backup(path, mode) }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(activeDialog = null) }
                        _effects.tryEmit(BackupConfigEffect.ShowMessage(R.string.backup_success))
                    }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(activeDialog = null) }
                        _effects.tryEmit(
                            BackupConfigEffect.ShowMessage(R.string.backup_fail, error.localizedMessage)
                        )
                    }
                }
        }
    }

    private fun requestLocalRestore() {
        _uiState.update { it.copy(activeSheet = null, activeDialog = null) }
        _effects.tryEmit(BackupConfigEffect.LaunchRestoreFilePicker)
    }

    private fun restoreLocal(uri: String) {
        _uiState.update { it.copy(activeDialog = BackupConfigDialog.Loading(R.string.on_restore)) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { backupRestoreUseCase.restoreLocal(Uri.parse(uri).toString()) }
                .fold(
                    onSuccess = { finishRestoreSuccess() },
                    onFailure = { finishRestoreFailure(it.localizedMessage) },
                )
        }
    }

    private fun loadNetworkBackups() {
        _uiState.update {
            it.copy(activeSheet = null, activeDialog = BackupConfigDialog.Loading(R.string.loading))
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { webDavBackupUseCase.getBackupNames() }
                .onSuccess { names ->
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                activeDialog = null,
                                activeSheet = BackupConfigSheet.RestoreFiles,
                                backupNames = names.toImmutableList(),
                            )
                        }
                    }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                activeDialog = BackupConfigDialog.ConfirmLocalRestoreFallback(
                                    error.localizedMessage
                                )
                            )
                        }
                    }
                }
        }
    }

    private fun restoreNetwork(name: String) {
        _uiState.update {
            it.copy(activeSheet = null, activeDialog = BackupConfigDialog.Loading(R.string.on_restore))
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { webDavBackupUseCase.restore(name) }
                .fold(
                    onSuccess = { finishRestoreSuccess() },
                    onFailure = { finishRestoreFailure(it.localizedMessage, webDav = true) },
                )
        }
    }

    private suspend fun finishRestoreSuccess() = withContext(Dispatchers.Main) {
        _uiState.update { it.copy(activeDialog = null) }
        _effects.tryEmit(BackupConfigEffect.ShowMessage(R.string.restore_success))
    }

    private suspend fun finishRestoreFailure(message: String?, webDav: Boolean = false) =
        withContext(Dispatchers.Main) {
            _uiState.update { it.copy(activeDialog = null) }
            _effects.tryEmit(
                BackupConfigEffect.ShowMessage(
                    if (webDav) R.string.webdav_restore_fail else R.string.restore_fail_with_error,
                    message,
                )
            )
        }

    private companion object {
        fun loadIgnoreItems() = io.legado.app.help.storage.BackupConfig.ignoreKeys
            .mapIndexed { index, key ->
                BackupIgnoreItem(
                    key = key,
                    title = io.legado.app.help.storage.BackupConfig.ignoreTitle[index],
                    checked = io.legado.app.help.storage.BackupConfig.ignoreConfig[key] ?: false,
                )
            }
            .toImmutableList()

        fun loadBackupIgnoreItems() = io.legado.app.help.storage.BackupConfig.backupIgnoreKeys
            .mapIndexed { index, key ->
                BackupIgnoreItem(
                    key = key,
                    title = io.legado.app.help.storage.BackupConfig.backupIgnoreTitle[index],
                    checked = io.legado.app.help.storage.BackupConfig.backupIgnoreConfig[key]
                        ?: false,
                )
            }
            .toImmutableList()

        fun loadDbIgnoreItems() = io.legado.app.help.storage.BackupConfig.dbIgnoreKeys
            .mapIndexed { index, key ->
                BackupIgnoreItem(
                    key = key,
                    title = io.legado.app.help.storage.BackupConfig.dbIgnoreTitle[index],
                    checked = io.legado.app.help.storage.BackupConfig.dbIgnoreConfig[key] ?: false,
                )
            }
            .toImmutableList()

        fun loadBackupDbIgnoreItems() = io.legado.app.help.storage.BackupConfig.backupDbIgnoreKeys
            .mapIndexed { index, key ->
                BackupIgnoreItem(
                    key = key,
                    title = io.legado.app.help.storage.BackupConfig.backupDbIgnoreTitle[index],
                    checked = io.legado.app.help.storage.BackupConfig.backupDbIgnoreConfig[key]
                        ?: false,
                )
            }
            .toImmutableList()
    }
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
