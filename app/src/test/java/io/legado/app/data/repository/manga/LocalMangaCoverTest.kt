package io.legado.app.data.repository.manga

import android.app.Application
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.model.localBook.LocalBook
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.io.File
import java.net.URI
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocalMangaCoverTest {
    @Before
    fun setup() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
    }

    @Test
    fun `explicit comic cover wins over the first page and survives reader cleanup`() {
        withComic { root, cache, book ->
            root.resolve("0001/0001.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
            root.resolve("cover.png").writeBytes(byteArrayOf(7, 8, 9))
            val destination = File(LocalBook.getCoverPath(book))
            try {
                LocalMangaLoader(cache).use { loader -> assertTrue(loader.ensureCover(book)) }
                assertTrue(book.coverUrl.orEmpty().startsWith("file:///"))
                assertEquals(destination.absoluteFile, File(URI(book.coverUrl)))
                assertArrayEquals(byteArrayOf(7, 8, 9), destination.readBytes())
                assertTrue(cache.listFiles().orEmpty().isEmpty())
            } finally {
                destination.delete()
            }
        }
    }

    @Test
    fun `comic without an explicit cover uses the first page in natural order`() {
        withComic { root, cache, book ->
            root.resolve("10.png").writeBytes(byteArrayOf(10))
            root.resolve("2.png").writeBytes(byteArrayOf(2))
            val destination = File(LocalBook.getCoverPath(book))
            try {
                LocalMangaLoader(cache).use { loader -> assertTrue(loader.ensureCover(book)) }
                assertArrayEquals(byteArrayOf(2), destination.readBytes())
            } finally {
                destination.delete()
            }
        }
    }

    @Test
    fun `existing NAS and user selected covers are retained`() {
        withComic { root, cache, book ->
            root.resolve("cover.png").writeBytes(byteArrayOf(7))
            LocalMangaLoader(cache).use { loader ->
                book.coverUrl = "https://nas.example/api/books/1/cover"
                assertFalse(loader.ensureCover(book))
                assertEquals("https://nas.example/api/books/1/cover", book.coverUrl)
                book.coverUrl = null
                book.customCoverUrl = "content://media/external/images/1"
                assertFalse(loader.ensureCover(book))
                assertEquals("content://media/external/images/1", book.customCoverUrl)
            }
        }
    }

    private inline fun withComic(action: (File, File, Book) -> Unit) {
        val root = Files.createTempDirectory("local-manga-cover").toFile()
        val cache = Files.createTempDirectory("local-manga-cover-cache").toFile()
        try {
            action(root, cache, Book(
                type = BookType.local or BookType.image,
                bookUrl = root.absolutePath,
                name = "Comic",
                originName = root.name,
            ))
        } finally {
            root.deleteRecursively()
            cache.deleteRecursively()
        }
    }
}
