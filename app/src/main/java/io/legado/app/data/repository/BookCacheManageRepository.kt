package io.legado.app.data.repository

import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.data.dao.BookGroupDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.model.BookChapterCacheInfo
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class BookCacheManageRepository(
    private val bookDao: BookDao,
    private val bookChapterDao: BookChapterDao,
    private val bookGroupDao: BookGroupDao,
) {

    fun flowBooks(): Flow<List<Book>> = bookDao.flowAll()
        .distinctUntilChangedBy(::cacheBookIdentities)
        .flowOn(Dispatchers.IO)

    fun flowSelectableGroups(): Flow<List<BookGroup>> =
        bookGroupDao.flowSelect().flowOn(Dispatchers.IO)

    suspend fun getAllBooks(): List<Book> = withContext(Dispatchers.IO) {
        bookDao.all
    }

    suspend fun getBook(bookUrl: String): Book? = withContext(Dispatchers.IO) {
        bookDao.getBook(bookUrl)
    }

    suspend fun getChapterCount(bookUrl: String): Int = withContext(Dispatchers.IO) {
        bookChapterDao.getChapterCount(bookUrl)
    }

    suspend fun getVolumeCount(bookUrl: String): Int = withContext(Dispatchers.IO) {
        bookChapterDao.getVolumeCount(bookUrl)
    }

    suspend fun getChapterCacheInfo(bookUrl: String): List<BookChapterCacheInfo> =
        withContext(Dispatchers.IO) {
            bookChapterDao.getChapterCacheInfoList(bookUrl)
        }

    suspend fun countCachedChapters(book: Book): Int = withContext(Dispatchers.IO) {
        val chapters = bookChapterDao.getChapterCacheInfoList(book.bookUrl)
        chapters.count { chapter ->
            currentCoroutineContext().ensureActive()
            chapter.isVolume || BookHelp.isChapterCacheComplete(
                book,
                BookChapter(
                    bookUrl = book.bookUrl,
                    url = chapter.url,
                    title = chapter.title,
                    isVolume = chapter.isVolume,
                    index = chapter.index,
                ),
            )
        }
    }
}

internal data class CacheBookIdentity(
    val bookUrl: String,
    val name: String,
    val author: String,
    val folderName: String,
    val origin: String,
    val type: Int,
    val group: Long,
    val chapterCount: Int,
    val latestChapterTime: Long,
)

internal fun cacheBookIdentity(book: Book) = CacheBookIdentity(
    bookUrl = book.bookUrl,
    name = book.name,
    author = book.getRealAuthor(),
    folderName = book.getFolderName(),
    origin = book.origin,
    type = book.type,
    group = book.group,
    chapterCount = book.totalChapterNum,
    latestChapterTime = book.latestChapterTime,
)

internal fun cacheBookIdentities(books: List<Book>): List<CacheBookIdentity> =
    books.map(::cacheBookIdentity).sortedBy { it.bookUrl }
