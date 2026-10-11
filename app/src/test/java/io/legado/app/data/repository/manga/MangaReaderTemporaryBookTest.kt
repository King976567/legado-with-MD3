package io.legado.app.data.repository.manga

import android.app.Application
import coil3.ImageLoader
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.model.settings.OtherSettings
import io.legado.app.domain.model.settings.ReadSettings
import io.legado.app.help.book.isNotShelf
import io.legado.app.support.InMemoryAppDatabaseFixture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MangaReaderTemporaryBookTest {
    private lateinit var fixture: InMemoryAppDatabaseFixture
    private lateinit var imageLoader: ImageLoader
    private lateinit var repository: MangaReaderActionRepository

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        application.injectAsAppCtx()
        fixture = InMemoryAppDatabaseFixture(application)
        imageLoader = ImageLoader.Builder(application).build()
        repository = MangaReaderActionRepository(
            application = application,
            database = fixture.database,
            imageLoader = imageLoader,
            otherSettingsGateway = FakeOtherSettings(),
            readSettingsGateway = FakeReadSettings(),
        )
    }

    @After
    fun tearDown() {
        imageLoader.shutdown()
        fixture.close()
    }

    @Test
    fun `discarding a still temporary comic removes its book and chapters`() = runBlocking {
        val book = insertComic(temporary = true)

        repository.removeTemporaryBook(book.bookUrl)

        assertNull(fixture.database.bookDao.getBook(book.bookUrl))
        assertEquals(0, fixture.database.bookChapterDao.getChapterCount(book.bookUrl))
    }

    @Test
    fun `discard from an old reader snapshot preserves a comic added in details`() = runBlocking {
        val oldReaderBook = insertComic(temporary = true)
        repository.addCurrentBookToShelf(oldReaderBook.bookUrl)

        // The reader (or its already-open confirmation) still knows only the old snapshot.
        repository.removeTemporaryBook(oldReaderBook.bookUrl)

        val savedBook = fixture.database.bookDao.getBook(oldReaderBook.bookUrl)
        assertNotNull(savedBook)
        assertFalse(requireNotNull(savedBook).isNotShelf)
        assertEquals("Comic", savedBook.name)
        assertEquals(1, fixture.database.bookChapterDao.getChapterCount(oldReaderBook.bookUrl))
    }

    @Test
    fun `temporary cleanup leaves existing shelf comics and absent records alone`() = runBlocking {
        val book = insertComic(temporary = false)

        repository.removeTemporaryBook(book.bookUrl)
        repository.removeTemporaryBook("missing-comic")

        assertEquals(book, fixture.database.bookDao.getBook(book.bookUrl))
        assertEquals(1, fixture.database.bookChapterDao.getChapterCount(book.bookUrl))
    }

    private fun insertComic(temporary: Boolean): Book {
        val book = Book(
            bookUrl = "comic",
            name = "Comic",
            author = "Author",
            origin = "source",
            type = BookType.image or if (temporary) BookType.notShelf else 0,
        )
        fixture.database.bookDao.insert(book)
        fixture.database.bookChapterDao.insert(
            BookChapter(bookUrl = book.bookUrl, url = "chapter", title = "Chapter", index = 0)
        )
        return book
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
}
