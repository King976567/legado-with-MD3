package io.legado.app.ui.book.info

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.domain.gateway.CoverAlbumGateway
import io.legado.app.domain.model.CoverAlbum
import io.legado.app.domain.model.CoverAlbumImageInput
import io.legado.app.domain.model.CoverAlbumSelection
import io.legado.app.domain.model.PrivateAccessState
import io.legado.app.domain.model.settings.AppShellSettings
import io.legado.app.domain.model.settings.AppUiConfiguration
import io.legado.app.domain.model.settings.CoverSettings
import io.legado.app.domain.model.settings.OtherSettings
import io.legado.app.domain.model.settings.TextAnimationLevel
import io.legado.app.domain.model.settings.ThemeSettings
import io.legado.app.domain.usecase.CoverAlbumUseCase
import io.legado.app.help.config.AppConfigStore
import io.legado.app.ui.theme.AppTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import splitties.init.injectAsAppCtx

/**
 * Regression coverage for the NAS shared header and upstream author/tag events.
 *
 * These tests enter the public BookInfoScreen and observe its real callbacks and
 * semantics. They do not call the private header, author splitting, label parsing,
 * or a second copy of the word-count filtering implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class BookInfoScreenNasBehaviorTest {
    @get:Rule val compose = createComposeRule()

    private val themeSettings = ThemeSettings(
        bookInfoFollowCoverColor = false,
        bookInfoNetworkCoverBackground = "off_for_default",
        bookInfoDefaultCoverBackground = "off_for_default",
    )
    private val coverSettings = CoverSettings(
        useDefaultCover = true,
        defaultCover = "",
        defaultCoverDark = "",
    )
    private val configuration = AppUiConfiguration(
        appShell = AppShellSettings(textAnimationLevel = TextAnimationLevel.Off),
        theme = themeSettings,
        cover = coverSettings,
    )

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
        AppConfigStore.init(context)
        stopKoin()
        startKoin {
            modules(module {
                // Same dependency seam as AccessibilityRegressionTest. Empty cover
                // selection keeps the test on the default canvas cover, with no I/O.
                single { CoverAlbumUseCase(EmptyCoverAlbumGateway()) }
            })
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun eachMultiAuthorLabelEmitsItsExactAuthorForShortAndLongPress() {
        val intents = mutableListOf<BookInfoIntent>()
        val expected = mutableListOf<BookInfoIntent>()
        val state = screenState(
            // Both separators and empty segments exercise what the real Screen renders.
            book = bookFixture(author = "  Ada Author , , Bert Author， Chen Author  "),
        )
        show(state = { state }, onIntent = { intents.add(it) })

        for (author in listOf("Ada Author", "Bert Author", "Chen Author")) {
            val node = compose.onNodeWithText(author)
                .assertIsDisplayed()
                .assertHasClickAction()
            node.performTouchInput { click() }
            expected.add(BookInfoIntent.AuthorClick(longClick = false, author = author))
            compose.runOnIdle { assertEquals(expected, intents) }

            node.performTouchInput { longClick() }
            expected.add(BookInfoIntent.AuthorClick(longClick = true, author = author))
            // Exact list equality also rejects an extra short-click emitted on release.
            compose.runOnIdle { assertEquals(expected, intents) }
        }
    }

    @Test
    fun singleAuthorKeepsNullFallbackForBothPressKinds() {
        val intents = mutableListOf<BookInfoIntent>()
        val state = screenState(
            book = bookFixture(author = "Original Author Record (source value)")
                .copy(realAuthor = "Solo Author"),
        )
        show(state = { state }, onIntent = { intents.add(it) })

        val label = RuntimeEnvironment.getApplication()
            .getString(R.string.author_show, "Solo Author")
        val node = compose.onNodeWithText(label)
            .assertIsDisplayed()
            .assertHasClickAction()
        node.performTouchInput { click() }
        node.performTouchInput { longClick() }

        compose.runOnIdle {
            assertEquals(
                listOf(
                    BookInfoIntent.AuthorClick(longClick = false, author = null),
                    BookInfoIntent.AuthorClick(longClick = true, author = null),
                ),
                intents,
            )
        }
    }

    @Test
    fun imageAndImageArchiveHideNumericWordCountButKeepTheirOtherTags() {
        val state = mutableStateOf(screenState(
            book = bookFixture(type = BookType.image),
            kindLabels = listOf(WORD_COUNT, OTHER_TAG),
        ))
        show(state = { state.value })

        for (type in listOf(BookType.image, BookType.image or BookType.archive)) {
            compose.runOnIdle {
                state.value = screenState(
                    book = bookFixture(type = type),
                    kindLabels = listOf(WORD_COUNT, OTHER_TAG),
                )
            }
            compose.waitForIdle()
            assertNoExposedValue(WORD_COUNT)
            compose.onNodeWithText(OTHER_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun novelAndNonImageArchivesKeepTheirWordCount() {
        val state = mutableStateOf(screenState(
            book = bookFixture(type = BookType.text),
            kindLabels = listOf(WORD_COUNT, OTHER_TAG),
        ))
        show(state = { state.value })

        // Archive is a provenance flag. It must not turn text into a comic.
        for (type in listOf(BookType.text, BookType.archive, BookType.text or BookType.archive)) {
            compose.runOnIdle {
                state.value = screenState(
                    book = bookFixture(type = type),
                    kindLabels = listOf(WORD_COUNT, OTHER_TAG),
                )
            }
            compose.waitForIdle()
            compose.onNodeWithText(WORD_COUNT).assertIsDisplayed()
            compose.onNodeWithText(OTHER_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun lockedEntryHidesAuthorWordCountAndBusinessActionsAndOnlyRequestsUnlock() {
        val intents = mutableListOf<BookInfoIntent>()
        val explores = mutableListOf<Pair<String, String>>()
        val state = lockedState()
        show(
            state = { state },
            onIntent = { intents.add(it) },
            onOpenExplore = { title, url -> explores.add(title to url) },
        )

        assertLockedContentIsRedacted()
        compose.runOnIdle {
            assertEquals(emptyList<BookInfoIntent>(), intents)
            assertEquals(emptyList<Pair<String, String>>(), explores)
        }
        compose.onNodeWithText(string(R.string.private_verify_and_open))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf(BookInfoIntent.RequestPrivateUnlock), intents)
            assertEquals(emptyList<Pair<String, String>>(), explores)
        }
    }

    @Test
    fun relockingAnExistingScreenRemovesPreviouslyRenderedAuthorAndActions() {
        val intents = mutableListOf<BookInfoIntent>()
        val state = mutableStateOf(lockedState().copy(privateLocked = false))
        show(state = { state.value }, onIntent = { intents.add(it) })
        compose.onNodeWithText("Ada Author").assertIsDisplayed()
        compose.onNodeWithText(WORD_COUNT).assertIsDisplayed()

        compose.runOnIdle { state.value = state.value.copy(privateLocked = true) }
        // Screen Crossfade is still active; wait for it to settle before inspecting
        // the final locked semantics, rather than asserting against an old node handle.
        compose.waitForIdle()
        assertLockedContentIsRedacted()
        compose.runOnIdle { assertEquals(emptyList<BookInfoIntent>(), intents) }
    }

    @Test
    fun linkedKindTagCallsHostWithItsDisplayLabelAndUrlWithoutAnIntent() {
        val intents = mutableListOf<BookInfoIntent>()
        val explores = mutableListOf<Pair<String, String>>()
        val state = screenState(kindLabels = listOf(EXPLORE_LABEL + "|/catalog?page=2"))
        show(
            state = { state },
            onIntent = { intents.add(it) },
            onOpenExplore = { title, url -> explores.add(title to url) },
        )

        compose.onNodeWithText(EXPLORE_LABEL)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf(EXPLORE_LABEL to "/catalog?page=2"), explores)
            assertEquals(emptyList<BookInfoIntent>(), intents)
        }
        assertNoExposedValue(EXPLORE_LABEL + "|/catalog?page=2")
    }

    private fun show(
        state: () -> BookInfoUiState,
        onIntent: (BookInfoIntent) -> Unit = {},
        onOpenExplore: (String, String) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(configuration, applyBackground = false) {
                    BookInfoScreen(
                        state = state(),
                        groups = persistentListOf(),
                        onIntent = onIntent,
                        onBack = {},
                        onOpenExplore = onOpenExplore,
                    )
                }
            }
        }
    }

    private fun screenState(
        book: BookInfoBookUi = bookFixture(),
        kindLabels: List<String> = emptyList(),
    ) = BookInfoUiState(
        book = book,
        kindLabels = kindLabels,
    ).withSettings(
        theme = themeSettings,
        cover = coverSettings,
        other = OtherSettings(),
    )

    private fun lockedState() = screenState(
        // Text type proves that privacy hides a tag that the unlocked header keeps.
        book = bookFixture(author = "Ada Author, Bert Author", type = BookType.text),
        kindLabels = listOf(WORD_COUNT, EXPLORE_LABEL + "|/catalog"),
    ).copy(
        inBookshelf = true,
        bookPrivate = true,
        privateLockedByEntry = true,
        privateLocked = true,
        privateAccess = PrivateAccessState(hasPassword = true, isEnabled = true),
    )

    private fun assertLockedContentIsRedacted() {
        for (sensitive in listOf(
            BOOK_TITLE, "Ada Author", "Bert Author", SOURCE_NAME, WORD_COUNT, EXPLORE_LABEL,
        )) {
            assertNoExposedValue(sensitive)
        }
        for (action in listOf(
            R.string.reading,
            R.string.already_in_bookshelf,
            R.string.add_to_bookshelf,
            R.string.view_toc,
            R.string.change_origin,
            R.string.read_record,
            R.string.edit,
            R.string.share,
            R.string.more_actions,
        )) {
            assertNoExposedValue(string(action))
        }
        compose.onNodeWithText(string(R.string.private_locked_book_title)).assertIsDisplayed()
    }

    private fun assertNoExposedValue(value: String) {
        // Check both text and content descriptions, including descendants hidden by
        // semantics merging. A visually obscured author is not sufficient redaction.
        compose.onAllNodes(
            hasText(value, substring = true) or hasContentDescription(value, substring = true),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    private fun string(resource: Int): String = RuntimeEnvironment.getApplication().getString(resource)

    private fun bookFixture(
        author: String = "Solo Author",
        type: Int = BookType.text,
    ) = BookInfoBookUi(
        bookUrl = "https://example.invalid/book/nas-screen-test",
        name = BOOK_TITLE,
        author = author,
        realAuthor = author,
        origin = "https://example.invalid",
        originName = SOURCE_NAME,
        coverPath = null,
        group = 0L,
        isLocal = false,
        type = type,
        canUpdate = true,
        disableReadRecord = false,
        splitLongChapter = false,
        durChapterTitle = null,
        latestChapterTitle = null,
        totalChapterNum = 0,
        durChapterIndex = 0,
        durChapterPos = 0,
        remark = null,
        intro = null,
    )

    private class EmptyCoverAlbumGateway : CoverAlbumGateway {
        override val albums = MutableStateFlow<List<CoverAlbum>>(emptyList())
        override val selection = MutableStateFlow(CoverAlbumSelection())

        override fun selectedImagePaths(isDark: Boolean): List<String> = emptyList()
        override suspend fun createAlbum(name: String): String = unexpectedMutation()
        override suspend fun importAlbum(
            name: String,
            lightImages: List<CoverAlbumImageInput>,
            darkImages: List<CoverAlbumImageInput>,
        ): String = unexpectedMutation()
        override suspend fun renameAlbum(albumId: String, name: String): Unit = unexpectedMutation()
        override suspend fun deleteAlbum(albumId: String): Unit = unexpectedMutation()
        override suspend fun addImages(
            albumId: String,
            isDark: Boolean,
            images: List<CoverAlbumImageInput>,
        ): Unit = unexpectedMutation()
        override suspend fun removeImage(
            albumId: String,
            isDark: Boolean,
            imageId: String,
        ): Unit = unexpectedMutation()
        override suspend fun selectAlbum(albumId: String?): Unit = unexpectedMutation()

        private fun unexpectedMutation(): Nothing = error("BookInfo header must not mutate cover albums")
    }

    private companion object {
        const val BOOK_TITLE = "NAS behaviour fixture"
        const val SOURCE_NAME = "Fixture source"
        const val WORD_COUNT = "12602.2万字"
        const val OTHER_TAG = "Full colour"
        const val EXPLORE_LABEL = "Explore catalogue"
    }
}
