package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.NasLibraryGateway
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.gateway.NasUploadSource
import io.legado.app.domain.model.*
import io.legado.app.domain.model.settings.NasSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

class UploadNasBookUseCaseTest {
    private val settings = Settings()
    private val api = Library()
    private val useCase = UploadNasBookUseCase(api, settings)
    private val stages = mutableListOf<NasBookUploadStage>()
    private val hash = "a".repeat(64)
    private suspend fun upload() = useCase.execute(settings.currentSettings,
        NasUploadSource { it.write(byteArrayOf(1, 2), 0, 2) },
        "book.txt", 2, hash, "Book", "Author", "Intro", stages::add)

    @Test fun disabledOrUnverifiedNeverCallsNetwork() = runBlocking {
        settings.settings.value = settings.currentSettings.copy(showHomeCard = false)
        assertEquals(NasBookUploadError.Unavailable, failure().reason)
        settings.settings.value = settings.currentSettings.copy(showHomeCard = true, connectionVerified = false)
        assertEquals(NasBookUploadError.Unavailable, failure().reason)
        assertEquals(0, api.checks)
        assertEquals(0, api.uploads)
    }

    @Test fun contentHashMatchesRenamedFileOnLaterPageWithoutUploading() = runBlocking {
        api.pages = listOf(NasBookPage(listOf(NasBook("1")), 1, 1, 2),
            NasBookPage(listOf(NasBook("2", relativePath = "other/renamed.epub", contentHash = hash.uppercase())), 2, 1, 2))
        val result = upload()
        assertTrue(result.alreadyExists)
        assertEquals("other/renamed.epub", result.path)
        assertEquals(listOf(1, 2), api.requestedPages)
        assertEquals(0, api.uploads)
    }

    @Test fun occupiedTargetPathSkipsEvenWhenContentDiffers() = runBlocking {
        api.pages = listOf(NasBookPage(listOf(NasBook("1", relativePath = "books/book.txt")), 1, 200, 1))
        assertTrue(upload().alreadyExists)
        assertEquals(0, api.uploads)
    }

    @Test fun sameTitleDifferentContentAndPathDoesNotCountAsExactDuplicate() = runBlocking {
        api.pages = listOf(NasBookPage(listOf(NasBook("1", title = "Book", author = "Author",
            relativePath = "other/edition.txt", contentHash = "b".repeat(64))), 1, 200, 1))
        assertFalse(upload().alreadyExists)
        assertEquals(1, api.uploads)
        assertEquals("skip", api.policy)
        assertEquals(listOf(NasBookUploadStage.Checking, NasBookUploadStage.Uploading), stages)
    }

    @Test fun emptyLibraryUploadsOnceWithMetadataAndSameSettings() = runBlocking {
        assertFalse(upload().alreadyExists)
        assertEquals(listOf(1), api.requestedPages)
        assertEquals(1, api.uploads)
        assertEquals("skip", api.policy)
        assertEquals(settings.currentSettings, api.uploadSettings)
        assertEquals(listOf("Book", "Author", "Intro", "books"), api.metadata)
    }

    @Test fun serverDuplicateRaceIsReportedAsExistingNotSuccess() = runBlocking {
        api.result = NasUploadResult(skipped = listOf(NasBook("race", relativePath = "other/book.txt")))
        assertTrue(upload().alreadyExists)
        assertEquals(1, api.uploads)
    }

    @Test fun readOnlyAndMissingCapabilityNeverReadFileOrUpload() = runBlocking {
        api.connection = api.connection.copy(writeAccess = NasWriteAccess.DENIED)
        assertEquals(NasBookUploadError.ReadOnly, failure().reason)
        api.connection = api.connection.copy(writeAccess = NasWriteAccess.ALLOWED, capabilities = NasCapabilities())
        assertEquals(NasBookUploadError.Unsupported, failure().reason)
        assertEquals(0, api.uploads)
    }

    @Test fun brokenPaginationAbortsInsteadOfAssumingAbsent() = runBlocking {
        api.pages = listOf(NasBookPage(listOf(NasBook("1")), 1, 1, 2),
            NasBookPage(listOf(NasBook("1")), 2, 1, 2))
        assertEquals(NasBookUploadError.CheckIncomplete, failure().reason)
        assertEquals(0, api.uploads)
    }

    @Test fun configurationChangeDuringPreflightAbortsUpload() = runBlocking {
        api.afterList = { settings.settings.value = settings.currentSettings.copy(apiToken = "replacement") }
        assertEquals(NasBookUploadError.Unavailable, failure().reason)
        assertEquals(0, api.uploads)
    }

    @Test fun preflightNetworkFailureDoesNotUpload() = runBlocking {
        api.afterList = { throw IOException("offline") }
        try { upload(); fail("Expected failure") } catch (_: IOException) { }
        assertEquals(0, api.uploads)
    }

    @Test fun emptyOrFailedUploadResponseIsNotSuccess() = runBlocking {
        api.result = NasUploadResult()
        assertEquals(NasBookUploadError.UploadFailed, failure().reason)
        api.result = NasUploadResult(failed = listOf("disk full"))
        assertEquals(NasBookUploadError.UploadFailed, failure().reason)
    }

    private suspend fun failure(): NasBookUploadException = try {
        upload(); throw AssertionError("Expected upload failure")
    } catch (expected: NasBookUploadException) { expected }

    private class Settings : NasSettingsGateway {
        override val settings = MutableStateFlow(NasSettings("https://nas.example", "test-token", true, true))
        override val currentSettings get() = settings.value
        override suspend fun update(transform: (NasSettings) -> NasSettings) { settings.value = transform(settings.value) }
    }

    private class Library : NasLibraryGateway by unsupportedGateway() {
        var checks = 0
        var uploads = 0
        var policy = ""
        var uploadSettings: NasSettings? = null
        var metadata = emptyList<String>()
        var connection = NasConnection(NasHealth("ok"),
            NasCapabilities(features = setOf("books", "upload"), defaultUploadDirectory = "books"))
        var pages = listOf(NasBookPage(emptyList(), 1, 200, 0))
        var result = NasUploadResult(uploaded = listOf(NasBook("new", relativePath = "books/book.txt")))
        val requestedPages = mutableListOf<Int>()
        var afterList: () -> Unit = {}
        override fun normalizeUrl(raw: String) = raw.trimEnd('/')
        override suspend fun checkConnection(settings: NasSettings): NasConnection { checks++; return connection }
        override suspend fun listBooks(page: Int, pageSize: Int, search: String?, directoryPath: String?,
            scrapeStatus: String?, manualConfirmed: Boolean?, sortBy: String, sortOrder: String,
            settings: NasSettings): NasBookPage {
            requestedPages += page
            assertNull(search)
            afterList()
            return pages[page - 1]
        }
        override suspend fun uploadBook(source: NasUploadSource, fileName: String, contentLength: Long?,
            duplicatePolicy: String, title: String, author: String, intro: String, directoryPath: String,
            settings: NasSettings): NasUploadResult {
            uploads++
            policy = duplicatePolicy
            uploadSettings = settings
            metadata = listOf(title, author, intro, directoryPath)
            var count = 0
            source.writeTo { _, _, length -> count += length }
            assertEquals(2, count)
            return result
        }
    }

    companion object {
        private fun unsupportedGateway(): NasLibraryGateway = Proxy.newProxyInstance(
            NasLibraryGateway::class.java.classLoader, arrayOf(NasLibraryGateway::class.java)
        ) { _, method, _ -> error("Unexpected call: ${method.name}") } as NasLibraryGateway
    }
}
