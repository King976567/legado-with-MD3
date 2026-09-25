package io.legado.app.domain.model.settings

/** Settings for the optional NAS library service. The token is kept local and is never exported. */
data class NasSettings(
    val apiUrl: String = "",
    val apiToken: String = "",
    val showHomeCard: Boolean = false,
    /** Local trust marker; never exported in a backup. */
    val connectionVerified: Boolean = false,
    val lastConnectionError: String? = null,
)

/** Stable preference names shared by the NAS repository and backup filter. */
object NasSettingsKeys {
    const val API_URL = "nasApiUrl"
    const val API_TOKEN = "nasApiToken"
    const val SHOW_HOME_CARD = "showNasExploreCard"
    const val CONNECTION_VERIFIED = "nasConnectionVerified"
    const val LAST_CONNECTION_ERROR = "nasLastConnectionError"
}
