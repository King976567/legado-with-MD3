package io.legado.app.data.repository.manga

import android.app.Application
import androidx.room.Room
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.runBlocking
import io.legado.app.domain.model.manga.MangaCatalogCheck
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MangaCatalogRefreshTest {
    private lateinit var database: AppDatabase
    private val book = Book(
        bookUrl = "https://example.invalid/comic",
        origin = "https://example.invalid",
        tocUrl = "https://example.invalid/toc",
        name = "Online comic",
        type = BookType.image,
        totalChapterNum = 2,
        latestChapterTitle = "Second",
    )
    private val first = BookChapter(bookUrl = book.bookUrl, url = "first", title = "First", index = 0)
    private val second = BookChapter(bookUrl = book.bookUrl, url = "second", title = "Second", index = 1)
    private val third = BookChapter(bookUrl = book.bookUrl, url = "third", title = "Third", index = 2)
    private val fetchedBook get() = book.copy(latestChapterTitle = "Third", lastCheckTime = 42)

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        application.injectAsAppCtx()
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        database.bookDao.insert(book)
        database.bookChapterDao.insert(first, second)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `appending chapters preserves cached title and concurrently saved progress`() = runBlocking {
        database.bookDao.update(book.copy(durChapterIndex = 1, durChapterTitle = "Second", durChapterPos = 8))
        val result = database.appendMangaCatalog(book, fetchedBook, listOf(first.copy(title = "Renamed"), second, third))
        assertEquals(MangaCatalogCheck.Appended, result)

        assertEquals(listOf("First", "Second", "Third"), database.bookChapterDao.getChapterList(book.bookUrl).map { it.title })
        val updated = requireNotNull(database.bookDao.getBook(book.bookUrl))
        assertEquals(3, updated.totalChapterNum)
        assertEquals("Third", updated.latestChapterTitle)
        assertEquals(1, updated.lastCheckCount)
        assertEquals(1, updated.durChapterIndex)
        assertEquals(8, updated.durChapterPos)
    }

    @Test
    fun `detail and reader checking the same catalog do not append chapters twice`() = runBlocking {
        val catalog = listOf(first, second, third)
        assertEquals(MangaCatalogCheck.Appended, database.appendMangaCatalog(book, fetchedBook, catalog))
        assertEquals(MangaCatalogCheck.Unchanged, database.appendMangaCatalog(book, fetchedBook, catalog))
        assertEquals(catalog, database.bookChapterDao.getChapterList(book.bookUrl))
        assertEquals(1, database.bookDao.getBook(book.bookUrl)?.lastCheckCount)
    }

    @Test
    fun `an incomplete or reordered fetch does not change downloaded chapter indices`() = runBlocking {
        val incomplete = database.appendMangaCatalog(book, fetchedBook, listOf(first))
        val reordered = database.appendMangaCatalog(book, fetchedBook, listOf(second.copy(index = 0), first.copy(index = 1), third))
        val inserted = database.appendMangaCatalog(book, fetchedBook, listOf(first, third.copy(index = 1), second.copy(index = 2)))
        assertEquals(MangaCatalogCheck.Unchanged, incomplete)
        assertEquals(MangaCatalogCheck.RequiresManualRefresh, reordered)
        assertEquals(MangaCatalogCheck.RequiresManualRefresh, inserted)
        assertEquals(listOf("first", "second"), database.bookChapterDao.getChapterList(book.bookUrl).map { it.url })
        assertEquals(2, database.bookDao.getBook(book.bookUrl)?.totalChapterNum)
    }

    @Test
    fun `source changed while fetching does not receive old source chapters`() = runBlocking {
        database.bookDao.update(book.copy(origin = "https://example.invalid/new-source"))
        database.appendMangaCatalog(book, fetchedBook, listOf(first, second, third))
        assertEquals(2, database.bookChapterDao.getChapterCount(book.bookUrl))
    }

    @Test
    fun `duplicate chapter URLs are not treated as new entries`() = runBlocking {
        database.appendMangaCatalog(book, fetchedBook, listOf(first, second, second.copy(index = 2)))
        assertEquals(2, database.bookChapterDao.getChapterCount(book.bookUrl))
    }

    @Test
    fun `disabling updates during fetch leaves the existing catalog intact`() = runBlocking {
        database.bookDao.update(book.copy(canUpdate = false))
        database.appendMangaCatalog(book, fetchedBook, listOf(first, second, third))
        assertEquals(2, database.bookChapterDao.getChapterCount(book.bookUrl))
    }

    @Test
    fun `download progress denominator does not include volume separators`() = runBlocking {
        database.bookChapterDao.insert(third.copy(isVolume = true))
        assertEquals(3, database.bookChapterDao.getChapterCount(book.bookUrl))
        assertEquals(2, database.bookChapterDao.getDownloadableChapterCount(book.bookUrl))
    }
}
