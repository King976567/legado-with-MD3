package io.legado.app.data.repository

import io.legado.app.domain.gateway.NasDownloadSink
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.gateway.NasUploadSource
import io.legado.app.domain.model.NasHttpException
import io.legado.app.domain.model.NasTaskConflictException
import io.legado.app.domain.model.NasWriteAccess
import io.legado.app.domain.model.settings.NasSettings
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** HTTP contract tests for the dedicated legado-nas-indexer API. */
class NasLibraryRepositoryHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: NasLibraryRepository
    private lateinit var settings: NasSettings

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = NasLibraryRepository(
            settingsGateway = FakeNasSettingsGateway(),
            httpClient = OkHttpClient.Builder()
                .connectTimeout(300, TimeUnit.MILLISECONDS)
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .writeTimeout(300, TimeUnit.MILLISECONDS)
                .callTimeout(700, TimeUnit.MILLISECONDS)
                .build(),
            requestTimeoutMs = 500L,
        )
        settings = NasSettings(
            apiUrl = server.url("/").toString().trimEnd('/'),
            apiToken = "test-token",
            connectionVerified = true,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun listBooksSendsBearerAndParsesPageSearchAndDeduplicatesItems() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                {
                  "items": [
                    {"id":"book-1","relativePath":"a/book.epub","fileName":"book.epub","title":"A"},
                    {"id":"book-1","relativePath":"a/book.epub","fileName":"book.epub","title":"A duplicate"},
                    {"id":"book-2","relativePath":"b/book.epub","fileName":"book.epub"}
                  ],
                  "page": 2,
                  "pageSize": 2,
                  "total": 5
                }
                """.trimIndent(),
            ),
        )

        val page = repository.listBooks(
            page = 2,
            pageSize = 2,
            search = "三体",
            directoryPath = "小说/中文",
            scrapeStatus = "pending",
            manualConfirmed = false,
            sortBy = "title",
            sortOrder = "ASC",
            settings = settings,
        )

        assertEquals(2, page.items.size)
        assertEquals("book-1", page.items[0].id)
        assertEquals("book-2", page.items[1].id)
        assertEquals(2, page.page)
        assertEquals(2, page.pageSize)
        assertEquals(5, page.total)

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("Bearer test-token", request.headers["Authorization"])
        val url = requireNotNull(request.url)
        assertEquals("/api/books", url.encodedPath)
        assertEquals("2", url.queryParameter("page"))
        assertEquals("2", url.queryParameter("pageSize"))
        assertEquals("三体", url.queryParameter("search"))
        assertEquals("小说/中文", url.queryParameter("directoryPath"))
        assertEquals("pending", url.queryParameter("scrapeStatus"))
        assertEquals("false", url.queryParameter("manualConfirmed"))
        assertEquals("title", url.queryParameter("sortBy"))
        assertEquals("ASC", url.queryParameter("sortOrder"))
    }

    @Test
    fun checkConnectionValidatesHealthCapabilitiesBooksAndWriteAccess() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"status":"ok","service":"legado-nas-indexer","version":"1","authRequired":true}""",
            ),
        )
        server.enqueue(
            jsonResponse(
                """
                {
                  "features":["books","download","upload","indexer","scraper","libraryDirectories"],
                  "service":"legado-nas-indexer",
                  "version":"1.2",
                  "defaultUploadDirectory":"books",
                  "paths":{"books":{"configured":true,"exists":true,"isDir":true,"readable":true,"writable":false}}
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(jsonResponse("""{"items":[],"page":1,"pageSize":1,"total":0}"""))
        server.enqueue(MockResponse.Builder().code(403).body("forbidden").build())

        val connection = repository.checkConnection(settings)

        assertEquals("ok", connection.health.status)
        assertEquals("legado-nas-indexer", connection.health.service)
        assertTrue(connection.capabilities.supports("books"))
        assertTrue(connection.capabilities.supportsIndexer)
        assertEquals("books", connection.capabilities.defaultUploadDirectory)
        assertFalse(connection.capabilities.paths["books"]!!.writable)
        assertEquals(NasWriteAccess.DENIED, connection.writeAccess)
        assertEquals(4, server.requestCount)
        assertEquals("/health", requireNotNull(takeRequest().url).encodedPath)
        assertEquals("/api/capabilities", requireNotNull(takeRequest().url).encodedPath)
        assertEquals("/api/books", requireNotNull(takeRequest().url).encodedPath)
        assertEquals("/api/books/confirm", requireNotNull(takeRequest().url).encodedPath)
    }

    @Test
    fun unauthorizedResponseIsTypedAndTokenIsOnlySentAsBearerHeader() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("unauthorized").build())

        val error = try {
            repository.listBooks(settings = settings)
            throw AssertionError("expected 401")
        } catch (expected: NasHttpException) {
            expected
        }

        assertEquals(401, error.statusCode)
        assertTrue(error.isUnauthorized)
        assertFalse(error.message.orEmpty().contains("test-token"))
        val request = takeRequest()
        assertEquals("Bearer test-token", request.headers["Authorization"])
        assertFalse(request.target.contains("test-token"))
    }

    @Test
    fun forbiddenResponseIsTypedForWriteOperations() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).body("forbidden").build())

        val error = try {
            repository.updateMetadata("book-1", "title", "author", "intro", settings)
            throw AssertionError("expected 403")
        } catch (expected: NasHttpException) {
            expected
        }

        assertEquals(403, error.statusCode)
        assertTrue(error.isForbidden)
        val request = takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("Bearer test-token", request.headers["Authorization"])
    }

    @Test
    fun taskConflictPreservesCurrentTaskDetails() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"error":"indexer already running","status":{"status":"running","total":12,"processed":4}}""",
                code = 409,
            ),
        )

        val error = try {
            repository.refreshIndex(settings)
            throw AssertionError("expected conflict")
        } catch (expected: NasTaskConflictException) {
            expected
        }

        assertEquals(409, error.statusCode)
        assertEquals("running", error.currentTask?.status)
        assertEquals(12, error.currentTask?.total)
        assertEquals(4, error.currentTask?.processed)
        assertTrue(error.message.orEmpty().contains("indexer already running"))
    }

    @Test
    fun detailAndScrapePathsEncodeBookIdsAndDirectoryQueries() = runBlocking {
        server.enqueue(jsonResponse("""{"id":"book id/中文","fileName":"book.epub"}"""))
        val detail = repository.detail("book id/中文", settings)
        assertEquals("book id/中文", detail.id)

        server.enqueue(jsonResponse("""{"task":{"id":"scrape-1","status":"queued"}}"""))
        repository.scrapeAllPending("小说/中文", settings)

        val detailRequest = takeRequest()
        assertEquals("/api/books/book%20id%2F%E4%B8%AD%E6%96%87", requireNotNull(detailRequest.url).encodedPath)
        assertEquals("Bearer test-token", detailRequest.headers["Authorization"])
        val scrapeRequest = takeRequest()
        assertEquals("POST", scrapeRequest.method)
        assertEquals("/api/scraper/scrape/all-pending", requireNotNull(scrapeRequest.url).encodedPath)
        assertEquals("小说/中文", requireNotNull(scrapeRequest.url).queryParameter("directoryPath"))
    }

    @Test
    fun uploadUsesMultipartAndStreamsSourceWithoutLeakingToken() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"uploaded":[{"id":"uploaded-1","fileName":"book.epub","relativePath":"books/book.epub"}],"duplicates":[],"skipped":[],"overwritten":[],"failed":[]}""",
            ),
        )
        val content = "epub-content".toByteArray()
        val result = repository.uploadBook(
            source = NasUploadSource { sink ->
                sink.write(content, 0, content.size)
            },
            fileName = "book.epub",
            contentLength = content.size.toLong(),
            duplicatePolicy = "skip",
            title = "Book",
            author = "Author",
            intro = "Intro",
            directoryPath = "folder/中文",
            settings = settings,
        )

        assertEquals("uploaded-1", result.primaryBook?.id)
        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/books/upload", requireNotNull(request.url).encodedPath)
        assertTrue(request.headers["Content-Type"].orEmpty().startsWith("multipart/form-data"))
        assertEquals("Bearer test-token", request.headers["Authorization"])
        val body = requireNotNull(request.body).utf8()
        assertTrue(body.contains("book.epub"))
        assertTrue(body.contains("folder/中文"))
        assertTrue(body.contains("skip"))
        assertTrue(body.contains("epub-content"))
        assertFalse(body.contains("test-token"))
    }

    @Test
    fun downloadStreamsResponseBodyToSink() = runBlocking {
        server.enqueue(MockResponse.Builder().body("downloaded book").build())
        val received = StringBuilder()
        repository.downloadBook(
            id = "book-1",
            sink = NasDownloadSink { bytes, offset, length ->
                received.append(String(bytes, offset, length))
            },
            settings = settings,
        )

        assertEquals("downloaded book", received.toString())
        val request = takeRequest()
        assertEquals("/api/books/book-1/download", requireNotNull(request.url).encodedPath)
        assertEquals("Bearer test-token", request.headers["Authorization"])
    }

    @Test
    fun timeoutIsBoundedAndOfflineServerFailsAsIOException() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        try {
            repository.listBooks(settings = settings)
            throw AssertionError("expected timeout")
        } catch (_: TimeoutCancellationException) {
            // Repository timeout is intentionally shorter than the client timeout.
        } catch (_: IOException) {
            // OkHttp may surface cancellation as an I/O failure on this platform.
        }

        server.close()
        try {
            repository.listBooks(settings = settings)
            throw AssertionError("expected offline failure")
        } catch (_: IOException) {
            // Expected: MockWebServer is no longer listening.
        } catch (_: TimeoutCancellationException) {
            // Also acceptable when the platform delays connection failure.
        }
    }

    private fun jsonResponse(body: String, code: Int = 200): MockResponse =
        MockResponse.Builder()
            .code(code)
            .body(body)
            .build()

    private fun takeRequest() = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))

    private class FakeNasSettingsGateway : NasSettingsGateway {
        override val currentSettings: NasSettings = NasSettings()
        override val settings: Flow<NasSettings> = flowOf(currentSettings)
        override suspend fun update(transform: (NasSettings) -> NasSettings) = Unit
    }
}
