package io.legado.app.feature.settings.search

import android.app.Application
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import io.legado.app.R
import io.legado.app.domain.model.settings.AppShellSettings
import io.legado.app.domain.model.settings.AppUiConfiguration
import io.legado.app.feature.settings.reading.ReadSetting
import io.legado.app.ui.config.readConfig.ReadConfigScreen
import io.legado.app.ui.config.readConfig.ReadConfigUiState
import io.legado.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import splitties.init.injectAsAppCtx
import java.util.Locale

@Suppress("DEPRECATION") // StateRestorationTester uses the JUnit4 ComposeContentTestRule.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsSearchScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun exercise(engine: String, browseAll: Boolean = false) {
        val app = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
        val viewModel = SettingsSearchViewModel(SavedStateHandle())
        val restore = StateRestorationTester(compose)
        restore.setContent {
            CompositionLocalProvider(LocalInspectionMode provides false) {
                AppTheme(AppUiConfiguration(appShell = AppShellSettings(composeEngine = engine))) {
                    var target by rememberSaveable { mutableStateOf<String?>(null) }
                    val holder = rememberSaveableStateHolder()
                    holder.SaveableStateProvider(if (target == null) "search" else "target") {
                        if (target == null) {
                            SettingsSearchRouteScreen({}, { target = it }, viewModel)
                        } else {
                            ReadConfigScreen(ReadConfigUiState(), {}, { target = null }, target)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText(app.getString(R.string.settings_search_prompt)).assertIsDisplayed()
        val setting = if (browseAll) ReadSetting.MenuIcons else ReadSetting.VolumeKeys
        val title = app.getString(setting.titleRes)
        val query = if (browseAll) app.getString(R.string.read_config) else title
        compose.onNode(hasSetTextAction()).performTextInput(query)
        val result = hasText(title) and hasClickAction() and
            hasAnyAncestor(hasTestTag("settings-search-result:${setting.id}"))
        if (browseAll) compose.onNode(hasScrollToIndexAction()).performScrollToNode(result)
        compose.onNode(result).performClick()
        compose.onNodeWithTag("setting:${setting.id}").assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
        compose.onNode(hasSetTextAction()).assertTextContains(query)
        compose.onNode(result).assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains(query)
        compose.onNode(result).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.settings_search_clear)).performClick()
        assertEquals("", viewModel.uiState.value.query)
        compose.onNodeWithText(app.getString(R.string.settings_search_prompt)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("NoSuchSetting123")
        compose.onNodeWithText(app.getString(R.string.settings_search_empty)).assertIsDisplayed()
    }

    @Test fun `material search navigates to a control returns restores and clears`() = exercise("material")
    @Test fun `miuix search retains query and exposes named clear and back actions`() = exercise("miuix")
    @Test fun `bottom result stays visible after returning and restoring search`() = exercise("material", browseAll = true)
    @Test @Config(qualifiers = "zh-rCN-w1000dp-h700dp")
    fun `Chinese catalog works in expanded windows`() = exercise("material")

    @Test
    fun `language change rebuilds cached index with the same resources and keeps query`() {
        val app = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
        val resources = app.resources
        val original = Configuration(resources.configuration)
        var configuration by mutableStateOf(Configuration(original))
        val viewModel = SettingsSearchViewModel(SavedStateHandle())
        val reading = ReadSetting.BrightnessPosition
        val theme = requireNotNull(SettingsSearchCatalog.find("theme.miuix_monet"))

        fun entry(id: String) = viewModel.uiState.value.results.single { it.id == id }
        fun assertLocalizedEntries() {
            val brightness = entry(reading.id)
            assertEquals(resources.getString(reading.titleRes), brightness.title)
            assertEquals(resources.getString(R.string.settings_search_path,
                resources.getString(R.string.read_config), resources.getString(reading.section.titleRes)),
                brightness.path)
            assertEquals(resources.getString(reading.descriptionRes!!,
                resources.getString(R.string.brightness_bar_vertical)), brightness.description)
            val fixed = entry(theme.id)
            assertEquals(resources.getString(theme.titleRes), fixed.title)
            assertEquals(resources.getString(R.string.settings_search_path,
                resources.getString(theme.page.titleRes), resources.getString(theme.groupTitleRes!!)), fixed.path)
            assertEquals(listOf(
                resources.getString(theme.descriptionRes!!),
                resources.getString(R.string.settings_search_condition,
                    resources.getString(SettingsSearchCatalog.find(theme.parentId!!)!!.titleRes)),
            ).joinToString("\n"), fixed.description)
            assertEquals(resources.getString(R.string.settings_search_theme_keywords), fixed.keywords)
        }

        try {
            compose.setContent {
                // Android may reuse Context and Resources while updating their configuration.
                CompositionLocalProvider(LocalContext provides app, LocalConfiguration provides configuration) {
                    AppTheme(AppUiConfiguration()) { SettingsSearchRouteScreen({}, {}, viewModel) }
                }
            }
            compose.onNode(hasSetTextAction()).performTextInput("›")
            lateinit var englishTitle: String
            compose.runOnIdle {
                assertLocalizedEntries()
                englishTitle = entry(reading.id).title
                configuration = Configuration(original).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
                resources.updateConfiguration(configuration, resources.displayMetrics)
            }
            compose.runOnIdle {
                assertEquals("›", viewModel.uiState.value.query)
                assertLocalizedEntries()
                assertNotEquals(englishTitle, entry(reading.id).title)
            }
        } finally {
            resources.updateConfiguration(original, resources.displayMetrics)
        }
    }
}
