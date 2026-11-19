package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ManagedChapterCacheDeletionTest {
    @get:Rule val temp = TemporaryFolder()
    private val store = BookImageFileStore { true }
    private val root get() = temp.root
    private val book get() = root.resolve("book")
    private val current get() = book.resolve("chapter/chapter.nb")
    private val legacy get() = book.resolve("chapter.nb")
    private val images get() = book.resolve("chapter/images")
    private val shared get() = book.resolve("images")
    private val subject = "0.hash"

    private fun delete(reused: Set<String> = emptySet()) = ManagedChapterCacheDeletion(store).delete(
        root, current, legacy, images, shared, subject, isComic = true, reused,
    )

    @Test fun `clears both text layouts and exclusive images plus their indexes`() {
        val page = images.resolve("page.jpg").makeFile()
        store.retainDownload(page, subject)
        val dim = images.resolve(".dimensions/page.jpg").makeFile()
        val text = listOf(current, legacy, File(current.path + ".ruby-v1"), File(legacy.path + ".ruby-v1"))
        text.forEach { it.makeFile() }
        assertEquals(ChapterCacheDeleteResult.Cleared, delete())
        (text + page + dim).forEach { assertFalse(it.exists()) }
        assertFalse(store.isExplicitDownload(page))
        assertEquals(ChapterCacheDeleteResult.Absent, delete())
    }

    @Test fun `only exclusive originals can be deleted while uncertain originals are kept`() {
        val exclusive = images.resolve("exclusive.jpg").makeFile()
        val multiple = images.resolve("multiple.jpg").makeFile()
        val online = images.resolve("online.jpg").makeFile()
        val located = images.resolve("located.jpg").makeFile()
        val uncertain = images.resolve("uncertain.jpg").makeFile()
        val legacyPage = shared.resolve("legacy.jpg").makeFile()
        current.makeFile()
        listOf(exclusive, multiple, located).forEach { store.retainDownload(it, subject) }
        store.retainDownload(multiple, "1.other")
        store.setOnline(online, true)
        images.resolve(".downloads/uncertain.jpg").makeFile().writeText("version=1\nchapter.$subject=explicit\nunknown=true")
        assertEquals(ChapterCacheDeleteResult.TextClearedImagesKept, delete(setOf(located.absolutePath)))
        assertFalse(current.exists())
        assertFalse(exclusive.exists())
        listOf(multiple, online, located, uncertain, legacyPage).forEach { assertTrue(it.isFile) }
        assertEquals(ChapterCacheDeleteResult.ImagesKept, delete(setOf(located.absolutePath)))
    }

    @Test fun `image only chapter can be cleared`() {
        val page = images.resolve("page.jpg").makeFile()
        store.retainDownload(page, subject)
        assertEquals(ChapterCacheDeleteResult.Cleared, delete())
        assertFalse(page.exists())
    }

    @Test fun `files are untouched if a reader or writer pins the chapter or any descendant`() {
        val text = current.makeFile()
        val old = legacy.makeFile()
        val page = images.resolve("page.jpg").makeFile()
        store.retainDownload(page, subject)
        listOf(current.parentFile!!, old, page, book, File(legacy.path + ".ruby-v1")).forEach { held ->
            store.pin(held).use {
                assertEquals(ChapterCacheDeleteResult.Busy, delete())
                assertTrue(text.isFile && old.isFile && page.isFile)
            }
        }
        assertEquals(ChapterCacheDeleteResult.Cleared, delete())
    }

    @Test fun `an unexpected directory counts as incomplete and is not recursively removed`() {
        val payload = current.resolve("keep").makeFile()
        val old = legacy.makeFile()
        assertEquals(ChapterCacheDeleteResult.PartlyCleared, delete())
        assertTrue(payload.isFile)
        assertFalse(old.exists())
        assertEquals(ChapterCacheDeleteResult.Failed, delete())
    }

    @Test fun `does not follow an unexpected chapter symlink`() {
        val outside = root.resolve("outside/chapter.nb").makeFile()
        book.mkdirs()
        Files.createSymbolicLink(current.parentFile!!.toPath(), outside.parentFile!!.toPath())
        assertEquals(ChapterCacheDeleteResult.Failed, delete())
        assertTrue(outside.isFile)
        assertTrue(Files.isSymbolicLink(current.parentFile!!.toPath()))
    }

    @Test fun `reader window is scoped to book and chapter and releases on navigation and close`() {
        val reader = ChapterCacheUse()
        val secondReader = ChapterCacheUse()
        fun attempt(book: String, index: Int) = ChapterCacheUse.deleteIfIdle(book, index) {
            ChapterCacheDeleteResult.Cleared
        }
        try {
            reader.retain("one", setOf(1, 2))
            secondReader.retain("one", setOf(2))
            assertEquals(ChapterCacheDeleteResult.Busy, attempt("one", 1))
            assertEquals(ChapterCacheDeleteResult.Busy, attempt("one", 2))
            assertEquals(ChapterCacheDeleteResult.Cleared, attempt("one", 3))
            assertEquals(ChapterCacheDeleteResult.Cleared, attempt("two", 2))
            reader.retain("one", setOf(3))
            assertEquals(ChapterCacheDeleteResult.Cleared, attempt("one", 1))
            assertEquals(ChapterCacheDeleteResult.Busy, attempt("one", 2))
            secondReader.close()
            assertEquals(ChapterCacheDeleteResult.Cleared, attempt("one", 2))
            assertEquals(ChapterCacheDeleteResult.Busy, attempt("one", 3))
        } finally {
            reader.close()
            secondReader.close()
        }
        assertEquals(ChapterCacheDeleteResult.Cleared, attempt("one", 3))
    }

    private fun File.makeFile() = apply { parentFile!!.mkdirs(); writeText("cache") }
}
