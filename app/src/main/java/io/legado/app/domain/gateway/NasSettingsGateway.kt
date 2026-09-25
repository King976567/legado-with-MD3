package io.legado.app.domain.gateway

import io.legado.app.domain.model.settings.NasSettings
import kotlinx.coroutines.flow.Flow

interface NasSettingsGateway {
    val currentSettings: NasSettings
    val settings: Flow<NasSettings>
    suspend fun update(transform: (NasSettings) -> NasSettings)
}
