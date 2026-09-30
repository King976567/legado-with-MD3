package io.legado.app.data.repository

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CacheBookIdentityTest {
    @Test
    fun recentlyReadOrderDoesNotTriggerFullCacheScan() {
        val a = Book(bookUrl = "a", name = "A")
        val b = Book(bookUrl = "b", name = "B")
        assertEquals(cacheBookIdentities(listOf(a, b)), cacheBookIdentities(listOf(b, a)))
    }

    @Test
    fun readingProgressAndCoverChangesDoNotTriggerFullCacheScan() {
        val book = Book(bookUrl = "a", name = "Book", author = "Author", totalChapterNum = 10)
        val changed = book.copy(
            durChapterIndex = 8, durChapterPos = 100, durChapterTime = 1234,
            coverUrl = "new-cover", lastCheckTime = 9999,
        )
        assertEquals(cacheBookIdentity(book), cacheBookIdentity(changed))
    }

    @Test
    fun catalogAndCacheLocationChangesTriggerScan() {
        val book = Book(bookUrl = "a", name = "Book", author = "Author", totalChapterNum = 10)
        val before = cacheBookIdentity(book)
        assertNotEquals(before, cacheBookIdentity(book.copy(totalChapterNum = 11)))
        assertNotEquals(before, cacheBookIdentity(book.copy(group = 2)))
        assertNotEquals(before, cacheBookIdentity(book.copy(type = 1)))
        assertNotEquals(before, cacheBookIdentity(book.copy(bookUrl = "b")))
    }
}
