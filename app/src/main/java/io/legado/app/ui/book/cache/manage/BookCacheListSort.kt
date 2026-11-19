package io.legado.app.ui.book.cache.manage

/**
 * 缓存管理书籍列表排序：以显式下载 FIFO 顺序为主，避免按「是否正在下载」排序
 * 在 FAB 全局启停时因分批状态刷新导致列表跳动。
 */
fun sortBookCacheItems(
    items: List<BookCacheBookItem>,
    downloadOrder: List<String>,
): List<BookCacheBookItem> {
    val orderIndex = downloadOrder.withIndex().associate { (index, bookUrl) -> bookUrl to index }
    return items.sortedWith(
        compareBy<BookCacheBookItem> { item ->
            orderIndex[item.bookUrl] ?: Int.MAX_VALUE
        }.thenByDescending { it.hasDownloadTask }
            .thenByDescending { it.cachedCount }
            .thenBy { it.name },
    )
}

internal data class BookCacheOverview(
    val downloading: Int = 0,
    val waiting: Int = 0,
    val paused: Int = 0,
    val failed: Int = 0,
    val cached: Int = 0,
    val isCheckingCache: Boolean = false,
    val hasDownloadTarget: Boolean = false,
)

internal fun summarizeBookCache(items: List<BookCacheBookItem>): BookCacheOverview {
    var downloading = 0
    var waiting = 0
    var paused = 0
    var failed = 0
    var cached = 0
    var checking = false
    var hasTarget = false
    items.forEach { book ->
        downloading += book.downloadingCount
        waiting += book.waitingCount
        paused += book.pausedCount
        failed += book.errorCount
        cached += book.cachedCount
        checking = checking || book.isCheckingCache
        hasTarget = hasTarget || book.isPaused ||
            (!book.isCheckingCache && book.cachedCount < book.totalCount)
    }
    return BookCacheOverview(downloading, waiting, paused, failed, cached, checking, hasTarget)
}
