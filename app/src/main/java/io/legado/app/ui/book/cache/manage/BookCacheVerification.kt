package io.legado.app.ui.book.cache.manage

import io.legado.app.data.model.BookChapterCacheInfo
import io.legado.app.data.repository.CacheBookIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Main-thread coordination for book-level cache verification. */
internal class BookCacheVerification {
    private class Scan(val identity: CacheBookIdentity) {
        val changes = BookCacheScanChanges()
        val result = CompletableDeferred<Set<BookChapterCacheInfo>?>()
        var callers = 0
    }

    private val inFlight = hashMapOf<String, MutableSet<Scan>>()

    suspend fun verify(
        identity: CacheBookIdentity,
        scan: suspend () -> Set<BookChapterCacheInfo>,
        onVerified: (Set<BookChapterCacheInfo>) -> Unit,
    ): Set<BookChapterCacheInfo> {
        val bookUrl = identity.bookUrl
        while (true) {
            currentCoroutineContext().ensureActive()
            val scans = inFlight.getOrPut(bookUrl) { hashSetOf() }
            val running = scans.firstOrNull { it.identity == identity && !it.result.isCompleted }
            val request = running ?: Scan(identity).also { scans.add(it) }
            request.callers++
            try {
                if (running == null) {
                    val read = scan()
                    currentCoroutineContext().ensureActive()
                    val verified = request.changes.reconcile(read) ?: continue
                    onVerified(verified)
                    request.result.complete(verified)
                    return verified
                }

                // A download or deletion can happen after the disk result is delivered but
                // before this caller resumes. Keep reconciling until the last caller leaves.
                val verified = request.result.await()?.let(request.changes::reconcile)
                if (verified != null) return verified
            } catch (error: Throwable) {
                if (running == null && error !is CancellationException) {
                    request.result.completeExceptionally(error)
                }
                throw error
            } finally {
                // Null asks surviving callers to retry when the owner is canceled or invalidated.
                if (running == null) request.result.complete(null)
                if (--request.callers == 0) {
                    scans.remove(request)
                    if (scans.isEmpty()) inFlight.remove(bookUrl)
                }
            }
        }
    }

    fun chapterCompleted(bookUrl: String, chapter: BookChapterCacheInfo) {
        inFlight[bookUrl]?.forEach { it.changes.chapterCompleted(chapter) }
    }

    fun isVerifying(bookUrl: String) = inFlight[bookUrl]?.any { !it.result.isCompleted } == true

    fun invalidate(bookUrl: String) {
        inFlight[bookUrl]?.forEach { it.changes.invalidate() }
    }

    fun invalidateAll() = inFlight.keys.forEach(::invalidate)
}

/** Main-thread changes that occurred after a scan started reading disk. */
internal class BookCacheScanChanges {
    private var invalidated = false
    private val completed = hashSetOf<BookChapterCacheInfo>()

    fun chapterCompleted(chapter: BookChapterCacheInfo) { completed.add(chapter) }
    fun invalidate() { invalidated = true }

    fun reconcile(scanned: Set<BookChapterCacheInfo>): Set<BookChapterCacheInfo>? = when {
        invalidated -> null
        completed.isEmpty() -> scanned
        else -> scanned + completed
    }
}
