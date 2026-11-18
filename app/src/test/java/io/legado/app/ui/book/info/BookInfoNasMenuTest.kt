package io.legado.app.ui.book.info

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.domain.model.NasComicUploadState
import io.legado.app.domain.model.settings.AppUiConfiguration
import io.legado.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class BookInfoNasMenuTest {
    @get:Rule val compose = createComposeRule()
    private val book = BookInfoBookUi(
        bookUrl = "/local/book.txt", name = "Book", author = "Author", realAuthor = "Author",
        origin = "local", originName = "book.txt", coverPath = null, group = 0, isLocal = true,
        type = 0, canUpdate = false, splitLongChapter = false, durChapterTitle = null,
        latestChapterTitle = null, totalChapterNum = 0, durChapterIndex = 0, durChapterPos = 0,
        remark = null, intro = null, disableReadRecord = false,
    )
    private val actions = mutableListOf<BookInfoMenuAction>()
    @Before fun init() { RuntimeEnvironment.getApplication().injectAsAppCtx() }
    private fun show(state: BookInfoUiState) {
        compose.setContent {
            AppTheme(AppUiConfiguration()) {
                BookInfoOverflowAction(state, actions::add)
            }
        }
        compose.onNodeWithContentDescription(
            RuntimeEnvironment.getApplication().getString(R.string.more_menu),
        ).performClick()
    }
    @Test fun enabledLocalBookShowsNasAndDispatchesSeparateAction() {
        show(BookInfoUiState(book = book, nasUploadVisible = true))
        compose.onNodeWithText("Upload to NAS").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(BookInfoMenuAction.UploadNas), actions) }
    }
    @Test fun disabledNasDoesNotShowEntry() {
        show(BookInfoUiState(book = book, nasUploadVisible = false))
        compose.onNodeWithText("Upload to NAS").assertDoesNotExist()
    }
    @Test fun onlineBookDoesNotPretendToHaveUploadableFile() {
        show(BookInfoUiState(book = book.copy(isLocal = false), nasUploadVisible = true))
        compose.onNodeWithText("Upload to NAS").assertDoesNotExist()
        compose.onNodeWithText("Upload comic to NAS").assertDoesNotExist()
    }
    @Test fun onlineComicKeepsFullUploadEntryAfterUpstreamMerge() {
        show(BookInfoUiState(
            book = book.copy(isLocal = false, type = BookType.image),
            nasUploadVisible = true,
        ))
        compose.onNodeWithText("Upload comic to NAS").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(BookInfoMenuAction.UploadNasComic), actions) }
    }
    @Test fun localComicUsesLocalFileUploadRatherThanSourcePackaging() {
        show(BookInfoUiState(
            book = book.copy(type = BookType.image or BookType.archive),
            nasUploadVisible = true,
        ))
        compose.onNodeWithText("Upload comic to NAS").assertDoesNotExist()
        compose.onNodeWithText("Upload to NAS").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(BookInfoMenuAction.UploadNas), actions) }
    }
    @Test fun runningComicUploadCannotStartAnotherTask() {
        show(BookInfoUiState(
            book = book.copy(isLocal = false, type = BookType.image),
            nasUploadVisible = true,
            nasComicUpload = NasComicUploadState(running = true),
        ))
        compose.onNodeWithText("Upload comic to NAS").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(emptyList<BookInfoMenuAction>(), actions) }
    }
    @Test fun readOnlyComicEntryIsDisabled() {
        show(BookInfoUiState(
            book = book.copy(isLocal = false, type = BookType.image),
            nasUploadVisible = true,
            nasUploadDenied = true,
        ))
        compose.onNodeWithText("Upload comic to NAS").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(emptyList<BookInfoMenuAction>(), actions) }
    }
    @Test fun readOnlyEntryIsDisabled() {
        show(BookInfoUiState(book = book, nasUploadVisible = true, nasUploadDenied = true))
        compose.onNodeWithText("Upload to NAS (read-only token)").assertIsNotEnabled()
    }
}
