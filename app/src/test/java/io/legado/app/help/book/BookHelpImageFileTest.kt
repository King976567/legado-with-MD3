package io.legado.app.help.book

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.cache.CacheDownloadRepository
import io.legado.app.model.ImageProvider
import io.legado.app.domain.gateway.DownloadCacheSettingsGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.model.settings.DownloadCacheSettings
import io.legado.app.domain.model.settings.OtherSettings
import kotlinx.coroutines.flow.flowOf
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
import org.robolectric.annotation.GraphicsMode
import splitties.init.injectAsAppCtx
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookHelpImageFileTest {
    private val book = Book(bookUrl = "book-image-contract", name = "Image contract")

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
        startKoin {
            modules(module {
                single<DownloadCacheSettingsGateway> {
                    object : DownloadCacheSettingsGateway {
                        override val currentSettings = DownloadCacheSettings()
                        override val settings = flowOf(currentSettings)
                        override suspend fun update(transform: (DownloadCacheSettings) -> DownloadCacheSettings) = Unit
                    }
                }
                single<OtherSettingsGateway> {
                    object : OtherSettingsGateway {
                        override val currentSettings = OtherSettings()
                        override val settings = flowOf(currentSettings)
                        override suspend fun update(transform: (OtherSettings) -> OtherSettings) = Unit
                    }
                }
            })
        }
    }

    @After
    fun tearDown() = stopKoin()

    private fun png(color: Int = Color.TRANSPARENT): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `cached export stream keeps its original alive until close`() {
        val chapter = BookChapter(bookUrl = book.bookUrl, title = "Export", index = 9)
        val src = "https://example.invalid/export-original"
        val bytes = png()
        BookHelp.writeImage(book, chapter, src, bytes)
        val file = BookHelp.getImage(book, chapter, src)
        try {
            BookHelp.openCachedImage(book, chapter, src).use { input ->
                BookHelp.clearCache(book)
                assertTrue(file.exists())
                assertArrayEquals(bytes, input.readBytes())
            }
            BookHelp.clearCache(book)
            assertFalse(file.exists())
            try {
                BookHelp.openCachedImage(book, chapter, src).close()
                error("A missing cached original must fail without downloading")
            } catch (_: IOException) {
                assertFalse(file.exists())
            }
        } finally {
            BookHelp.clearCache(book)
        }
    }

    @Test
    fun `forced refresh updates duplicate chapter images and preserves explicit download references`() = runBlocking {
        val fresh = png(Color.BLUE)
        val src = "data:image/png;base64,${Base64.encodeToString(fresh, Base64.NO_WRAP)}"
        val first = BookChapter(bookUrl = book.bookUrl, title = "First", index = 0)
        val second = BookChapter(bookUrl = book.bookUrl, title = "Second", index = 1)
        try {
            for (chapter in listOf(first, second)) {
                BookHelp.writeImage(book, chapter, src, png(Color.RED))
                assertTrue(BookHelp.saveImage(null, book, src, chapter, explicitDownloadChapter = "${chapter.index}.audit"))
                val copy = BookHelp.getImage(book, chapter, src)
                val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                ImageProvider.bitmapLruCache.put(copy.absolutePath, bitmap)
                assertTrue(ImageProvider.bitmapLruCache[copy.absolutePath] != null)
            }
            val selected = BookHelp.getImage(book, src)
            assertTrue(ImageProvider.refreshImage(book, src, null))
            assertTrue(selected.isFile)
            for (chapter in listOf(first, second)) {
                val copy = BookHelp.getImage(book, chapter, src)
                assertArrayEquals(fresh, copy.readBytes())
                val index = java.io.File(java.io.File(copy.parentFile, ".downloads"), copy.name)
                val properties = java.util.Properties().apply { index.inputStream().use { load(it) } }
                assertEquals("explicit", properties.getProperty("chapter.${chapter.index}.audit"))
                assertEquals(null, ImageProvider.bitmapLruCache[copy.absolutePath])
            }
        } finally {
            ImageProvider.bitmapLruCache.evictAll()
            BookHelp.clearCache(book)
        }
    }

    @Test
    fun `failed forced refresh keeps the last valid original`() = runBlocking {
        val src = "data:image/png;base64,bm90IGFuIGltYWdl"
        val original = png(Color.RED)
        try {
            BookHelp.writeImage(book, src, original)
            assertFalse(ImageProvider.refreshImage(book, src, null))
            assertArrayEquals(original, BookHelp.getImage(book, src).readBytes())
        } finally {
            BookHelp.clearCache(book)
        }
    }

    @Test
    fun `text reader finds chapter downloaded images offline and refresh keeps the same path`() = runBlocking {
        val src = "https://invalid.example/chapter-image.jpg"
        val chapter = BookChapter(bookUrl = book.bookUrl, title = "Downloaded")
        val bytes = png()
        try {
            BookHelp.writeImage(book, chapter, src, bytes)
            val downloaded = BookHelp.getImage(book, chapter, src)
            assertEquals(downloaded, ImageProvider.cacheImage(book, src, null))
            val bitmap = ImageProvider.getImage(book, src, 4)
            assertFalse(ImageProvider.isErrorBitmap(bitmap))
            assertEquals(4, bitmap.width)
            assertArrayEquals(bytes, BookHelp.getImage(book, src).readBytes())
            downloaded.delete()
            assertEquals(downloaded, BookHelp.getImage(book, src))
            BookHelp.writeImage(book, src, bytes)
            assertTrue(downloaded.isFile)
        } finally {
            ImageProvider.bitmapLruCache.evictAll()
            BookHelp.clearCache(book)
        }
    }

    @Test
    fun `chapter reads reuse legacy images and their explicit download references`() = runBlocking {
        val src = "https://invalid.example/legacy.jpg"
        val chapter = BookChapter(bookUrl = book.bookUrl, url = "https://invalid.example/chapter", title = "Legacy")
        val bytes = png()
        try {
            BookHelp.writeImage(book, src, bytes)
            assertTrue(BookHelp.saveImage(null, book, src, explicitDownloadChapter = "legacy-reference"))
            val legacy = BookHelp.getImage(book, src)
            assertEquals(legacy, BookHelp.getImage(book, chapter, src))
            val (image, lease) = BookHelp.acquireReadingImage(
                null, book, src, chapterSubject = chapter.getFileName().substringBeforeLast('.'),
            )
            lease.use { assertArrayEquals(bytes, image.readBytes()) }
            val index = java.io.File(java.io.File(image.parentFile, ".downloads"), image.name)
            val properties = java.util.Properties().apply { index.inputStream().use { load(it) } }
            assertEquals("explicit", properties.getProperty("chapter.legacy-reference"))
        } finally {
            BookHelp.clearCache(book)
        }
    }

    @Test
    fun `explicit download promotes the same original and retains chapter references`() = runBlocking {
        val src = "https://invalid.example/explicit.jpg"
        val image = BookHelp.getImage(book, src)
        val index = java.io.File(java.io.File(image.parentFile, ".downloads"), image.name)
        try {
            val bytes = png()
            BookHelp.writeImage(book, src, bytes)
            var transfers = 0
            assertTrue(BookHelp.saveImage(null, book, src, onDownload = { transfers++ }, explicitDownloadChapter = "2.hash"))
            assertTrue(BookHelp.saveImage(null, book, src, onDownload = { transfers++ }, explicitDownloadChapter = "3.hash"))
            // 自动离线/阅读复用不会降级显式下载记录。
            assertTrue(BookHelp.saveImage(null, book, src))
            val properties = java.util.Properties().apply { index.inputStream().use { load(it) } }
            assertEquals("explicit", properties.getProperty("chapter.2.hash"))
            assertEquals("explicit", properties.getProperty("chapter.3.hash"))
            assertEquals(0, transfers)
            assertArrayEquals(bytes, image.readBytes())
        } finally {
            BookHelp.clearCache(book)
        }
    }

    @Test
    fun `book rename defers physical migration until image reader releases its file`() = runBlocking {
        val renamed = book.copy(name = "Renamed image contract")
        val src = "https://invalid.example/rename.jpg"
        val original = BookHelp.getImage(book, src)
        val destination = BookHelp.getImage(renamed, src)
        BookHelp.clearCache(book)
        BookHelp.clearCache(renamed)
        BookHelp.writeImage(book, src, png())
        val lease = BookHelp.pinImageFile(original)
        try {
            BookHelp.updateCacheFolder(book, renamed)
            assertTrue(original.exists())
            assertFalse(destination.exists())
            lease.close()
            withTimeout(5_000) { while (!destination.exists()) delay(10) }
            assertFalse(original.exists())
        } finally {
            lease.close()
            BookHelp.clearCache(book)
            BookHelp.clearCache(renamed)
        }
    }

    @Test
    fun `chapter download upgrades online original without creating a second image`() = runBlocking {
        val bytes = png()
        val src = "data:image/png;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        val expected = BookHelp.getImage(book, src)
        val marker = java.io.File(java.io.File(expected.parentFile, ".online"), expected.name)
        expected.delete()
        marker.delete()
        var lease: java.io.Closeable? = null
        try {
            val (image, acquired) = BookHelp.acquireReadingImage(null, book, src)
            lease = acquired
            assertEquals(expected, image)
            assertTrue(marker.exists())
            assertTrue(BookHelp.saveImage(null, book, src))
            assertFalse(marker.exists())
            assertArrayEquals(bytes, image.readBytes())
        } finally {
            lease?.close()
            BookHelp.clearCache(book)
        }
    }

    @Test
    fun `failed page does not fill progress and continuing only fetches the missing page`() = runBlocking {
        val comic = book.copy(type = BookType.image)
        val bytes = png()
        val repository = CacheDownloadRepository()
        MockWebServer().use { server ->
            server.start()
            val chapter = BookChapter(
                bookUrl = comic.bookUrl, url = server.url("/chapter").toString(), title = "Comic",
            )
            val source = BookSource(bookSourceUrl = server.url("/").toString())
            val page1 = server.url("/1.png").toString()
            val page2 = server.url("/2.png").toString()
            fun imageResponse() = MockResponse.Builder().body(Buffer().write(bytes)).build()
            server.enqueue(imageResponse())
            server.enqueue(MockResponse.Builder().code(503).body("Please try again").build())
            server.enqueue(imageResponse())
            try {
                BookHelp.saveText(
                    comic, chapter, "<img src=\"$page1\">\n<img src=\"$page2\">", notifyContentChange = false,
                )
                val firstProgress = mutableListOf<Pair<Int, Int>>()
                val failure = runCatching {
                    repository.saveCachedImagesAwait(source, comic, chapter, { done, total ->
                        firstProgress.add(done to total)
                    }, explicitDownload = true)
                }.exceptionOrNull()
                assertTrue(failure is NoStackTraceException)
                assertFalse(BookHelp.hasExplicitImageContent(comic, chapter))
                assertArrayEquals(bytes, BookHelp.getImage(comic, chapter, page1).readBytes())
                assertFalse(BookHelp.getImage(comic, chapter, page2).exists())
                assertEquals(listOf(0 to 2, 1 to 2), firstProgress)

                val continuedProgress = mutableListOf<Pair<Int, Int>>()
                repository.saveCachedImagesAwait(source, comic, chapter, { done, total ->
                    continuedProgress.add(done to total)
                }, explicitDownload = true)
                assertTrue(BookHelp.hasExplicitImageContent(comic, chapter))
                assertArrayEquals(bytes, BookHelp.getImage(comic, chapter, page2).readBytes())
                assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), continuedProgress)
                assertEquals(3, server.requestCount)
                assertEquals(listOf("/1.png", "/2.png", "/2.png"), List(3) {
                    server.takeRequest().url.encodedPath
                })
            } finally {
                BookHelp.clearCache(comic)
            }
        }
    }

    @Test
    fun `missing download index prevents full progress even when all images are valid`() = runBlocking {
        val comic = book.copy(type = BookType.image)
        val chapter = BookChapter(bookUrl = comic.bookUrl, url = "https://example.invalid/chapter", title = "Comic")
        val page1 = "https://example.invalid/page1.png"
        val page2 = "https://example.invalid/page2.png"
        val chapterDir = requireNotNull(BookHelp.getChapterContentFile(comic, chapter).parentFile)
        val firstImage = BookHelp.getImage(comic, chapter, page1)
        val firstIndex = firstImage.parentFile!!.resolve(BookImageFileStore.DOWNLOAD_INDEX_DIRECTORY)
            .resolve(firstImage.name)
        val progress = mutableListOf<Pair<Int, Int>>()
        try {
            BookHelp.saveText(
                comic, chapter, "<img src=\"$page1\">\n<img src=\"$page2\">", notifyContentChange = false,
            )
            BookHelp.writeImage(comic, chapter, page1, png())
            BookHelp.writeImage(comic, chapter, page2, png())
            val failure = runCatching {
                CacheDownloadRepository().saveCachedImagesAwait(BookSource(), comic, chapter, { done, total ->
                    progress.add(done to total)
                    if (done == 1) {
                        assertFalse(BookHelp.deleteChapterCacheEntryIfIdle(chapterDir))
                        // 模拟索引异常消失，确保收尾不只检测原图文件。
                        assertTrue(firstIndex.delete())
                    }
                }, explicitDownload = true)
            }.exceptionOrNull()
            assertTrue(failure is NoStackTraceException)
            assertTrue(BookHelp.hasImageContent(comic, chapter))
            assertFalse(BookHelp.hasExplicitImageContent(comic, chapter))
            assertEquals(listOf(0 to 2, 1 to 2), progress)
            assertTrue(BookHelp.deleteChapterCacheEntryIfIdle(chapterDir))
        } finally {
            BookHelp.clearCache(comic)
        }
    }

    @Test
    fun `valid cached original is reused without resolving its network URL`() = runBlocking {
        val src = "https://invalid.example/original.jpg,{\"headers\":{\"Referer\":\"original\"}}"
        val image = BookHelp.getImage(book, src)
        try {
            val bytes = png()
            BookHelp.writeImage(book, src, bytes)
            assertTrue(BookHelp.saveImage(null, book, src))
            assertArrayEquals(bytes, image.readBytes())
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            image.inputStream().use { BitmapFactory.decodeStream(it, null, dimensions) }
            assertEquals(4, dimensions.outWidth)
            assertEquals(6, dimensions.outHeight)
        } finally {
            image.delete()
        }
    }

    @Test
    fun `invalid bytes are rejected without replacing the cached original`() {
        val src = "https://invalid.example/replacement.jpg"
        val image = BookHelp.getImage(book, src)
        try {
            val original = png()
            BookHelp.writeImage(book, src, original)
            try {
                BookHelp.writeImage(book, src, "not an image".toByteArray())
                error("Expected invalid image to fail")
            } catch (_: IOException) {
                assertArrayEquals(original, image.readBytes())
            }
        } finally {
            image.delete()
        }
    }

    @Test
    fun `valid SVG survives the shared file validation path`() {
        val src = "https://invalid.example/page.svg"
        val image = BookHelp.getImage(book, src)
        try {
            val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"8\" height=\"12\"><rect width=\"8\" height=\"12\"/></svg>".toByteArray()
            BookHelp.writeImage(book, src, svg)
            assertArrayEquals(svg, image.readBytes())
        } finally {
            image.delete()
        }
    }

    @Test
    fun `inline transfer replaces invalid legacy file with a validated original`() = runBlocking {
        val bytes = png()
        val src = "data:image/png;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        val image = BookHelp.getImage(book, src)
        try {
            image.parentFile!!.mkdirs()
            image.writeBytes("old broken image".toByteArray())
            assertTrue(BookHelp.saveImage(null, book, src))
            assertArrayEquals(bytes, image.readBytes())
            assertTrue(BookHelp.saveImage(null, book, src))
        } finally {
            image.delete()
        }
    }

    @Test
    fun `failed inline image cannot leave a file that the next attempt treats as ready`() = runBlocking {
        val src = "data:image/png;base64,bm90IGFuIGltYWdl"
        val image = BookHelp.getImage(book, src)
        try {
            image.delete()
            assertFalse(BookHelp.saveImage(null, book, src))
            assertTrue(AppLog.logs.first().second.contains("图片数据无效"))
            assertFalse(image.exists())
            assertFalse(BookHelp.saveImage(null, book, src))
            assertFalse(image.exists())
        } finally {
            image.delete()
        }
    }
}
