package io.legado.app.ui.book.cache.manage

import io.legado.app.data.entities.Book
import io.legado.app.data.model.BookChapterCacheInfo
import io.legado.app.data.repository.BookCacheSavedCount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookCacheProgressiveLoadTest {
    @Test
    fun savedRowsShowConfirmedLastCountBeforeEvenListingBookDirectories() = runTest {
        val diskGate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val job = launch {
            loadCacheItemsInStages(
                books = listOf(Book(bookUrl = "old")),
                loadSaved = { listOf(row("old").withSavedCacheCount(BookCacheSavedCount(10, 6))) },
                onSaved = { saved ->
                    val item = saved.single()
                    assertTrue(item.isCheckingCache)
                    assertTrue(item.hasKnownCacheCount)
                    assertFalse(summarizeBookCache(saved).hasDownloadTarget)
                    events += "saved:${item.cachedCount}"
                },
                loadSummary = {
                    diskGate.await()
                    row(it.bookUrl)
                },
                onSummaries = { events += "disk" },
                countCached = { _, _ -> 5 },
                onCount = { _, count -> events += "verified:$count" },
            )
        }
        runCurrent()
        assertEquals(listOf("saved:6"), events)
        diskGate.complete(Unit)
        job.join()
        assertEquals(listOf("saved:6", "disk", "verified:5"), events)
    }

    @Test
    fun publishesRowsBeforeSlowImageScanAndUpdatesEachBookSeparately() = runTest {
        val scanGate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val job = launch {
            loadCacheItemsInStages(
                books = listOf(Book(bookUrl = "a"), Book(bookUrl = "b")),
                loadSummary = { row(it.bookUrl) },
                onSummaries = { events += "rows:${it.size}" },
                countCached = { book, _ ->
                    if (book.bookUrl == "b") scanGate.await()
                    3
                },
                onCount = { url, count -> events += "$url:$count" },
            )
        }
        runCurrent()
        assertEquals(listOf("rows:2", "a:3"), events)
        scanGate.complete(Unit)
        job.join()
        assertEquals(listOf("rows:2", "a:3", "b:3"), events)
    }

    @Test
    fun cancellationStopsOldScanWithoutPublishingOrScanningAnotherBook() = runTest {
        val scanGate = CompletableDeferred<Unit>()
        val scanned = mutableListOf<String>()
        val counts = mutableListOf<Int>()
        val job = launch {
            loadCacheItemsInStages(
                books = listOf(Book(bookUrl = "a"), Book(bookUrl = "b")),
                loadSummary = { row(it.bookUrl) },
                onSummaries = {},
                countCached = { book, _ ->
                    scanned += book.bookUrl
                    scanGate.await()
                    10
                },
                onCount = { _, count -> counts += count },
            )
        }
        runCurrent()
        job.cancel()
        scanGate.complete(Unit)
        job.join()
        assertEquals(listOf("a"), scanned)
        assertTrue(counts.isEmpty())
    }

    @Test
    fun booksWithoutCacheAreNotImageScannedAndEmptyListStillPublishes() = runTest {
        var rows: List<BookCacheBookItem>? = null
        loadCacheItemsInStages(
            books = listOf(Book(bookUrl = "no-cache")),
            loadSummary = { null },
            onSummaries = { rows = it },
            countCached = { _, _ -> error("Uncached book should not be scanned") },
            onCount = { _, _ -> error("No count expected") },
        )
        assertEquals(emptyList<BookCacheBookItem>(), rows)
    }

    @Test
    fun downloadCompletionDuringScanDoesNotGetOverwrittenOrCountedTwice() {
        val first = BookChapterCacheInfo("first", "First", false, 0)
        val second = BookChapterCacheInfo("second", "Second", false, 1)
        val changes = BookCacheScanChanges()
        changes.chapterCompleted(first)
        changes.chapterCompleted(second)
        changes.chapterCompleted(second)

        assertEquals(setOf(first, second), changes.reconcile(setOf(first)))
    }

    @Test
    fun deletionInvalidatesAnOverlappingScan() {
        val first = BookChapterCacheInfo("first", "First", false, 0)
        val changes = BookCacheScanChanges()
        changes.chapterCompleted(first)
        changes.invalidate()

        assertNull(changes.reconcile(setOf(first)))
    }

    private fun row(url: String) = BookCacheBookItem(
        bookUrl = url, name = url, author = "", totalCount = 10,
        cachedCount = 0, hasCacheFiles = true, waitingCount = 0,
        downloadingCount = 0, pausedCount = 0, errorCount = 0,
        isNotShelf = false, group = 0L, isCheckingCache = true,
    )
}
