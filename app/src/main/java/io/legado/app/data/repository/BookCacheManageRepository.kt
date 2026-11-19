package io.legado.app.data.repository

import androidx.collection.LruCache
import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.data.dao.BookGroupDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.model.BookChapterCacheInfo
import io.legado.app.help.book.BookHelp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

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

    suspend fun getDownloadableChapterCount(bookUrl: String): Int = withContext(Dispatchers.IO) {
        bookChapterDao.getDownloadableChapterCount(bookUrl)
    }

    suspend fun getChapterCacheInfo(bookUrl: String): List<BookChapterCacheInfo> =
        withContext(Dispatchers.IO) {
            bookChapterDao.getChapterCacheInfoList(bookUrl)
        }

    // Display a recent verified result on re-entry while disk reconciliation runs. Never use
    // this snapshot to decide whether to skip an actual download.
    private val recentScans = LruCache<String, ChapterCacheSnapshot>(32)

    fun recentCachedChapters(book: Book): Set<BookChapterCacheInfo>? =
        recentScans[book.bookUrl]?.takeIf { it.identity == cacheBookIdentity(book) }?.chapters

    fun invalidateCachedChapters(bookUrl: String) {
        recentScans.remove(bookUrl)
    }

    fun rememberCachedChapters(book: Book, chapters: Set<BookChapterCacheInfo>) {
        recentScans.put(book.bookUrl, ChapterCacheSnapshot(cacheBookIdentity(book), chapters))
    }

    // Returns the updated count only when we already have a verified baseline. Cancelling or
    // failing a scan must not replace that baseline with an incomplete result.
    fun recordCachedChapter(bookUrl: String, chapter: BookChapterCacheInfo): Int? {
        val previous = recentScans[bookUrl] ?: return null
        if (chapter !in previous.chapters) {
            recentScans.put(bookUrl, previous.copy(chapters = previous.chapters + chapter))
            return previous.chapters.size + 1
        }
        return previous.chapters.size
    }

    suspend fun scanCachedChapters(
        book: Book,
        chapters: List<BookChapterCacheInfo>? = null,
    ): Set<BookChapterCacheInfo> = withContext(Dispatchers.IO) {
        val scanContext = currentCoroutineContext()
        val contents = BookHelp.getChapterFiles(book)
        if (contents.none { it.endsWith(".nb") }) {
            emptySet()
        } else {
            val catalog = chapters ?: bookChapterDao.getChapterCacheInfoList(book.bookUrl)
            buildSet {
                for (chapter in catalog) {
                    scanContext.ensureActive()
                    if (chapter.isVolume) continue
                    val entity = BookChapter(
                        bookUrl = book.bookUrl, url = chapter.url, title = chapter.title, index = chapter.index,
                    )
                    // Don't open files or walk image directories for chapters with no cached content.
                    if (entity.getFileName() !in contents) continue
                    val isComplete = try {
                        BookHelp.isChapterCacheComplete(book, entity)
                    } catch (_: FileNotFoundException) {
                        // Deletion can race the content open after the initial directory listing.
                        false
                    }
                    if (isComplete) add(chapter)
                }
            }
        }
    }

    private data class ChapterCacheSnapshot(
        val identity: CacheBookIdentity,
        val chapters: Set<BookChapterCacheInfo>,
    )
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
