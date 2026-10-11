package io.legado.app.ui.book.manga

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coil3.ImageLoader
import io.legado.app.data.dao.BookSourceDao
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.manga.MangaReaderActionRepository
import io.legado.app.domain.gateway.BookCacheDownloadGateway
import io.legado.app.domain.gateway.MangaReaderSession
import io.legado.app.domain.gateway.MangaReaderSessionFactory
import io.legado.app.domain.gateway.MangaSettingsGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.model.manga.MangaBookState
import io.legado.app.domain.model.manga.MangaChapterContent
import io.legado.app.domain.model.manga.MangaChapterState
import io.legado.app.domain.model.manga.MangaPageContent
import io.legado.app.domain.model.manga.MangaSessionCommand
import io.legado.app.domain.model.manga.MangaSessionEvent
import io.legado.app.domain.model.manga.MangaSessionId
import io.legado.app.domain.model.manga.MangaSessionState
import io.legado.app.domain.model.settings.MangaSettings
import io.legado.app.domain.model.settings.OtherSettings
import io.legado.app.domain.model.settings.ReadSettings
import io.legado.app.domain.usecase.CacheBookChaptersUseCase
import io.legado.app.model.cache.CacheDownloadRequest
import io.legado.app.support.InMemoryAppDatabaseFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MangaReaderNavigationResultTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var fixture: InMemoryAppDatabaseFixture
    private lateinit var imageLoader: ImageLoader
    private lateinit var session: FakeSession
    private lateinit var viewModel: MangaReaderViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val application = RuntimeEnvironment.getApplication()
        application.injectAsAppCtx()
        fixture = InMemoryAppDatabaseFixture(application)
        imageLoader = ImageLoader.Builder(application).build()
        session = FakeSession()
        viewModel = MangaReaderViewModel(
            mangaSettingsGateway = FakeMangaSettings(),
            otherSettingsGateway = FakeOtherSettings(),
            readSettingsGateway = FakeReadSettings(),
            actionRepository = MangaReaderActionRepository(
                application, fixture.database, imageLoader, FakeOtherSettings(), FakeReadSettings(),
            ),
            cacheBookChaptersUseCase = CacheBookChaptersUseCase(UnusedDownloads()),
            sessionFactory = MangaReaderSessionFactory { session },
        )
        store.put("manga", viewModel)
    }

    @After
    fun tearDown() {
        store.clear()
        imageLoader.shutdown()
        fixture.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `restored toc selection waits for the book and survives repeated route initialization`() =
        runTest(dispatcher) {
            // Nav3 restores to a TOC child before its reader VM has opened the book.
            val result = async { viewModel.onTocResult("comic", 2, 7) }
            runCurrent()
            assertFalse(result.isCompleted)
            assertTrue(session.chapterCommands().isEmpty())

            val initialize = MangaReaderIntent.Initialize("comic", true, false)
            viewModel.onIntent(initialize)
            viewModel.onIntent(initialize)
            runCurrent()
            assertEquals(1, session.commands.filterIsInstance<MangaSessionCommand.Open>().size)
            assertFalse(result.isCompleted)

            // Only identity and the chapter count are available; no image has loaded.
            session.openBook("comic")
            runCurrent()

            assertTrue(result.isCompleted)
            assertEquals(listOf(MangaSessionCommand.OpenChapter(2, 7)), session.chapterCommands())
            assertEquals(2, viewModel.uiState.value.pendingChapterIndex)
        }

    @Test
    fun `new explicit open ids reopen the retained book while identical requests remain deduplicated`() =
        runTest(dispatcher) {
            val first = MangaReaderIntent.Initialize("comic", true, true, openRequestId = 11L)
            viewModel.onIntent(first)
            viewModel.onIntent(first)
            runCurrent()
            assertEquals(
                listOf(MangaSessionCommand.Open("comic", true, true)),
                session.commands.filterIsInstance<MangaSessionCommand.Open>(),
            )

            // Details selected another chapter, then replaced the route of this same book.
            // Its new id is the only changed field; it must still open the saved target.
            val second = first.copy(openRequestId = 22L)
            viewModel.onIntent(second)
            viewModel.onIntent(second)
            runCurrent()
            assertEquals(
                List(2) { MangaSessionCommand.Open("comic", true, true) },
                session.commands.filterIsInstance<MangaSessionCommand.Open>(),
            )
        }

    @Test
    fun `cancelled source lookup cannot restore the previous book while a new open waits`() =
        runTest(dispatcher) {
            viewModel.onIntent(MangaReaderIntent.Initialize("old-comic", true, false, 1L))
            runCurrent()
            onNextSourceLookup {
                // The current lookup is still on the stack when a new route requests another
                // book. Its cancelled job must not commit even before Open updates the session.
                viewModel.onIntent(MangaReaderIntent.Initialize("new-comic", true, false, 2L))
            }
            session.openReadyBook("old-comic")
            runCurrent()

            assertTrue(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.pages.isEmpty())
            assertTrue(session.commands.filterIsInstance<MangaSessionCommand.VisiblePageChanged>().isEmpty())

            session.openReadyBook("new-comic", chapterIndex = 1)
            runCurrent()
            assertEquals("new-comic", viewModel.uiState.value.bookUrl)
            assertEquals(1, viewModel.uiState.value.chapterIndex)
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.pages.isNotEmpty())
        }

    @Test
    fun `source lookup cannot publish a ready chapter from a replaced session revision`() =
        runTest(dispatcher) {
            onNextSourceLookup {
                // A session's ordered Open has advanced while the old ready presentation
                // lookup was in progress. It has no new chapter to cancel that old job yet.
                session.beginOpen("new-comic")
            }
            session.openReadyBook("old-comic")
            runCurrent()

            assertTrue(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.pages.isEmpty())
            assertTrue(session.commands.filterIsInstance<MangaSessionCommand.VisiblePageChanged>().isEmpty())

            session.openReadyBook("new-comic", chapterIndex = 3)
            runCurrent()
            assertEquals("new-comic", viewModel.uiState.value.bookUrl)
            assertEquals(3, viewModel.uiState.value.chapterIndex)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `same book reopened during source lookup presents only the latest chapter`() =
        runTest(dispatcher) {
            onNextSourceLookup { session.openReadyBook("comic", chapterIndex = 2) }
            session.openReadyBook("comic", chapterIndex = 0)
            runCurrent()

            assertEquals("comic", viewModel.uiState.value.bookUrl)
            assertEquals(2, viewModel.uiState.value.chapterIndex)
            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(
                listOf(MangaSessionCommand.VisiblePageChanged(2, 0)),
                session.commands.filterIsInstance<MangaSessionCommand.VisiblePageChanged>(),
            )
        }

    @Test
    fun `new book initialization clears unfinished chapter and pager handoff gates`() =
        runTest(dispatcher) {
            viewModel.onIntent(MangaReaderIntent.Initialize("old-comic", true, false, 1L))
            session.openReadyBook("old-comic")
            runCurrent()
            viewModel.onIntent(MangaReaderIntent.OpenChapter(2, 0))
            viewModel.onIntent(MangaReaderIntent.PagerScrollChanged(true))
            runCurrent()
            assertEquals(2, viewModel.uiState.value.pendingChapterIndex)

            viewModel.onIntent(MangaReaderIntent.Initialize("new-comic", true, false, 2L))
            session.openReadyBook("new-comic", chapterIndex = 1)
            runCurrent()

            assertEquals("new-comic", viewModel.uiState.value.bookUrl)
            assertEquals(1, viewModel.uiState.value.chapterIndex)
            assertFalse(viewModel.uiState.value.isLoading)
            assertFalse(viewModel.uiState.value.isChapterLoading)
            assertTrue(viewModel.uiState.value.pages.isNotEmpty())
        }

    @Test
    fun `retained reader accepts a toc result without waiting for chapter images`() =
        runTest(dispatcher) {
            session.openBook("comic")
            runCurrent()

            val result = async { viewModel.onTocResult("comic", 3, 4) }
            runCurrent()

            assertTrue(result.isCompleted)
            assertEquals(listOf(MangaSessionCommand.OpenChapter(3, 4)), session.chapterCommands())
        }

    @Test
    fun `stopping the route cancels waiting without consuming the later redelivery`() =
        runTest(dispatcher) {
            val firstDelivery = async { viewModel.onTocResult("comic", 1, 2) }
            runCurrent()
            firstDelivery.cancel()
            runCurrent()
            session.openBook("comic")
            runCurrent()
            assertTrue(session.chapterCommands().isEmpty())

            val redelivery = async { viewModel.onTocResult("comic", 1, 2) }
            runCurrent()
            assertTrue(redelivery.isCompleted)
            assertEquals(listOf(MangaSessionCommand.OpenChapter(1, 2)), session.chapterCommands())
        }

    @Test
    fun `a different book cannot make a pending toc result ready`() = runTest(dispatcher) {
        val result = async { viewModel.onTocResult("comic", 2, 0) }
        runCurrent()
        session.openBook("other-comic")
        runCurrent()
        assertFalse(result.isCompleted)
        assertTrue(session.chapterCommands().isEmpty())

        session.openBook("comic")
        runCurrent()
        assertTrue(result.isCompleted)
        assertEquals(listOf(MangaSessionCommand.OpenChapter(2, 0)), session.chapterCommands())
    }

    @Test
    fun `toc from the changed source uses its actual book instead of the initial reader route`() =
        runTest(dispatcher) {
            viewModel.onIntent(MangaReaderIntent.Initialize("old-source-comic", true, false))
            runCurrent()
            session.openBook("old-source-comic")
            runCurrent()
            // ChangeSourceBook reopens the same retained session; its Nav3 route remains unchanged.
            session.openBook("new-source-comic")
            runCurrent()

            val result = async { viewModel.onTocResult("new-source-comic", 3, 6) }
            runCurrent()

            assertTrue(result.isCompleted)
            assertEquals(listOf(MangaSessionCommand.OpenChapter(3, 6)), session.chapterCommands())
        }

    @Test
    fun `restored toc accepts the current open request when source parsing rewrites its url`() =
        runTest(dispatcher) {
            val result = async { viewModel.onTocResult("original-comic", 2, 5) }
            runCurrent()
            // The alias is already known while opening; chapter selection must still wait.
            session.state.value = session.state.value.copy(requestedBookUrl = "original-comic")
            runCurrent()
            assertFalse(result.isCompleted)
            assertTrue(session.chapterCommands().isEmpty())

            session.openBook("canonical-comic", requestedBookUrl = "original-comic")
            runCurrent()

            assertTrue(result.isCompleted)
            assertEquals(listOf(MangaSessionCommand.OpenChapter(2, 5)), session.chapterCommands())
        }

    @Test
    fun `source switch replaces the request alias and cannot consume an old source toc`() =
        runTest(dispatcher) {
            session.openBook("new-canonical-comic", requestedBookUrl = "new-source-comic")
            runCurrent()
            val stale = async { viewModel.onTocResult("old-source-comic", 2, 0) }
            runCurrent()
            assertFalse(stale.isCompleted)
            assertTrue(session.chapterCommands().isEmpty())
            stale.cancel()
            runCurrent()

            val current = async { viewModel.onTocResult("new-source-comic", 1, 4) }
            runCurrent()
            assertTrue(current.isCompleted)
            assertEquals(listOf(MangaSessionCommand.OpenChapter(1, 4)), session.chapterCommands())
        }

    /** Intercept the real synchronous DAO lookup without a production test hook or mock library. */
    private fun onNextSourceLookup(callback: () -> Unit) {
        val original = fixture.database.bookSourceDao
        var called = false
        val intercepted = object : BookSourceDao by original {
            override fun getBookSource(key: String): BookSource? {
                if (!called) {
                    called = true
                    callback()
                }
                return original.getBookSource(key)
            }
        }
        fixture.database.javaClass.declaredFields
            .single { it.type == BookSourceDao::class.java }
            .apply { isAccessible = true }
            .set(fixture.database, intercepted)
    }

    private class FakeSession : MangaReaderSession {
        override val state = MutableStateFlow(MangaSessionState(MangaSessionId("test")))
        override val events = MutableSharedFlow<MangaSessionEvent>()
        val commands = mutableListOf<MangaSessionCommand>()

        override suspend fun execute(command: MangaSessionCommand) {
            commands += command
        }

        fun openBook(bookUrl: String, requestedBookUrl: String = bookUrl) {
            state.value = state.value.copy(
                revision = state.value.revision + 1,
                book = MangaBookState(
                    bookUrl = bookUrl,
                    name = "Comic",
                    author = "Author",
                    coverUrl = null,
                    customCoverUrl = null,
                    sourceOrigin = "source",
                    sourceName = "Source",
                    sourceType = null,
                    inBookshelf = true,
                    scrollMode = null,
                    sidePaddingDp = null,
                    isLocal = false,
                    chapterTitles = List(5) { "Chapter ${it + 1}" },
                ),
                chapterCount = 5,
                currentChapter = MangaChapterState.Empty,
                requestedBookUrl = requestedBookUrl,
            )
        }

        fun beginOpen(bookUrl: String) {
            state.value = MangaSessionState(
                sessionId = state.value.sessionId,
                revision = state.value.revision + 1,
                requestedBookUrl = bookUrl,
            )
        }

        fun openReadyBook(bookUrl: String, chapterIndex: Int = 0) {
            openBook(bookUrl)
            val opened = state.value
            state.value = opened.copy(
                chapterIndex = chapterIndex,
                currentChapter = MangaChapterState.Ready(
                    token = requireNotNull(opened.tokenFor(chapterIndex)),
                    chapter = MangaChapterContent(
                        chapterIndex = chapterIndex,
                        chapterTitle = "Chapter ${chapterIndex + 1}",
                        chapterUrl = null,
                        pages = List(2) { page ->
                            MangaPageContent("$bookUrl/$chapterIndex/$page.jpg", page, 2)
                        },
                        isVolume = false,
                    ),
                ),
            )
        }

        fun chapterCommands() = commands.filterIsInstance<MangaSessionCommand.OpenChapter>()
        override fun close() = Unit
    }

    private class FakeMangaSettings : MangaSettingsGateway {
        override val settings = MutableStateFlow(MangaSettings())
        override val currentSettings get() = settings.value
        override suspend fun update(transform: (MangaSettings) -> MangaSettings) {
            settings.value = transform(settings.value)
        }
    }

    private class FakeOtherSettings : OtherSettingsGateway {
        override val settings = MutableStateFlow(OtherSettings())
        override val currentSettings get() = settings.value
        override suspend fun update(transform: (OtherSettings) -> OtherSettings) {
            settings.value = transform(settings.value)
        }
    }

    private class FakeReadSettings : ReadSettingsGateway {
        override val settings = MutableStateFlow(ReadSettings())
        override val currentSettings get() = settings.value
        override suspend fun update(transform: (ReadSettings) -> ReadSettings) {
            settings.value = transform(settings.value)
        }
    }

    private class UnusedDownloads : BookCacheDownloadGateway {
        override suspend fun start(request: CacheDownloadRequest) = error("Unexpected download")
        override suspend fun start(requests: List<CacheDownloadRequest>) = error("Unexpected download")
        override suspend fun start(bookUrl: String, chapterIndices: List<Int>) = error("Unexpected download")
        override suspend fun start(bookUrl: String, startIndex: Int, endIndex: Int) = error("Unexpected download")
    }
}
