package io.legado.app.ui.config.backupConfig

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import io.legado.app.domain.model.settings.BackupSettings
import io.legado.app.domain.model.settings.NasSettings
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class BackupConfigUiState(
    val settings: BackupSettings = BackupSettings(),
    val nasSettings: NasSettings = NasSettings(),
    val nasConnectionState: NasConnectionState = NasConnectionState.NotConfigured,
    val activeSheet: BackupConfigSheet? = null,
    val activeDialog: BackupConfigDialog? = null,
    val backupNames: ImmutableList<String> = persistentListOf(),
    val ignoreItems: ImmutableList<BackupIgnoreItem> = persistentListOf(),
    val backupIgnoreItems: ImmutableList<BackupIgnoreItem> = persistentListOf(),
    val dbIgnoreItems: ImmutableList<BackupIgnoreItem> = persistentListOf(),
    val backupDbIgnoreItems: ImmutableList<BackupIgnoreItem> = persistentListOf(),
)

/** Connection state shown by the NAS section in the backup settings screen. */
enum class NasConnectionState {
    NotConfigured,
    Idle,
    Testing,
    Connected,
    Failed,
}

/**
 * Derives the state shown in the NAS settings section from persisted settings.
 * A local verification marker is meaningful only while both credentials are
 * present; this prevents an old marker from making a restored, token-less
 * configuration appear connected.
 */
internal fun deriveNasConnectionState(settings: NasSettings): NasConnectionState = when {
    settings.apiUrl.isBlank() || settings.apiToken.isBlank() -> NasConnectionState.NotConfigured
    settings.lastConnectionError != null -> NasConnectionState.Failed
    settings.connectionVerified -> NasConnectionState.Connected
    else -> NasConnectionState.Idle
}

@Stable
data class BackupIgnoreItem(
    val key: String,
    val title: String,
    val checked: Boolean,
)

sealed interface BackupConfigSheet {
    data object ChooseBackupPath : BackupConfigSheet
    data object ChooseBackupAndRun : BackupConfigSheet
    data object BackupOptions : BackupConfigSheet
    data object RestoreOptions : BackupConfigSheet
    data object RestoreFiles : BackupConfigSheet
    data object IgnoreRestoreItems : BackupConfigSheet
    data object IgnoreBackupItems : BackupConfigSheet
}

sealed interface BackupConfigDialog {
    data class WebDavAuth(
        val account: String,
        val password: String,
        val passwordVisible: Boolean = false,
    ) : BackupConfigDialog

    /** Temporary editor state for the local-only NAS bearer token. */
    data class NasToken(
        val token: String,
        val tokenVisible: Boolean = false,
    ) : BackupConfigDialog

    data class ConfirmLocalRestoreFallback(val error: String?) : BackupConfigDialog
    data class Loading(@StringRes val titleRes: Int) : BackupConfigDialog
}

sealed interface BackupConfigIntent {
    data class SetWebDavUrl(val value: String) : BackupConfigIntent
    data class SetWebDavDir(val value: String) : BackupConfigIntent
    data class SetWebDavDeviceName(val value: String) : BackupConfigIntent
    data class SetSyncBookProgress(val value: Boolean) : BackupConfigIntent
    data class SetSyncBookProgressPlus(val value: Boolean) : BackupConfigIntent
    data class SetAutoCheckNewBackup(val value: Boolean) : BackupConfigIntent
    data class SetOnlyLatestBackup(val value: Boolean) : BackupConfigIntent
    data class SetBackupSyncMode(val value: String) : BackupConfigIntent
    data class OpenSheet(val sheet: BackupConfigSheet) : BackupConfigIntent
    data object DismissSheet : BackupConfigIntent
    data object OpenWebDavAuth : BackupConfigIntent
    data class EditWebDavAccount(val value: String) : BackupConfigIntent
    data class EditWebDavPassword(val value: String) : BackupConfigIntent
    data object TogglePasswordVisibility : BackupConfigIntent
    data object SaveWebDavAuth : BackupConfigIntent
    data object TestWebDav : BackupConfigIntent
    data object OpenNasToken : BackupConfigIntent
    data class EditNasToken(val value: String) : BackupConfigIntent
    data object ToggleNasTokenVisibility : BackupConfigIntent
    data object SaveNasToken : BackupConfigIntent
    data class SetNasUrl(val value: String) : BackupConfigIntent
    data class SetNasToken(val value: String) : BackupConfigIntent
    data class SetNasShowHomeCard(val value: Boolean) : BackupConfigIntent
    data object TestNasConnection : BackupConfigIntent
    data object OpenIgnoreDialog : BackupConfigIntent
    data class ToggleIgnoreItem(val key: String, val value: Boolean) : BackupConfigIntent
    data object SaveIgnoreItems : BackupConfigIntent
    data object OpenBackupIgnoreDialog : BackupConfigIntent
    data class ToggleBackupIgnoreItem(val key: String, val value: Boolean) : BackupConfigIntent
    data object SaveBackupIgnoreItems : BackupConfigIntent
    data class ToggleDbIgnoreItem(val key: String, val value: Boolean) : BackupConfigIntent
    data object SaveDbIgnoreItems : BackupConfigIntent
    data class ToggleBackupDbIgnoreItem(val key: String, val value: Boolean) : BackupConfigIntent
    data object SaveBackupDbIgnoreItems : BackupConfigIntent
    data object DismissDialog : BackupConfigIntent
    data object SelectBackupDirectory : BackupConfigIntent
    data object SelectBackupAndRunDirectory : BackupConfigIntent
    data class BackupDirectorySelected(val path: String, val runBackup: Boolean) : BackupConfigIntent
    data class RequestBackup(val mode: String) : BackupConfigIntent
    data class PerformBackup(val path: String, val mode: String) : BackupConfigIntent
    data object RequestLocalRestore : BackupConfigIntent
    data class RestoreLocal(val uri: String) : BackupConfigIntent
    data object RequestNetworkRestore : BackupConfigIntent
    data class RestoreNetwork(val name: String) : BackupConfigIntent
    data object ConfirmLocalRestoreFallback : BackupConfigIntent
    data object RequestImportOldData : BackupConfigIntent
}

sealed interface BackupConfigEffect {
    data object LaunchBackupDirectoryPicker : BackupConfigEffect
    data object LaunchBackupAndRunDirectoryPicker : BackupConfigEffect
    data object LaunchRestoreFilePicker : BackupConfigEffect
    data object LaunchImportOldDataPicker : BackupConfigEffect
    data class RequestStoragePermission(val path: String, val mode: String) : BackupConfigEffect
    data class ShowMessage(@StringRes val messageRes: Int, val argument: String? = null) : BackupConfigEffect
}
