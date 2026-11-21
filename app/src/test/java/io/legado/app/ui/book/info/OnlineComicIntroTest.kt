package io.legado.app.ui.book.info

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineComicIntroTest {
    @Test
    fun discoveryIntroIsOnlyAFallbackForMissingCustomAndDetailText() {
        val book = Book(type = BookType.image, intro = " ", listIntro = "Discovery text")
        assertEquals("Discovery text", book.detailIntro())
        assertFalse(book.needsOnlineComicIntro())
        book.intro = "<md>Source details</md>"
        assertEquals("<md>Source details</md>", book.detailIntro())
        book.customIntro = "My notes"
        assertEquals("My notes", book.detailIntro())
    }

    @Test
    fun onlyOnlineComicsWithNoIntroNeedRecovery() {
        val book = Book(type = BookType.image)
        assertTrue(book.needsOnlineComicIntro())
        assertFalse(book.copy(type = BookType.text).needsOnlineComicIntro())
        assertFalse(book.copy(type = BookType.local or BookType.image).needsOnlineComicIntro())
    }

    @Test
    fun discoveryRecoveryRequiresMatchingUrlAndSource() {
        val book = Book(bookUrl = "https://example.invalid/comic", origin = "https://example.invalid", type = BookType.image)
        val result = SearchBook(bookUrl = book.bookUrl, origin = book.origin, intro = "Discovery text")
        assertEquals("Discovery text", book.matchingDiscoveryIntro(result))
        assertNull(book.matchingDiscoveryIntro(result.copy(origin = "https://other.invalid")))
        assertNull(book.matchingDiscoveryIntro(result.copy(bookUrl = "https://example.invalid/another")))
        assertNull(book.matchingDiscoveryIntro(result.copy(intro = " ")))
    }
}
