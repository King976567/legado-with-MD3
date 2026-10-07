package io.legado.app.ui.book.readaloud

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.CoverAlbumRepository
import io.legado.app.data.repository.ReadAloudSettingsRepository
import io.legado.app.data.repository.SettingsRepository
import io.legado.app.domain.gateway.PlaybackCapsuleGateway
import io.legado.app.domain.model.PlaybackCapsuleSource
import io.legado.app.domain.model.PlaybackCapsuleState
import io.legado.app.domain.model.settings.AppUiConfiguration
import io.legado.app.domain.usecase.CoverAlbumUseCase
import io.legado.app.help.config.AppConfigStore
import io.legado.app.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ReadAloudBarCapsuleInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun expandedCoverLongPressRestoresTheDockWithoutOpeningOrControllingPlayback() {
        val application = RuntimeEnvironment.getApplication()
        AppConfigStore.init(application)
        stopKoin()
        startKoin {
            modules(module {
                single {
                    CoverAlbumUseCase(
                        CoverAlbumRepository(
                            application,
                            SettingsRepository()
                        )
                    )
                }
            })
        }
        var expanded by mutableStateOf(true)
        var opens = 0
        val settings = ReadAloudSettingsRepository()
        val playback = object : PlaybackCapsuleGateway {
            override val state = MutableStateFlow(
                PlaybackCapsuleState(
                    source = PlaybackCapsuleSource.AudioBook,
                    bookName = "Book",
                    chapterTitle = "Chapter",
                )
            )

            override fun setSessionAvailable(source: PlaybackCapsuleSource, available: Boolean) =
                error("unexpected playback action")

            override fun prepareAudioBook(bookUrl: String) = error("unexpected playback action")
            override fun clearPreparedAudioBook(bookUrl: String) =
                error("unexpected playback action")

            override fun togglePause(source: PlaybackCapsuleSource) =
                error("unexpected playback action")

            override fun stop(source: PlaybackCapsuleSource) = error("unexpected playback action")
        }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    AppTheme(AppUiConfiguration(), applyBackground = false) {
                        Box(Modifier.fillMaxSize()) {
                            ReadAloudBarCapsuleSlot(
                                enabled = true, morph = null,
                                onOpenPlayer = { opens++ }, playbackGateway = playback,
                                settingsGateway = settings,
                                controlsExpanded = expanded, expansion = if (expanded) 1f else 0f,
                                width = if (expanded) 288.dp else 64.dp, expandedWidth = 288.dp,
                                onControlsExpandedChange = { expanded = it },
                                modifier = Modifier.testTag("read-aloud-capsule"),
                            )
                        }
                    }
                }
            }
            compose.onNodeWithTag("read-aloud-capsule", useUnmergedTree = true)
                .assertIsDisplayed()
                .performTouchInput {
                    // Use Compose's gesture clock and timeout instead of combining real
                    // sleep with Robolectric uptime. Keep the touch on the 64dp cover,
                    // away from the playback controls on the right.
                    longClick(Offset(height / 2f, centerY))
                }
            compose.runOnIdle {
                assertFalse("opens=$opens", expanded)
                assertEquals(0, opens)
            }
        } finally {
            stopKoin()
        }
    }
}
