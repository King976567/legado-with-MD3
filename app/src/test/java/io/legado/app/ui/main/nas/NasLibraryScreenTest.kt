package io.legado.app.ui.main.nas

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import io.legado.app.domain.model.NasBook
import io.legado.app.domain.model.NasCapabilities
import io.legado.app.domain.model.NasDirectory
import io.legado.app.domain.model.NasTask
import io.legado.app.ui.theme.LocalLegadoThemeColors
import io.legado.app.ui.theme.MaterialThemeWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "en-rUS-w411dp-h891dp")
class NasLibraryScreenTest {
    @get:Rule val compose = createComposeRule()
    private val intents = mutableListOf<NasLibraryIntent>()
    private val state = NasLibraryUiState(
        isConfigured = true,
        books = (1..30).map { NasBook(id = "$it", title = "Book $it") },
        total = 60,
        pageSize = 30,
    )

    @Before fun initializeAppContext() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
    }

    private fun show(value: NasLibraryUiState = state, settings: () -> Unit = {}) {
        compose.setContent {
            MaterialThemeWrapper(LocalLegadoThemeColors.current, null) {
                NasLibraryScreen(value, intents::add, onOpenSettings = settings)
            }
        }
    }

    @Test fun searchScrollsWithBooksAndBottomRequestsNextPage() {
        show()
        compose.onNodeWithText("搜索 NAS 书库").assertIsDisplayed()
        compose.runOnIdle { assertTrue(intents.isEmpty()) }
        compose.onNodeWithTag("nas-library-list").performScrollToIndex(32)
        compose.onNodeWithText("搜索 NAS 书库").assertDoesNotExist()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(NasLibraryIntent.NextPage), intents) }
    }

    @Test fun failedAppendRequiresExplicitRetry() {
        show(state.copy(loadMoreError = "Offline"))
        compose.onNodeWithTag("nas-library-list").performScrollToIndex(33)
        compose.runOnIdle { assertTrue(intents.isEmpty()) }
        compose.onNodeWithText("Retry loading more").performClick()
        compose.runOnIdle { assertEquals(listOf(NasLibraryIntent.RetryLoadMore), intents) }
    }

    @Test fun completedTaskHistoryOnlyAppearsWhenToolbarEntryIsOpened() {
        show(state.copy(tasks = listOf(NasTask(id = "1", kind = "indexer", status = "completed"))))
        compose.onNodeWithTag("nas-task-list").assertDoesNotExist()
        compose.onNodeWithContentDescription("Tasks").performClick()
        compose.onNodeWithTag("nas-task-list").assertIsDisplayed()
        compose.onNodeWithText("Indexing · Completed").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(NasLibraryIntent.LoadTasks), intents) }
    }

    @Test fun readOnlyManagementDisablesWritesButKeepsSettings() {
        var settingsOpened = false
        show(state.copy(
            writeAccessDenied = true,
            capabilities = NasCapabilities(features = setOf("upload", "indexer", "scraper")),
        )) { settingsOpened = true }
        compose.onNodeWithContentDescription("Manage NAS").performClick()
        compose.onNodeWithText("Upload book").assertIsNotEnabled()
        compose.onNodeWithText("Refresh index").assertIsNotEnabled()
        compose.onNodeWithText("NAS settings").performClick()
        compose.runOnIdle { assertTrue(settingsOpened); assertTrue(intents.isEmpty()) }
    }

    private val book = NasBook(id = "remote", title = "Remote book", author = "Writer",
        fileName = "remote.epub", relativePath = "Fiction/remote.epub", size = 2048,
        intro = (1..100).joinToString("\n") { "Paragraph $it of the introduction." })
    private val detail get() = state.copy(selectedBook = book, total = 30,
        capabilities = NasCapabilities(features = setOf("download", "books", "libraryDirectories", "scraper")),
        directories = listOf(NasDirectory(directoryPath = "Fiction")))

    @Test fun detailIsFullPageAndLongIntroScrollsWithoutHidingReadAction() {
        show(detail)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("nas-library-list").assertDoesNotExist()
        compose.onNodeWithTag("nas-book-detail").assertIsDisplayed()
        compose.onNodeWithText("Remote book").assertIsDisplayed()
        compose.onNodeWithTag("nas-book-detail-list").performScrollToIndex(2)
        compose.onNodeWithText("Fiction/remote.epub").assertIsDisplayed()
        compose.onNodeWithTag("nas-book-read").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(NasLibraryIntent.DownloadBook(book)), intents) }
    }

    @Test @Config(qualifiers = "en-rUS-w1000dp-h700dp")
    fun expandedWindowKeepsDetailsAndReadActionUsable() {
        show(detail)
        compose.onNodeWithText("Remote book").assertIsDisplayed()
        compose.onNodeWithTag("nas-book-detail-list").performScrollToIndex(2)
        compose.onNodeWithText("File information").assertIsDisplayed()
        compose.onNodeWithTag("nas-book-read").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun detailReadOnlyMenuDisablesWritesButNotDownloadOrRefresh() {
        show(detail.copy(writeAccessDenied = true))
        compose.onNodeWithTag("nas-book-read").assertIsEnabled()
        compose.onNodeWithContentDescription("Book actions").performClick()
        compose.onNodeWithText("Edit metadata").assertIsNotEnabled()
        compose.onNodeWithText("Move to category").assertIsNotEnabled()
        compose.onNodeWithText("Scrape book").assertIsNotEnabled()
        compose.onNodeWithText("Refresh").performClick()
        compose.runOnIdle { assertEquals(listOf(NasLibraryIntent.OpenBook(book)), intents) }
    }

    @Test fun detailMenuKeepsExistingManagementIntents() {
        show(detail)
        listOf(
            "Edit metadata" to NasLibraryIntent.RequestEdit(book),
            "Move to category" to NasLibraryIntent.RequestMove(book),
            "Scrape book" to NasLibraryIntent.ScrapeBook(book),
        ).forEach { (label, intent) ->
            compose.onNodeWithContentDescription("Book actions").performClick()
            compose.onNodeWithText(label).performClick()
            compose.runOnIdle { assertEquals(intent, intents.last()) }
        }
    }

    @Test fun loadingDetailDisablesDownload() {
        show(detail.copy(isLoadingDetail = true))
        compose.onNodeWithTag("nas-book-loading").assertIsDisplayed()
        compose.onNodeWithTag("nas-book-read").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(intents.isEmpty()) }
    }

    @Test fun failedDetailKeepsListMetadataAndSupportsRetry() {
        show(detail.copy(detailError = "Offline"))
        compose.onNodeWithText("Remote book").assertIsDisplayed()
        compose.onNodeWithText("Offline").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(listOf(NasLibraryIntent.OpenBook(book)), intents) }
    }

    @Test fun returningFromDetailKeepsLibraryScrollPosition() {
        val current = mutableStateOf(state.copy(total = 30))
        compose.setContent {
            MaterialThemeWrapper(LocalLegadoThemeColors.current, null) {
                NasLibraryScreen(current.value, onIntent = { intent ->
                    intents += intent
                    when (intent) {
                        is NasLibraryIntent.OpenBook -> current.value = current.value.copy(selectedBook = intent.book)
                        NasLibraryIntent.DismissBook -> current.value = current.value.copy(selectedBook = null)
                        else -> Unit
                    }
                })
            }
        }
        compose.onNodeWithTag("nas-library-list").performScrollToIndex(17)
        compose.onNodeWithText("Book 15").performClick()
        compose.onNodeWithTag("nas-book-detail").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Book 15").assertIsDisplayed()
        compose.onNodeWithText("搜索 NAS 书库").assertDoesNotExist()
        compose.runOnIdle { assertTrue(intents.none { it is NasLibraryIntent.DownloadBook }) }
    }
}
