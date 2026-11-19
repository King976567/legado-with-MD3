package io.legado.app.ui.book.cache.manage

import io.legado.app.data.entities.Book
import io.legado.app.data.model.BookChapterCacheInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookCacheProgressiveLoadTest {
    @Test
    fun publishesRowsBeforeSlowImageScanAndUpdatesEachBookSeparately() = runTest {
        val scanGate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val job = launch {
            loadCacheItemsInStages(
                books = listOf(Book(bookUrl = "a"), Book(bookUrl = "b")),
                loadSummary = { row(it.bookUrl) },
                onSummaries = { events += "rows:${it.size}" },
                countCached = {
                    if (it.bookUrl == "b") scanGate.await()
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
                countCached = {
                    scanned += it.bookUrl
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
            countCached = { error("Uncached book should not be scanned") },
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
        cachedCount = 0, cachedFileCount = 3, waitingCount = 0,
        downloadingCount = 0, pausedCount = 0, errorCount = 0,
        isNotShelf = false, group = 0L, isCheckingCache = true,
    )
}
