package io.legado.app.ui.book.cache.manage

import io.legado.app.data.entities.Book
import io.legado.app.data.model.BookChapterCacheInfo
import io.legado.app.data.repository.cacheBookIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookCacheVerificationTest {
    private val identity = cacheBookIdentity(Book(bookUrl = "comic", totalChapterNum = 2))
    private val first = BookChapterCacheInfo("first", "First", false, 0)
    private val second = BookChapterCacheInfo("second", "Second", false, 1)

    @Test
    fun openingChaptersDuringFullScanSharesDiskReadAndMergesDownloadCompletion() = runTest {
        val verification = BookCacheVerification()
        val disk = CompletableDeferred<Set<BookChapterCacheInfo>>()
        val published = mutableListOf<Set<BookChapterCacheInfo>>()
        var reads = 0
        val full = async { verification.verify(identity, { reads++; disk.await() }, published::add) }
        runCurrent()
        val expanded = async { verification.verify(identity, { reads++; disk.await() }, published::add) }
        runCurrent()
        assertTrue(verification.isVerifying(identity.bookUrl))
        verification.chapterCompleted(identity.bookUrl, second)
        disk.complete(setOf(first))
        assertEquals(setOf(first, second), full.await())
        assertEquals(full.await(), expanded.await())
        assertEquals(1, reads)
        assertEquals(listOf(setOf(first, second)), published)
        assertFalse(verification.isVerifying(identity.bookUrl))
    }

    @Test
    fun cancelingTheScanOwnerDoesNotCancelTheOtherCaller() = runTest {
        val verification = BookCacheVerification()
        val full = async { verification.verify(identity, { awaitCancellation() }, {}) }
        runCurrent()
        val expanded = async { verification.verify(identity, { setOf(first) }, {}) }
        runCurrent()
        assertFalse(expanded.isCompleted)
        full.cancelAndJoin()
        assertEquals(setOf(first), expanded.await())
        assertFalse(verification.isVerifying(identity.bookUrl))
    }

    @Test
    fun deletingCacheDuringScanCannotPublishItsOldResult() = runTest {
        val verification = BookCacheVerification()
        val disk = CompletableDeferred<Unit>()
        val published = mutableListOf<Set<BookChapterCacheInfo>>()
        var reads = 0
        val full = async {
            verification.verify(identity, {
                if (++reads == 1) {
                    disk.await()
                    setOf(first, second)
                } else setOf(second)
            }, published::add)
        }
        runCurrent()
        val expanded = async { verification.verify(identity, { reads++; disk.await(); setOf(second) }, published::add) }
        runCurrent()
        verification.invalidate(identity.bookUrl)
        disk.complete(Unit)
        assertEquals(setOf(second), full.await())
        assertEquals(full.await(), expanded.await())
        assertTrue(published.all { it == setOf(second) })
    }

    @Test
    fun aWaitingCallerRechecksIfCacheWasDeletedBeforeItResumed() = runTest {
        val verification = BookCacheVerification()
        val disk = CompletableDeferred<Unit>()
        val published = mutableListOf<Set<BookChapterCacheInfo>>()
        val full = async {
            verification.verify(identity, { disk.await(); setOf(first) }) {
                published.add(it)
                launch { verification.invalidate(identity.bookUrl) }
            }
        }
        runCurrent()
        val expanded = async { verification.verify(identity, { emptySet() }, published::add) }
        runCurrent()
        disk.complete(Unit)
        assertEquals(setOf(first), full.await())
        assertEquals(emptySet<BookChapterCacheInfo>(), expanded.await())
        assertEquals(listOf(setOf(first), emptySet()), published)
    }

    @Test
    fun closingTheExpandedRowDoesNotCancelTheSharedScan() = runTest {
        val verification = BookCacheVerification()
        val disk = CompletableDeferred<Set<BookChapterCacheInfo>>()
        val full = async { verification.verify(identity, { disk.await() }, {}) }
        runCurrent()
        val expanded = async { verification.verify(identity, { error("Duplicate disk read") }, {}) }
        runCurrent()
        expanded.cancelAndJoin()
        assertTrue(verification.isVerifying(identity.bookUrl))
        disk.complete(setOf(first))
        assertEquals(setOf(first), full.await())
        assertFalse(verification.isVerifying(identity.bookUrl))
    }

    @Test
    fun newCatalogDoesNotJoinOldScanAndLaterRefreshReadsDiskAgain() = runTest {
        val verification = BookCacheVerification()
        val disk = CompletableDeferred<Unit>()
        val old = async { verification.verify(identity, { disk.await(); setOf(first) }, {}) }
        runCurrent()
        val newIdentity = identity.copy(chapterCount = 3)
        assertEquals(setOf(first, second), verification.verify(newIdentity, { setOf(first, second) }, {}))
        assertEquals(emptySet<BookChapterCacheInfo>(), verification.verify(newIdentity, { emptySet() }, {}))
        disk.complete(Unit)
        old.await()
    }
}
