package io.legado.app.data.repository.manga

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.domain.gateway.DownloadCacheSettingsGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.model.NasComicUploadException
import io.legado.app.domain.model.NasComicUploadFailure
import io.legado.app.domain.model.settings.DownloadCacheSettings
import io.legado.app.domain.model.settings.OtherSettings
import io.legado.app.help.book.BookHelp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import splitties.init.injectAsAppCtx
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NasComicUploadWorkerCacheTest {
    private val book = Book(bookUrl = "nas-worker-cache", name = "NAS worker cache")
    private val source = BookSource()
    private val first = BookChapter(bookUrl = book.bookUrl, url = "chapter-1", title = "First", index = 0)
    private val second = BookChapter(bookUrl = book.bookUrl, url = "chapter-2", title = "Second", index = 1)

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
        BookHelp.clearCache(book)
    }

    @After
    fun tearDown() {
        BookHelp.clearCache(book)
        stopKoin()
    }

    @Test
    fun `same URL in different chapters uses each chapter image without downloading`() = runBlocking {
        val url = "https://invalid.example/shared-page.png"
        val firstBytes = png(Color.RED)
        val secondBytes = png(Color.BLUE)
        BookHelp.writeImage(book, first, url, firstBytes)
        BookHelp.writeImage(book, second, url, secondBytes)

        NasComicImageLeases().use { leases ->
            val firstPage = acquireNasComicPageImage(source, book, first, url, leases)
            val secondPage = acquireNasComicPageImage(source, book, second, url, leases)

            assertFalse(firstPage.downloaded)
            assertFalse(secondPage.downloaded)
            assertTrue(firstPage.file != secondPage.file)
            assertArrayEquals(firstBytes, firstPage.file.readBytes())
            assertArrayEquals(secondBytes, secondPage.file.readBytes())
        }
    }

    @Test
    fun `legacy cache is reused without changing explicit download references`() = runBlocking {
        val url = "https://invalid.example/legacy-page.png"
        val bytes = png(Color.RED)
        BookHelp.writeImage(book, url, bytes)
        assertTrue(BookHelp.saveImage(source, book, url, explicitDownloadChapter = "legacy-chapter"))
        val legacy = BookHelp.getImage(book, url)
        val reference = File(File(legacy.parentFile, ".downloads"), legacy.name)
        val before = reference.readBytes()

        NasComicImageLeases().use { leases ->
            val page = acquireNasComicPageImage(source, book, first, url, leases)
            assertEquals(legacy, page.file)
            assertFalse(page.downloaded)
            assertArrayEquals(bytes, page.file.readBytes())
            assertArrayEquals(before, reference.readBytes())
        }
    }

    @Test
    fun `invalid existing image is fetched and validated instead of counted as cache reuse`() = runBlocking {
        val bytes = png(Color.BLUE)
        val url = inlineImage(bytes)
        val target = BookHelp.getImage(book, first, url)
        target.parentFile!!.mkdirs()
        target.writeText("not an image")

        NasComicImageLeases().use { leases ->
            val page = acquireNasComicPageImage(source, book, first, url, leases)
            assertTrue(page.downloaded)
            assertEquals(target, page.file)
            assertArrayEquals(bytes, page.file.readBytes())
            assertFalse(acquireNasComicPageImage(source, book, first, url, leases).downloaded)
        }
    }

    @Test
    fun `all acquired pages remain pinned throughout packaging and migrate after it`() = runBlocking {
        val url = "https://invalid.example/packing-page.png"
        val bytes = png(Color.RED)
        val renamed = book.copy(name = "Renamed worker cache")
        BookHelp.writeImage(book, first, url, bytes)
        BookHelp.writeImage(book, second, url, bytes)
        val oldPages = listOf(first, second).map { BookHelp.getImage(book, it, url) }
        val newPages = listOf(first, second).map { BookHelp.getImage(renamed, it, url) }
        val output = File(RuntimeEnvironment.getApplication().cacheDir, "nas-worker-pins.cbz")
        try {
            NasComicImageLeases().use { leases ->
                val pages = listOf(first, second).map { chapter ->
                    acquireNasComicPageImage(source, book, chapter, url, leases).file
                }
                val archive = NasComicCbzBuilder.build(
                    output = output,
                    title = book.name,
                    author = "",
                    summary = "",
                    chapters = pages.mapIndexed { index, page ->
                        NasComicArchiveChapter("Chapter $index", listOf(page))
                    },
                    maxBytes = 0,
                ) { _, _, _, _ ->
                    BookHelp.updateCacheFolder(book, renamed)
                    assertTrue(oldPages.all(File::isFile))
                    assertFalse(newPages.any(File::exists))
                }
                ZipFile(archive.file).use { zip ->
                    assertEquals(3, zip.size())
                }
                assertTrue(oldPages.all(File::isFile))
            }
            withTimeout(5_000) {
                while (newPages.any { !it.isFile }) delay(10)
            }
            assertTrue(oldPages.none(File::exists))
        } finally {
            output.delete()
            BookHelp.clearCache(book)
            BookHelp.clearCache(renamed)
        }
    }

    @Test
    fun `failed page acquisition releases earlier pages and does not permit partial packaging`() = runBlocking {
        val url = "https://invalid.example/valid-first.png"
        BookHelp.writeImage(book, first, url, png(Color.RED))
        val target = BookHelp.getImage(book, first, url)
        try {
            NasComicImageLeases().use { leases ->
                acquireNasComicPageImage(source, book, first, url, leases)
                acquireNasComicPageImage(source, book, second, inlineImage("invalid".toByteArray()), leases)
                error("Invalid image must stop the archive")
            }
            error("Expected image failure")
        } catch (error: NasComicUploadException) {
            assertEquals(NasComicUploadFailure.ImageFailed, error.reason)
            assertFalse(error.retryable)
        }
        BookHelp.clearCache(book)
        assertFalse(target.exists())
    }

    @Test
    fun `packaging failure releases pins and removes the incomplete archive`() = runBlocking {
        val url = "https://invalid.example/oversized-page.png"
        BookHelp.writeImage(book, first, url, png(Color.RED))
        val renamed = book.copy(name = "Failed worker cache")
        val target = BookHelp.getImage(book, first, url)
        val movedTarget = BookHelp.getImage(renamed, first, url)
        val output = File(RuntimeEnvironment.getApplication().cacheDir, "nas-worker-too-large.cbz")
        try {
            NasComicImageLeases().use { leases ->
                val page = acquireNasComicPageImage(source, book, first, url, leases)
                BookHelp.updateCacheFolder(book, renamed)
                assertTrue(target.isFile)
                NasComicCbzBuilder.build(
                    output = output,
                    title = book.name,
                    author = "",
                    summary = "",
                    chapters = listOf(NasComicArchiveChapter(first.title, listOf(page.file))),
                    maxBytes = 1,
                )
                error("Expected size limit to stop packaging")
            }
            error("Expected archive failure")
        } catch (error: NasComicArchiveException) {
            assertEquals(NasComicArchiveError.TooLarge, error.reason)
        } finally {
            output.delete()
        }
        assertFalse(File(output.parentFile, output.name + ".partial").exists())
        withTimeout(5_000) {
            while (!movedTarget.isFile) delay(10)
        }
        assertFalse(target.exists())
        BookHelp.clearCache(renamed)
    }

    @Test
    fun `cancellation releases page leases and repeated close is harmless`() = runBlocking {
        val url = "https://invalid.example/cancel-page.png"
        BookHelp.writeImage(book, first, url, png(Color.RED))
        val target = BookHelp.getImage(book, first, url)
        val renamed = book.copy(name = "Cancelled worker cache")
        val movedTarget = BookHelp.getImage(renamed, first, url)
        val leases = NasComicImageLeases()
        try {
            leases.use {
                acquireNasComicPageImage(source, book, first, url, it)
                BookHelp.updateCacheFolder(book, renamed)
                assertTrue(target.isFile)
                throw CancellationException("cancelled before packaging")
            }
        } catch (_: CancellationException) {
            leases.close()
        }
        withTimeout(5_000) {
            while (!movedTarget.isFile) delay(10)
        }
        assertFalse(target.exists())
        BookHelp.clearCache(renamed)
    }

    private fun png(color: Int): ByteArray {
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

    private fun inlineImage(bytes: ByteArray): String =
        "data:image/png;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
}
