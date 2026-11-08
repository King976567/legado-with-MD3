package io.legado.app.data.repository

import android.app.Application
import androidx.room.Room
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.manga.NasComicImageLeases
import io.legado.app.help.book.BookHelp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 35])
class BookCacheMaintenanceRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: BookCacheMaintenanceRepository
    private val book = Book(
        bookUrl = "https://example.invalid/nas-cache-maintenance",
        origin = "https://example.invalid/source",
        name = "NAS maintenance leases",
        type = BookType.image,
    )
    private val persisted = BookChapter(bookUrl = book.bookUrl, url = "old", title = "Old", index = 0)
    private val refreshed = BookChapter(bookUrl = book.bookUrl, url = "new", title = "New", index = 1)
    private val body get() = BookHelp.getChapterContentFile(book, refreshed)
    private val directory get() = body.parentFile!!
    private val legacy get() = File(directory.parentFile, refreshed.getFileName())
    private val entryNames get() = setOf(directory.name, legacy.name)

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.injectAsAppCtx()
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        database.bookDao.insert(book)
        // A nonempty old TOC makes the refreshed upload-only chapter a real orphan.
        database.bookChapterDao.insert(persisted)
        repository = BookCacheMaintenanceRepository(database.bookDao, database.bookChapterDao)
        BookHelp.clearCache(book)
    }

    @After
    fun tearDown() {
        BookHelp.clearCache(book)
        database.close()
    }

    @Test
    fun `refresh-only chapter and legacy body stay listed until upload releases leases`() = runBlocking {
        val bytes = "new chapter body before images".toByteArray()
        NasComicImageLeases().use { leases ->
            leases.pinChapter(book, refreshed)
            directory.mkdirs()
            body.writeBytes(bytes)
            legacy.writeBytes(bytes)
            val group = repository.scanOrphanedChapters { _, _ -> }.single()
            assertEquals(entryNames, group.entryNames.toSet())
            val result = repository.deleteOrphanedChapters(book.bookUrl, entryNames)
            assertEquals(0, result.deletedEntries)
            assertEquals(0L, result.freedBytes)
            assertEquals(1, result.skippedBooks)
            assertTrue(body.isFile)
            assertTrue(legacy.isFile)
        }
        val result = repository.deleteOrphanedChapters(book.bookUrl, entryNames)
        assertEquals(2, result.deletedEntries)
        assertEquals(bytes.size * 2L, result.freedBytes)
        assertEquals(0, result.skippedBooks)
        assertFalse(directory.exists())
        assertFalse(legacy.exists())
    }

    @Test
    fun `existing image lease protects its orphan directory without chapter lease`() = runBlocking {
        val page = File(directory, "images/page.png").apply { parentFile!!.mkdirs(); writeText("page") }
        BookHelp.pinImageFile(page).use {
            val result = repository.deleteOrphanedChapters(book.bookUrl, setOf(directory.name))
            assertEquals(0, result.deletedEntries)
            assertEquals(0L, result.freedBytes)
            assertEquals(1, result.skippedBooks)
            assertTrue(page.isFile)
        }
        val result = repository.deleteOrphanedChapters(book.bookUrl, setOf(directory.name))
        assertEquals(1, result.deletedEntries)
        assertEquals(4L, result.freedBytes)
    }

    @Test
    fun `partial group deletion reports actual bytes and keeps the protected remainder`() = runBlocking {
        directory.mkdirs()
        body.writeText("protected")
        legacy.writeText("removable")
        BookHelp.pinImageFile(body).use {
            val result = repository.deleteOrphanedChapters(book.bookUrl, entryNames)
            assertEquals(1, result.deletedEntries)
            assertEquals(9L, result.freedBytes)
            assertEquals(1, result.skippedBooks)
            assertTrue(body.isFile)
            assertFalse(legacy.exists())
        }
    }

    @Test
    fun `legal chapters stay and missing or empty orphans count correctly`() = runBlocking {
        val legal = BookHelp.getChapterContentFile(book, persisted).apply {
            parentFile!!.mkdirs(); writeText("legal")
        }
        directory.mkdirs()
        val selected = entryNames + legal.parentFile!!.name + "not-present.nb"
        val result = repository.deleteOrphanedChapters(book.bookUrl, selected)
        assertEquals(1, result.deletedEntries)
        assertEquals(0L, result.freedBytes)
        assertEquals(0, result.skippedBooks)
        assertTrue(legal.isFile)
        assertFalse(directory.exists())
    }

    @Test
    fun `concurrent maintenance callers count one physical deletion exactly once`() = runBlocking {
        val bytes = ByteArray(4096) { 1 }
        directory.mkdirs()
        body.writeBytes(bytes)
        val start = CompletableDeferred<Unit>()
        val calls = List(8) {
            async(Dispatchers.IO) {
                start.await()
                repository.deleteOrphanedChapters(book.bookUrl, setOf(directory.name))
            }
        }
        start.complete(Unit)
        val results = calls.awaitAll()
        assertEquals(1, results.sumOf { it.deletedEntries })
        assertEquals(bytes.size.toLong(), results.sumOf { it.freedBytes })
        assertFalse(directory.exists())
    }
}
