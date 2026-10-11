package io.legado.app.ui.book.manga

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coil3.ImageLoader
import io.legado.app.data.repository.manga.MangaReaderActionRepository
import io.legado.app.domain.gateway.BookCacheDownloadGateway
import io.legado.app.domain.gateway.MangaReaderSession
import io.legado.app.domain.gateway.MangaReaderSessionFactory
import io.legado.app.domain.gateway.MangaSettingsGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.model.manga.MangaBookState
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

    private class FakeSession : MangaReaderSession {
        override val state = MutableStateFlow(MangaSessionState(MangaSessionId("test")))
        override val events = MutableSharedFlow<MangaSessionEvent>()
        val commands = mutableListOf<MangaSessionCommand>()

        override suspend fun execute(command: MangaSessionCommand) {
            commands += command
        }

        fun openBook(bookUrl: String, requestedBookUrl: String = bookUrl) {
            state.value = state.value.copy(
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
                requestedBookUrl = requestedBookUrl,
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
