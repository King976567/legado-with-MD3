package io.legado.app.data.repository

import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.model.NasBook
import io.legado.app.domain.model.NasCapabilities
import io.legado.app.domain.model.settings.NasSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class NasLibraryRepositoryTest {
    private val repository = NasLibraryRepository(FakeNasSettingsGateway())

    @Test
    fun normalizeUrlAddsSchemeAndStripsTrailingSlash() {
        assertEquals("http://nas.example:3100", repository.normalizeUrl(" nas.example:3100/// "))
        assertEquals("https://nas.example/library", repository.normalizeUrl("https://nas.example/library/"))
    }

    @Test
    fun normalizeUrlRejectsUnsupportedOrAmbiguousAddresses() {
        assertNull(repository.normalizeUrl("ftp://nas.example"))
        assertNull(repository.normalizeUrl("https://nas.example/api?token=secret"))
        assertNull(repository.normalizeUrl("https://nas.example/api#books"))
        assertNull(repository.normalizeUrl("https://user:secret@nas.example"))
        assertNull(repository.normalizeUrl("https://user@nas.example"))
        assertNull(repository.normalizeUrl(""))
    }

    @Test
    fun apiV1InputUsesTheGoServicesApiRoute() {
        assertEquals(
            "https://nas.example/api/books",
            repository.apiUrl("https://nas.example/api/v1", "books"),
        )
        assertEquals(
            "https://nas.example/api/books",
            repository.apiUrl("https://nas.example/api", "books"),
        )
        assertEquals(
            "https://nas.example/api/books",
            repository.apiUrl("https://nas.example", "books"),
        )
    }

    @Test
    fun resolveCoverUrlPrefixesNasOriginForIndexerRelativePaths() {
        assertEquals(
            "https://nas.example/covers/book.jpg",
            repository.resolveCoverUrl("https://nas.example/api", "/covers/book.jpg"),
        )
        assertEquals(
            "https://nas.example/covers/book.jpg",
            repository.resolveCoverUrl("https://nas.example/api/v1", "covers/book.jpg"),
        )
    }

    @Test
    fun resolveCoverUrlPreservesAbsoluteExternalCoverUrls() {
        assertEquals(
            "https://cdn.example/book.jpg",
            repository.resolveCoverUrl("https://nas.example/api", "https://cdn.example/book.jpg"),
        )
        assertEquals("/covers/book.jpg", repository.resolveCoverUrl(null, "/covers/book.jpg"))
    }

    @Test
    fun bookDisplayAndDirectoryUseStableFallbacks() {
        val book = NasBook(
            id = "1",
            relativePath = "history/fantasy/book.epub",
            fileName = "book.epub",
        )
        assertEquals("history/fantasy", book.directoryPath)
        assertEquals("book", book.displayTitle)
        assertEquals("", NasBook(id = "2").directoryPath)
    }

    @Test
    fun capabilitiesExposeDeclaredFeatures() {
        val capabilities = NasCapabilities(features = setOf("books", "download"))
        assertTrue(capabilities.supports("books"))
        assertFalse(capabilities.supports("upload"))
        assertFalse(capabilities.supportsIndexer)
    }

    @Test
    fun bearerHeaderUsesTheTrimmedNasToken() {
        assertEquals(
            mapOf("Authorization" to "Bearer local-token"),
            nasAuthorizationHeaders("  local-token  "),
        )
        assertTrue(nasAuthorizationHeaders("   ").isEmpty())
    }

    @Test
    fun bearerHeaderRejectsControlCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            nasAuthorizationHeaders("token\nInjected: value")
        }
    }

    @Test
    fun taskConflictParsesIndexerStatusEnvelopeAsFailure() {
        val error = repository.taskConflictException(
            """
            {
              "error": "indexer already running",
              "status": {"status":"running","total":12,"processed":4}
            }
            """.trimIndent(),
            "NAS 索引刷新失败",
        )

        assertEquals(409, error.statusCode)
        assertTrue(error.message!!.contains("indexer already running"))
        assertEquals("running", error.currentTask?.status)
        assertEquals(12, error.currentTask?.total)
        assertEquals(4, error.currentTask?.processed)
    }

    @Test
    fun taskConflictParsesScraperTaskEnvelopeAndUsesFallbackMessage() {
        val error = repository.taskConflictException(
            """
            {
              "task": {"id":"scrape-1","status":"processing","processed":2}
            }
            """.trimIndent(),
            "NAS 刮削提交失败",
        )

        assertEquals("NAS 刮削提交失败：已有相同任务正在运行", error.message)
        assertEquals("scrape-1", error.currentTask?.taskId)
        assertEquals("processing", error.currentTask?.status)
        assertEquals(2, error.currentTask?.processed)
    }

    private class FakeNasSettingsGateway : NasSettingsGateway {
        override val currentSettings = NasSettings()
        override val settings: Flow<NasSettings> = flowOf(currentSettings)
        override suspend fun update(transform: (NasSettings) -> NasSettings) = Unit
    }
}
