package io.legado.app.data.repository

import android.app.Application
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.domain.gateway.NasLibraryGateway
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.gateway.NasUploadSource
import io.legado.app.domain.model.*
import io.legado.app.domain.model.settings.NasSettings
import io.legado.app.domain.usecase.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.Proxy
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NasLocalBookUploadRepositoryTest {
    @get:Rule val files = TemporaryFolder()
    private val settings = NasSettings("https://nas.example", "test-token", true, true)
    private val api = Library()
    private val gateway = object : NasSettingsGateway {
        override val currentSettings get() = this@NasLocalBookUploadRepositoryTest.settings
        override val settings get() = flowOf(currentSettings)
        override suspend fun update(transform: (NasSettings) -> NasSettings) = error("unused")
    }
    @Before fun init() { RuntimeEnvironment.getApplication().injectAsAppCtx() }
    private suspend fun upload(content: ByteArray): NasBookUploadOutcome {
        val app = RuntimeEnvironment.getApplication()
        val file = files.newFile("书籍.txt")
        file.writeBytes(content)
        val book = Book(bookUrl = file.absolutePath, originName = "书籍.txt", name = "书籍", type = BookType.local or BookType.text)
        try {
            return NasLocalBookUploadRepository(app, UploadNasBookUseCase(api, gateway)).upload(book, settings) {}
        } finally {
            assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("nas-book-upload-") })
        }
    }
    @Test fun hashesAndStreamsSameLocalBytesAndCleansTemporaryFile() = runBlocking {
        val content = ByteArray(150_000) { (it % 251).toByte() }
        assertFalse(upload(content).alreadyExists)
        assertArrayEquals(content, api.bytes.toByteArray())
        assertEquals("书籍.txt", api.fileName)
        assertTrue(api.maxChunk <= 64 * 1024)
    }
    @Test fun matchingHashSkipsBodyAndCleansTemporaryFile() = runBlocking {
        val content = "known content".toByteArray()
        api.knownHash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        assertTrue(upload(content).alreadyExists)
        assertEquals(0, api.bytes.size())
    }
    @Test fun failedUploadCleansTemporaryFile() = runBlocking {
        api.failUpload = true
        try { upload("test".toByteArray()); fail("Expected failure") } catch (_: IOException) { }
    }
    @Test fun emptyFileRejectedAndCleaned() = runBlocking {
        try { upload(byteArrayOf()); fail("Expected failure") } catch (error: NasBookUploadException) {
            assertEquals(NasBookUploadError.InvalidFile, error.reason)
        }
    }
    @Test fun fileNameCannotEscapeTargetDirectory() {
        assertEquals("book.txt", safeNasBookFileName("../../book.txt"))
        assertEquals("book.txt", safeNasBookFileName("..\\book.txt"))
        assertEquals("a_b.txt", safeNasBookFileName("a:b.txt"))
    }
    private class Library : NasLibraryGateway by unsupportedGateway() {
        var knownHash = ""
        var failUpload = false
        var maxChunk = 0
        var fileName = ""
        val bytes = ByteArrayOutputStream()
        override fun normalizeUrl(raw: String) = raw
        override suspend fun checkConnection(settings: NasSettings) = NasConnection(NasHealth("ok"),
            NasCapabilities(features = setOf("upload"), defaultUploadDirectory = "books"))
        override suspend fun listBooks(page: Int, pageSize: Int, search: String?, directoryPath: String?,
            scrapeStatus: String?, manualConfirmed: Boolean?, sortBy: String, sortOrder: String,
            settings: NasSettings) = if (knownHash.isBlank()) NasBookPage(emptyList(), 1, 200, 0) else
                NasBookPage(listOf(NasBook("existing", relativePath = "other/renamed.txt", contentHash = knownHash)), 1, 200, 1)
        override suspend fun uploadBook(source: NasUploadSource, fileName: String, contentLength: Long?,
            duplicatePolicy: String, title: String, author: String, intro: String, directoryPath: String,
            settings: NasSettings): NasUploadResult {
            this.fileName = fileName
            source.writeTo { data, offset, length ->
                maxChunk = maxOf(maxChunk, length)
                bytes.write(data, offset, length)
            }
            assertEquals(bytes.size().toLong(), contentLength)
            if (failUpload) throw IOException("offline")
            return NasUploadResult(uploaded = listOf(NasBook("new", relativePath = "books/$fileName")))
        }
    }
    companion object {
        private fun unsupportedGateway(): NasLibraryGateway = Proxy.newProxyInstance(
            NasLibraryGateway::class.java.classLoader, arrayOf(NasLibraryGateway::class.java)
        ) { _, method, _ -> error("Unexpected call: ${method.name}") } as NasLibraryGateway
    }
}
