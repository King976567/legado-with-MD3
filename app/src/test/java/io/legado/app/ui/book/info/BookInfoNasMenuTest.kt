package io.legado.app.ui.book.info

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.legado.app.ui.theme.LocalLegadoThemeColors
import io.legado.app.ui.theme.MaterialThemeWrapper
import org.junit.Assert.assertEquals
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
class BookInfoNasMenuTest {
    @get:Rule val compose = createComposeRule()
    private val book = BookInfoBookUi(
        bookUrl = "/local/book.txt", name = "Book", author = "Author", realAuthor = "Author",
        origin = "local", originName = "book.txt", coverPath = null, group = 0, isLocal = true,
        type = 0, canUpdate = false, splitLongChapter = false, durChapterTitle = null,
        latestChapterTitle = null, totalChapterNum = 0, durChapterIndex = 0, durChapterPos = 0,
        remark = null, intro = null,
    )
    private val actions = mutableListOf<BookInfoMenuAction>()
    @Before fun init() { RuntimeEnvironment.getApplication().injectAsAppCtx() }
    private fun show(state: BookInfoUiState) {
        compose.setContent {
            MaterialThemeWrapper(LocalLegadoThemeColors.current, null) {
                BookInfoOverflowMenu(true, {}, state, actions::add)
            }
        }
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
    }
    @Test fun readOnlyEntryIsDisabled() {
        show(BookInfoUiState(book = book, nasUploadVisible = true, nasUploadDenied = true))
        compose.onNodeWithText("Upload to NAS (read-only token)").assertIsNotEnabled()
    }
}
