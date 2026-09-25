package io.legado.app.data.repository

import androidx.datastore.preferences.core.Preferences
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.model.settings.NasSettings
import io.legado.app.domain.model.settings.NasSettingsKeys
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.config.compatDsBoolean
import io.legado.app.help.config.compatDsString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class NasSettingsRepository : NasSettingsGateway {
    override val currentSettings: NasSettings
        get() = AppConfigStore.preferences.toNasSettings()

    override val settings: Flow<NasSettings> = AppConfigStore.preferencesFlow
        .map(Preferences::toNasSettings)
        .distinctUntilChanged()

    override suspend fun update(transform: (NasSettings) -> NasSettings) {
        AppConfigStore.atomicUpdateAndAwait(
            read = Preferences::toNasSettings,
            toPrefMap = NasSettings::toPrefMap,
            transform = transform,
        )
    }
}

private fun Preferences.toNasSettings() = NasSettings(
    apiUrl = compatDsString(NasSettingsKeys.API_URL).orEmpty(),
    apiToken = compatDsString(NasSettingsKeys.API_TOKEN).orEmpty(),
    showHomeCard = compatDsBoolean(NasSettingsKeys.SHOW_HOME_CARD) ?: false,
    connectionVerified = compatDsBoolean(NasSettingsKeys.CONNECTION_VERIFIED) ?: false,
    lastConnectionError = compatDsString(NasSettingsKeys.LAST_CONNECTION_ERROR),
)

private fun NasSettings.toPrefMap(): Map<String, Any?> = mapOf(
    NasSettingsKeys.API_URL to apiUrl.trim().trimEnd('/').ifBlank { null },
    NasSettingsKeys.API_TOKEN to apiToken.trim().ifBlank { null },
    NasSettingsKeys.SHOW_HOME_CARD to showHomeCard,
    NasSettingsKeys.CONNECTION_VERIFIED to connectionVerified,
    NasSettingsKeys.LAST_CONNECTION_ERROR to lastConnectionError,
)
