package io.legado.app.ui.book.cache.manage

import org.junit.Assert.assertEquals
import org.junit.Test

class BookCacheListSortTest {

    @Test
    fun keepsFifoOrderEvenWhenOnlyOneBookIsDownloading() {
        val items = listOf(
            item("a", downloadingCount = 0, waitingCount = 3),
            item("b", downloadingCount = 2, waitingCount = 0),
            item("c", downloadingCount = 0, waitingCount = 0, pausedCount = 5),
        )

        val sorted = sortBookCacheItems(items, downloadOrder = listOf("a", "b", "c"))

        assertEquals(listOf("a", "b", "c"), sorted.map { it.bookUrl })
    }

    @Test
    fun doesNotJumpWhenMiddleBookBecomesActiveFirst() {
        // 模拟 FAB 恢复时 b 先刷新成下载中、a 仍显示暂停的中间态
        val midFlush = listOf(
            item("a", pausedCount = 10),
            item("b", downloadingCount = 1),
            item("c", pausedCount = 8),
        )

        val sorted = sortBookCacheItems(midFlush, downloadOrder = listOf("a", "b", "c"))

        assertEquals(listOf("a", "b", "c"), sorted.map { it.bookUrl })
    }

    @Test
    fun booksOutsideFifoStayAfterQueueOrderedByCachedCountThenName() {
        val items = listOf(
            item("zoo", cachedCount = 9),
            item("b", waitingCount = 1),
            item("alpha", cachedCount = 1),
            item("a", downloadingCount = 1),
        )

        val sorted = sortBookCacheItems(items, downloadOrder = listOf("a", "b"))

        assertEquals(listOf("a", "b", "zoo", "alpha"), sorted.map { it.bookUrl })
    }

    @Test
    fun `overview counts only the supplied books and paused work remains resumable`() {
        val visible = item("visible", pausedCount = 3, cachedCount = 2).copy(
            totalCount = 2,
            errorCount = 1,
        )
        val hidden = item("hidden", waitingCount = 10, downloadingCount = 2)
        assertEquals(
            BookCacheOverview(paused = 3, failed = 1, cached = 2, hasDownloadTarget = true),
            summarizeBookCache(listOf(visible)),
        )
        assertEquals(10, summarizeBookCache(listOf(visible, hidden)).waiting)
        assertEquals(
            BookCacheOverview(isCheckingCache = true),
            summarizeBookCache(listOf(item("checking").copy(isCheckingCache = true))),
        )
    }

    @Test
    fun refreshKeepsOrderEvenWhenCachedCountsAndDownloadPriorityChange() {
        val current = listOf(item("first", cachedCount = 4), item("last", cachedCount = 1))
        val updated = listOf(item("first", cachedCount = 4), item("last", cachedCount = 8, downloadingCount = 1))
        val prioritized = sortBookCacheItems(updated, downloadOrder = listOf("last"))

        assertEquals(listOf("last", "first"), prioritized.map { it.bookUrl })
        assertEquals(updated, retainBookCacheOrder(current, prioritized))
    }

    @Test
    fun refreshRemovesMissingBooksAndAppendsNewBooks() {
        val current = listOf(item("a"), item("removed"), item("b"))
        val incoming = listOf(item("new"), item("b"), item("a"))

        assertEquals(listOf("a", "b", "new"), retainBookCacheOrder(current, incoming).map { it.bookUrl })
        assertEquals(incoming, retainBookCacheOrder(emptyList(), incoming))
    }

    @Test
    fun explicitStorageOrderKeepsTiesAndUnknownsStableWithoutChangingQueueOrder() {
        val rows = listOf(item("first"), item("tie1"), item("large"), item("tie2"), item("unknown"))
        val storage = BookCacheStorageOrder.create(rows, mapOf("first" to 0, "tie1" to 11, "tie2" to 11, "large" to 200))
        val sortedUrls = listOf("large", "tie1", "tie2", "first", "unknown")
        assertEquals(sortedUrls, storage.applyTo(rows, emptySet()).map { it.bookUrl })
        val live = rows.map { it.copy(cachedCount = 1, downloadingCount = 1) } + item("new")
        val stale = storage.withoutBytes("large")
        assertEquals(null, stale.bytesByBook["large"])
        assertEquals(sortedUrls + "new", stale.applyTo(live, emptySet()).map { it.bookUrl })
        assertEquals(listOf("tie1", "tie2", "first", "large", "unknown"), storage.applyTo(rows, setOf("large")).map { it.bookUrl })
        assertEquals("first", sortBookCacheItems(live, listOf("first", "large")).first().bookUrl)
    }

    private fun item(
        bookUrl: String,
        downloadingCount: Int = 0,
        waitingCount: Int = 0,
        pausedCount: Int = 0,
        cachedCount: Int = 0,
    ): BookCacheBookItem {
        return BookCacheBookItem(
            bookUrl = bookUrl,
            name = bookUrl,
            author = "",
            totalCount = 10,
            cachedCount = cachedCount,
            hasCacheFiles = cachedCount > 0,
            waitingCount = waitingCount,
            downloadingCount = downloadingCount,
            pausedCount = pausedCount,
            errorCount = 0,
            isNotShelf = false,
            group = 0L,
        )
    }
}
