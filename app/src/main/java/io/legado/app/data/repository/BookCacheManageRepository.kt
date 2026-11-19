package io.legado.app.data.repository

import android.app.Application
import androidx.collection.LruCache
import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.data.dao.BookGroupDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.model.BookChapterCacheInfo
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ChapterCacheDeleteResult
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.model.CacheBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

class BookCacheManageRepository(
    application: Application,
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

    internal suspend fun deleteChapterCache(
        bookUrl: String,
        index: Int,
        expectedUrl: String,
        expectedTitle: String,
    ): ChapterCacheDeleteResult = withContext(Dispatchers.IO) {
        val book = bookDao.getBook(bookUrl)?.takeUnless { it.isLocal || it.isAudio || it.isVideo }
            ?: return@withContext ChapterCacheDeleteResult.ChapterChanged
        val chapter = bookChapterDao.getChapter(bookUrl, index)
            ?.takeIf { !it.isVolume && it.url == expectedUrl && it.title == expectedTitle }
            ?: return@withContext ChapterCacheDeleteResult.ChapterChanged
        // Consult the running queue while under the logical lease guard as well. That covers
        // the handoff between the download's text-fetch and image-write leases.
        BookHelp.deleteManagedChapterCache(book, chapter) {
            CacheBook.downloadStateFlow.value.books[bookUrl]?.runningIndices?.contains(index) == true
        }
    }

    suspend fun getDownloadableChapterCount(bookUrl: String): Int = withContext(Dispatchers.IO) {
        bookChapterDao.getDownloadableChapterCount(bookUrl)
    }

    suspend fun getChapterCacheInfo(bookUrl: String): List<BookChapterCacheInfo> =
        withContext(Dispatchers.IO) {
            bookChapterDao.getChapterCacheInfoList(bookUrl)
        }

    // The book-level snapshot is only for display. Download admission always checks the files.
    private val recentScans = LruCache<String, ChapterCacheSnapshot>(32)
    private val savedCounts by lazy {
        BookCacheSummaryStore(File(application.noBackupFilesDir, "book-cache-counts.bin"))
    }

    internal suspend fun savedBookCounts(books: List<Book>): Map<String, BookCacheSavedCount> =
        withContext(Dispatchers.IO) {
            val entries = savedCounts.read()
            if (entries.isEmpty()) return@withContext emptyMap()
            buildMap {
                for (book in books) {
                    ensureActive()
                    val entry = entries[cacheSummaryKey(book.bookUrl)] ?: continue
                    if (entry.identity == cacheSummaryIdentity(cacheBookIdentity(book))) {
                        put(book.bookUrl, entry.count)
                    }
                }
            }
        }

    internal fun hasCacheFiles(book: Book): Boolean = hasBookCacheFiles(bookCacheFolder(book).toPath())

    internal suspend fun measureCacheBytes(books: List<Book>): Map<String, Long> = withContext(Dispatchers.IO) {
        buildMap {
            for (book in books) {
                ensureActive()
                bookCacheFileBytes(bookCacheFolder(book).toPath())?.let { put(book.bookUrl, it) }
            }
        }
    }

    private fun bookCacheFolder(book: Book) = File(BookHelp.cachePath, book.getFolderName())

    fun recentCachedChapters(book: Book): Set<BookChapterCacheInfo>? =
        recentScans[book.bookUrl]?.takeIf { it.identity == cacheBookIdentity(book) }?.chapters

    fun invalidateCachedChapters(bookUrl: String) {
        recentScans.remove(bookUrl)
        savedCounts.update(cacheSummaryKey(bookUrl), null)
    }

    fun rememberCachedChapters(book: Book, chapters: Set<BookChapterCacheInfo>, totalCount: Int) {
        val snapshot = ChapterCacheSnapshot(cacheBookIdentity(book), chapters, totalCount)
        recentScans.put(book.bookUrl, snapshot)
        saveCount(snapshot)
    }

    private fun saveCount(snapshot: ChapterCacheSnapshot) {
        val count = snapshot.chapters.size.coerceAtMost(snapshot.totalCount)
        val key = cacheSummaryKey(snapshot.identity.bookUrl)
        savedCounts.update(key, if (count == 0) null else BookCacheSummaryEntry(
            cacheSummaryIdentity(snapshot.identity), BookCacheSavedCount(snapshot.totalCount, count),
        ))
    }

    // Returns an increment only when there is a verified baseline, never from the persisted count.
    fun recordCachedChapter(bookUrl: String, chapter: BookChapterCacheInfo): Int? {
        val previous = recentScans[bookUrl] ?: return null
        if (chapter !in previous.chapters) {
            val updated = previous.copy(chapters = previous.chapters + chapter)
            recentScans.put(bookUrl, updated)
            saveCount(updated)
            return updated.chapters.size
        }
        return previous.chapters.size
    }

    suspend fun scanCachedChapters(
        book: Book,
        chapters: List<BookChapterCacheInfo>? = null,
    ): Set<BookChapterCacheInfo> = withContext(Dispatchers.IO) {
        val scanContext = currentCoroutineContext()
        val contents = BookHelp.getChapterFiles(book, createIfMissing = false)
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
        val totalCount: Int,
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

internal fun cacheSummaryKey(bookUrl: String): String = sha256(bookUrl)

internal fun cacheSummaryIdentity(identity: CacheBookIdentity): String = with(identity) {
    val parts = listOf(bookUrl, name, author, folderName, origin, type, group, chapterCount, latestChapterTime)
    sha256(parts.joinToString("") { part -> "${part.toString().length}:$part" })
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).toHexString()
