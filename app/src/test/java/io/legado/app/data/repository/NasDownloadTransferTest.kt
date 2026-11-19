package io.legado.app.data.repository

import android.app.Application
import android.net.Uri
import io.legado.app.data.appDb
import io.legado.app.domain.gateway.ImportBookSettingsGateway
import io.legado.app.domain.model.settings.ImportBookSettings
import io.legado.app.domain.gateway.NasDownloadSink
import io.legado.app.domain.gateway.NasLibraryGateway
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.model.NasBook
import io.legado.app.domain.model.NasBookDetail
import io.legado.app.domain.model.NasTransferHistory
import io.legado.app.domain.model.settings.NasSettings
import io.legado.app.domain.usecase.NasLibraryUseCase
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import splitties.init.injectAsAppCtx
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class NasDownloadTransferTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val old = NasSettings("https://original.invalid/api", "original-token", connectionVerified = true)
    private val settings = object : NasSettingsGateway {
        override val settings = MutableStateFlow(old)
        override val currentSettings: NasSettings get() = settings.value
        override suspend fun update(transform: (NasSettings) -> NasSettings) { settings.value = transform(settings.value) }
    }
    private val history = NasTransferHistoryRepository(app)
    private val started = CompletableDeferred<Unit>()
    private var holdOpen = true
    private val api = object : NasLibraryGateway by unsupportedGateway() {
        override fun normalizeUrl(raw: String) = raw.trimEnd('/')
        override suspend fun detail(id: String, settings: NasSettings): NasBookDetail =
            throw AssertionError("A mismatched download must not use the network")

        override suspend fun downloadBook(id: String, sink: NasDownloadSink, settings: NasSettings) {
            assertEquals(old, settings)
            val bytes = if (holdOpen) byteArrayOf(1, 2, 3) else "Contents of $id".toByteArray()
            sink.write(bytes, 0, bytes.size)
            started.complete(Unit)
            if (holdOpen) awaitCancellation()
        }
    }
    private val repository = NasBookDownloadRepository(app, NasLibraryUseCase(api, settings), settings, history)

    @Test fun cancelledDownloadFinalizesHistoryAndDeletesTemporaryFile() = runBlocking {
        val transfer = launch { repository.download(NasBook("same-id", fileName = "Comic.cbz"), old) }
        started.await()
        assertTrue(app.cacheDir.listFiles().orEmpty().any { it.name.startsWith("nas-download-") })
        transfer.cancelAndJoin()
        assertFalse(app.cacheDir.listFiles().orEmpty().any { it.name.startsWith("nas-download-") })
        val row = history.history.value.single()
        assertEquals(NasTransferHistoryRepository.CANCELLED, row.state)
        assertEquals(nasDownloadFingerprint(old.apiUrl), row.destinationFingerprint)
        assertEquals("same-id", row.retryKey)
    }

    @Test fun differentNasAndLegacyHistoryCannotRetryTheOldId() = runBlocking {
        settings.update { it.copy(apiUrl = "https://different.invalid/api") }
        val item = NasTransferHistory("row", "download", "Comic", "comic", "FAILED", retryKey = "same-id")
        for (fingerprint in listOf(null, nasDownloadFingerprint(old.apiUrl))) {
            val error = runCatching { repository.retry(item.copy(destinationFingerprint = fingerprint), "same-id") }
            assertTrue(error.exceptionOrNull() is NoStackTraceException)
        }
        assertTrue(history.history.value.isEmpty())
    }

    @Test fun sameNamedDownloadsAreSeparateAndAnIdenticalRetryPreservesTheOriginalBook() = runBlocking {
        app.injectAsAppCtx()
        startKoin {
            modules(module {
                single<ImportBookSettingsGateway> {
                    object : ImportBookSettingsGateway {
                        override val settings = MutableStateFlow(ImportBookSettings())
                        override val currentSettings: ImportBookSettings get() = settings.value
                        override suspend fun update(transform: (ImportBookSettings) -> ImportBookSettings) {
                            settings.value = transform(settings.value)
                        }
                    }
                }
            })
        }
        try {
            holdOpen = false
            val first = repository.download(NasBook("first", fileName = "Book.txt"), old)
            appDb.bookDao.update(first.copy(durChapterIndex = 3, durChapterPos = 10))
            val second = repository.download(NasBook("second", fileName = "Book.txt"), old)
            val identical = repository.download(NasBook("first", fileName = "Book.txt"), old)
            assertNotEquals(first.bookUrl, second.bookUrl)
            assertEquals("Contents of first", File(requireNotNull(Uri.parse(first.bookUrl).path)).readText())
            assertEquals("Contents of second", File(requireNotNull(Uri.parse(second.bookUrl).path)).readText())
            assertEquals(first.bookUrl, identical.bookUrl)
            assertEquals(3, identical.durChapterIndex)
            assertEquals(10, identical.durChapterPos)
        } finally {
            stopKoin()
        }
    }

    private fun unsupportedGateway(): NasLibraryGateway = Proxy.newProxyInstance(
        NasLibraryGateway::class.java.classLoader,
        arrayOf(NasLibraryGateway::class.java),
    ) { _, method, _ -> throw AssertionError("Unexpected NAS gateway method ${method.name}") } as NasLibraryGateway
}
