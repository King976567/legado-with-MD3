package io.legado.app.ui.config.backupConfig

import io.legado.app.domain.model.settings.NasSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class NasConnectionStateTest {

    @Test
    fun `verified settings without token are not connected`() {
        assertEquals(
            NasConnectionState.NotConfigured,
            deriveNasConnectionState(
                NasSettings(
                    apiUrl = "https://nas.example",
                    apiToken = "",
                    connectionVerified = true,
                ),
            ),
        )
    }

    @Test
    fun `verified settings with credentials are connected`() {
        assertEquals(
            NasConnectionState.Connected,
            deriveNasConnectionState(
                NasSettings(
                    apiUrl = "https://nas.example",
                    apiToken = "token",
                    connectionVerified = true,
                ),
            ),
        )
    }

    @Test
    fun `connection error takes precedence over verified marker`() {
        assertEquals(
            NasConnectionState.Failed,
            deriveNasConnectionState(
                NasSettings(
                    apiUrl = "https://nas.example",
                    apiToken = "token",
                    connectionVerified = true,
                    lastConnectionError = "offline",
                ),
            ),
        )
    }
}
