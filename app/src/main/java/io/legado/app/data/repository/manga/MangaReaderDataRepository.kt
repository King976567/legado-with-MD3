package io.legado.app.data.repository.manga

import android.app.Application
import androidx.room.withTransaction
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.data.repository.ReadRecordRepository
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.domain.gateway.BookCacheDownloadGateway
import io.legado.app.domain.gateway.MangaReaderDataGateway
import io.legado.app.domain.model.manga.MangaBookPresentation
import io.legado.app.domain.model.manga.MangaBookState
import io.legado.app.domain.model.manga.MangaCatalog
import io.legado.app.domain.model.manga.MangaChapterContent
import io.legado.app.domain.model.manga.MangaProgressState
import io.legado.app.domain.model.manga.OpenedMangaBook
import io.legado.app.domain.usecase.GetReadingProgressUseCase
import io.legado.app.domain.usecase.UploadReadingProgressUseCase
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalManga
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.source.getSourceType
import io.legado.app.help.storage.Backup
import io.legado.app.model.cache.CacheDownloadRequest
import io.legado.app.model.cache.CacheDownloadSource
import io.legado.app.model.cache.ChapterSelection
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.LocalMangaFiles
import io.legado.app.model.localBook.isLocalMangaArchive
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

class MangaReaderDataRepository(
    private val application: Application,
    private val database: AppDatabase,
    private val getReadingProgressUseCase: GetReadingProgressUseCase,
    private val uploadReadingProgressUseCase: UploadReadingProgressUseCase,
    private val backupSettingsGateway: BackupSettingsGateway,
    private val readRecordRepository: ReadRecordRepository,
    private val bookCacheDownloadGateway: BookCacheDownloadGateway,
) : MangaReaderDataGateway {

    private var readingStartedAt: Long? = null
    private val chapterPageLoader = MangaChapterPageLoader(application, database)
    private val localMangaLoader =
        LocalMangaLoader(java.io.File(application.cacheDir, "local-manga"))

    override fun observeBookPresentation(bookUrl: String) = combine(
        database.bookDao.flowGetBook(bookUrl).map { book ->
            val presentation = MangaBookPresentation(
                scrollMode = book?.readConfig?.mangaScrollMode,
                sidePaddingDp = book?.readConfig?.webtoonSidePaddingDp,
                inBookshelf = book?.let { !it.isNotShelf },
            )
            val isOnline = book?.isLocal == false
            val simulatedCount = book?.takeIf { isOnline && it.readSimulating() }
                ?.simulatedTotalChapterNum()
            Triple(presentation, isOnline, simulatedCount)
        }.distinctUntilChanged(),
        database.bookChapterDao.flowChapterCacheInfo(bookUrl).distinctUntilChanged().map { chapters ->
            MangaCatalog(
                urls = chapters.map { it.url },
                titles = chapters.map { it.title },
                readableCount = chapters.size,
            )
        },
    ) { (presentation, isOnline, simulatedCount), catalog ->
        presentation.copy(catalog = when {
            !isOnline -> null
            simulatedCount != null -> catalog.copy(
                readableCount = simulatedCount.coerceIn(0, catalog.urls.size),
            )
            else -> catalog
        })
    }.distinctUntilChanged()

    override suspend fun checkForCatalogUpdates(bookUrl: String) {
        if (!NetworkUtils.isAvailable()) return
        val original = database.bookDao.getBook(bookUrl) ?: return
        if (original.isLocal || !original.canUpdate || original.tocUrl.isBlank() ||
            original.readSimulating()
        ) return
        val source = database.bookSourceDao.getBookSource(original.origin)?.takeIf { it.enabled } ?: return
        val fetchedBook = original.copy()
        val fetched = withTimeoutOrNull(15_000L) {
            WebBook.getChapterListAwait(source, fetchedBook, runPerJs = true).getOrThrow()
        } ?: return
        currentCoroutineContext().ensureActive()
        database.appendMangaCatalog(original, fetchedBook, fetched)
    }

    override suspend fun openBook(
        bookUrl: String?,
        inBookshelf: Boolean,
        chapterChanged: Boolean,
    ): OpenedMangaBook {
        val book = (if (bookUrl.isNullOrEmpty()) {
            database.bookDao.lastReadBook
        } else {
            database.bookDao.getBook(bookUrl)
        }) ?: throw NoStackTraceException(application.getString(R.string.no_book))
        if (book.isLocal) {
            if (book.isLocalManga && !book.isLocalMangaArchive) {
                LocalMangaFiles.checkDirectory(book)
            } else {
                LocalBook.getBookInputStream(book).use { }
            }
        }
        val source = database.bookSourceDao.getBookSource(book.origin)
        if (!book.isLocal && book.tocUrl.isEmpty()) {
            val activeSource = source ?: throw NoStackTraceException(
                application.getString(R.string.manga_reader_details_failed),
            )
            val oldBook = book.copy()
            WebBook.getBookInfoAwait(activeSource, book, canReName = false)
            if (oldBook.bookUrl == book.bookUrl) database.bookDao.update(book)
            else {
                database.bookDao.replace(oldBook, book)
                BookHelp.updateCacheFolder(oldBook, book)
            }
        }
        if (database.bookChapterDao.getChapterCount(book.bookUrl) == 0 || book.isLocalModified()) {
            if (book.isLocal) {
                val chapters = LocalBook.getChapterList(book)
                database.runInTransaction {
                    database.bookChapterDao.delByBook(book.bookUrl)
                    database.bookChapterDao.insert(*chapters.toTypedArray())
                    database.bookDao.update(book)
                }
            } else {
                val activeSource = source ?: throw NoStackTraceException(
                    application.getString(R.string.error_load_toc),
                )
                val oldBook = book.copy()
                val chapters = WebBook.getChapterListAwait(activeSource, book, true).getOrThrow()
                if (oldBook.bookUrl == book.bookUrl) {
                    database.bookDao.update(book)
                } else {
                    database.bookDao.replace(oldBook, book)
                    BookHelp.updateCacheFolder(oldBook, book)
                }
                database.bookChapterDao.delByBook(oldBook.bookUrl)
                database.bookChapterDao.insert(*chapters.toTypedArray())
            }
        }
        // Older imports may not have a separate cover. Do not replace an existing or custom cover.
        if (book.isLocalManga && localMangaLoader.ensureCover(book)) {
            database.bookDao.update(book)
        }
        val chapters = database.bookChapterDao.getChapterList(book.bookUrl)
        val simulatedCount = if (book.readSimulating()) {
            book.simulatedTotalChapterNum().coerceIn(0, chapters.size)
        } else chapters.size
        val safeChapter = book.durChapterIndex.coerceIn(0, (simulatedCount - 1).coerceAtLeast(0))
        val newerProgress = if (!chapterChanged) findNewerProgress(book) else null
        return OpenedMangaBook(
            book = MangaBookState(
                bookUrl = book.bookUrl,
                name = book.name,
                author = book.author,
                coverUrl = book.coverUrl,
                customCoverUrl = book.customCoverUrl,
                sourceOrigin = book.origin,
                sourceName = source?.bookSourceName.orEmpty(),
                sourceType = source?.getSourceType(),
                inBookshelf = inBookshelf,
                scrollMode = book.readConfig?.mangaScrollMode,
                sidePaddingDp = book.readConfig?.webtoonSidePaddingDp,
                isLocal = book.isLocal,
                chapterTitles = chapters.map { it.title },
                chapterUrls = chapters.map { it.url },
            ),
            chapterIndex = safeChapter,
            pageIndex = book.durChapterPos.coerceAtLeast(0),
            chapterCount = simulatedCount,
            newerProgress = newerProgress,
        )
    }

    override suspend fun loadChapter(bookUrl: String, chapterIndex: Int): MangaChapterContent {
        val book = database.bookDao.getBook(bookUrl)
            ?: throw NoStackTraceException(application.getString(R.string.no_book))
        val chapter = database.bookChapterDao.getChapter(bookUrl, chapterIndex)
            ?: throw NoStackTraceException(application.getString(R.string.error_load_toc))
        return if (book.isLocalManga) {
            localMangaLoader.load(book, chapter)
        } else chapterPageLoader.load(book, chapter)
    }

    override suspend fun prefetchChapter(bookUrl: String, chapterIndex: Int) {
        val book = database.bookDao.getBook(bookUrl) ?: return
        if (book.isLocal) return
        bookCacheDownloadGateway.start(
            CacheDownloadRequest(
                bookUrl = bookUrl,
                selection = ChapterSelection.Single(chapterIndex),
                source = CacheDownloadSource.ReadPreload,
            )
        )
    }

    override suspend fun retainChapterResources(bookUrl: String, chapterIndexes: Set<Int>) {
        chapterPageLoader.retain(bookUrl, chapterIndexes)
    }

    override suspend fun releaseAllChapterResources() {
        chapterPageLoader.close()
        localMangaLoader.close()
    }

    override suspend fun persistProgress(bookUrl: String, chapterIndex: Int, pageIndex: Int) {
        database.withTransaction {
            val book = database.bookDao.getBook(bookUrl) ?: return@withTransaction
            book.durChapterIndex = chapterIndex
            book.durChapterPos = pageIndex
            book.durChapterTime = System.currentTimeMillis()
            database.bookChapterDao.getChapter(bookUrl, chapterIndex)?.let {
                book.durChapterTitle = it.title
            }
            database.bookDao.update(book)
        }
    }

    override suspend fun applyProgress(bookUrl: String, progress: MangaProgressState) {
        persistProgress(bookUrl, progress.chapterIndex, progress.pageIndex)
    }

    override suspend fun resume(bookUrl: String) {
        if (readingStartedAt == null) readingStartedAt = System.currentTimeMillis()
    }

    override suspend fun pause(bookUrl: String, inBookshelf: Boolean) {
        val start = readingStartedAt ?: return
        readingStartedAt = null
        val end = System.currentTimeMillis()
        val book = database.bookDao.getBook(bookUrl) ?: return
        if (end - start >= 10_000) {
            readRecordRepository.saveReadSession(
                ReadRecordSession(
                    bookName = book.name,
                    bookAuthor = book.author,
                    bookUrl = book.bookUrl,
                    startTime = start,
                    endTime = end,
                )
            )
        }
        if (backupSettingsGateway.currentSettings.syncBookProgressPlus) {
            uploadReadingProgressUseCase.execute(book.toProgressState())
        }
        if (inBookshelf && !BuildConfig.DEBUG) Backup.autoBack(application)
    }

    override suspend fun syncProgress(bookUrl: String): MangaProgressState? {
        if (!backupSettingsGateway.currentSettings.syncBookProgressPlus) return null
        val book = database.bookDao.getBook(bookUrl) ?: return null
        return syncOrUpload(book)
    }

    private suspend fun findNewerProgress(book: Book): MangaProgressState? {
        if (!backupSettingsGateway.currentSettings.syncBookProgress) return null
        return syncOrUpload(book)
    }

    private suspend fun syncOrUpload(book: Book): MangaProgressState? {
        val newer = compareProgress(
            book,
            getReadingProgressUseCase.execute(book.name, book.author),
        )
        if (newer == null) uploadReadingProgressUseCase.execute(book.toProgressState())
        return newer
    }

    private fun compareProgress(
        book: Book,
        progress: io.legado.app.domain.model.ReadingProgress?,
    ): MangaProgressState? {
        progress ?: return null
        val remoteIsNewer = progress.durChapterIndex > book.durChapterIndex ||
            progress.durChapterIndex == book.durChapterIndex &&
            progress.durChapterPos > book.durChapterPos
        return progress.takeIf { remoteIsNewer }?.let {
            MangaProgressState(
                bookName = it.name,
                bookAuthor = it.author,
                chapterIndex = it.durChapterIndex,
                pageIndex = it.durChapterPos,
                chapterTitle = it.durChapterTitle,
                updatedAt = it.durChapterTime,
            )
        }
    }

    private fun Book.toProgressState() = io.legado.app.domain.model.ReadingProgress(
        name = name,
        author = author,
        durChapterIndex = durChapterIndex,
        durChapterPos = durChapterPos,
        durChapterTime = durChapterTime,
        durChapterTitle = durChapterTitle,
    )
}

/** Preserve cached chapter identities and active index-based downloads on automatic refresh. */
internal suspend fun AppDatabase.appendMangaCatalog(
    original: Book,
    refreshed: Book,
    fetched: List<BookChapter>,
) = withTransaction {
    val bookUrl = original.bookUrl
    val current = bookDao.getBook(bookUrl) ?: return@withTransaction
    if (refreshed.bookUrl != bookUrl || current.isLocal || !current.canUpdate ||
        current.origin != original.origin || current.tocUrl != original.tocUrl ||
        current.readSimulating() || current.getReverseToc() != original.getReverseToc()
    ) return@withTransaction
    val existing = bookChapterDao.getChapterList(bookUrl)
    if (existing.isEmpty() || fetched.size <= existing.size ||
        existing.indices.any { fetched[it].url != existing[it].url || existing[it].index != it }
    ) return@withTransaction
    val added = fetched.drop(existing.size)
    if (fetched.mapTo(HashSet(fetched.size)) { it.url }.size != fetched.size) return@withTransaction
    if (added.withIndex().any { (index, chapter) ->
            chapter.bookUrl != bookUrl || chapter.index != existing.size + index
        }
    ) return@withTransaction
    bookChapterDao.insert(*added.toTypedArray())
    current.totalChapterNum = fetched.size
    current.latestChapterTitle = refreshed.latestChapterTitle
    current.latestChapterTime = refreshed.lastCheckTime
    current.lastCheckTime = refreshed.lastCheckTime
    current.lastCheckCount = added.size
    bookDao.update(current)
}
