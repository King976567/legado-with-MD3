package io.legado.app.ui.main.nas

import android.app.Application
import androidx.lifecycle.ViewModelStore
import io.legado.app.domain.gateway.NasLibraryGateway
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.model.NasBook
import io.legado.app.domain.model.NasBookDetail
import io.legado.app.domain.model.settings.NasSettings
import io.legado.app.domain.usecase.NasLibraryUseCase
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class NasBookDetailTest {
    private val store = ViewModelStore()
    private val settings = Settings()
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val api = object : NasLibraryGateway by unsupportedGateway() {
        override fun normalizeUrl(raw: String) = raw.trimEnd('/')
        override suspend fun detail(id: String, settings: NasSettings): NasBookDetail {
            val request = Request(id)
            requests.send(request)
            try { return request.response.await() } finally { request.finished.complete(Unit) }
        }
    }
    private lateinit var model: NasLibraryViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        model = NasLibraryViewModel(RuntimeEnvironment.getApplication(), NasLibraryUseCase(api, settings), settings)
        store.put("nas", model)
    }

    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    private suspend fun request() = withTimeout(5_000) { requests.receive() }
    private suspend fun settled() = withTimeout(5_000) { model.uiState.first { !it.isLoadingDetail } }

    @Test fun openingOnlyLoadsDetailsAndDoesNotDownloadOrImport() = runBlocking {
        model.onIntent(NasLibraryIntent.OpenBook(NasBook("1", title = "List title")))
        val request = request()
        assertTrue(model.uiState.value.isLoadingDetail)
        assertEquals("List title", model.uiState.value.selectedBook?.title)
        request.response.complete(NasBookDetail("1", title = "Full title", intro = "Intro"))
        assertEquals("Full title", settled().selectedBook?.title)
        assertNull(model.uiState.value.actionError)
        assertFalse(model.uiState.value.isActionRunning)
    }

    @Test fun detailFailureKeepsListMetadataAndRetryCanSucceed() = runBlocking {
        val book = NasBook("1", title = "List title")
        model.onIntent(NasLibraryIntent.OpenBook(book))
        request().response.completeExceptionally(IOException("Offline"))
        assertEquals(book, settled().selectedBook)
        assertNotNull(model.uiState.value.detailError)
        model.onIntent(NasLibraryIntent.OpenBook(book))
        request().response.complete(NasBookDetail("1", title = "Full title"))
        assertNull(settled().detailError)
    }

    @Test fun backCancelsEnrichmentAndDoesNotReopenDetail() = runBlocking {
        model.onIntent(NasLibraryIntent.OpenBook(NasBook("1")))
        val old = request()
        model.onIntent(NasLibraryIntent.DismissBook)
        withTimeout(5_000) { old.finished.await() }
        old.response.complete(NasBookDetail("1", title = "Too late"))
        assertNull(settled().selectedBook)
        assertNull(model.uiState.value.detailError)
    }

    @Test fun newSelectionCancelsOldRequest() = runBlocking {
        model.onIntent(NasLibraryIntent.OpenBook(NasBook("1")))
        val old = request()
        model.onIntent(NasLibraryIntent.OpenBook(NasBook("2")))
        val current = request()
        withTimeout(5_000) { old.finished.await() }
        old.response.complete(NasBookDetail("1", title = "Old"))
        current.response.complete(NasBookDetail("2", title = "Current"))
        assertEquals("2", settled().selectedBook?.id)
    }

    @Test fun configurationChangeCancelsAndClearsDetail() = runBlocking {
        model.onIntent(NasLibraryIntent.OpenBook(NasBook("1")))
        val old = request()
        settings.update { it.copy(apiUrl = "https://other.example", connectionVerified = false) }
        withTimeout(5_000) { old.finished.await() }
        assertNull(settled().selectedBook)
        assertFalse(model.uiState.value.isConfigured)
    }

    private data class Request(val id: String,
        val response: CompletableDeferred<NasBookDetail> = CompletableDeferred(),
        val finished: CompletableDeferred<Unit> = CompletableDeferred())

    private class Settings : NasSettingsGateway {
        override val settings = MutableStateFlow(NasSettings(
            apiUrl = "https://nas.example", apiToken = "test-token", connectionVerified = true))
        override val currentSettings get() = settings.value
        override suspend fun update(transform: (NasSettings) -> NasSettings) { settings.value = transform(settings.value) }
    }

    companion object {
        private fun unsupportedGateway(): NasLibraryGateway = Proxy.newProxyInstance(
            NasLibraryGateway::class.java.classLoader, arrayOf(NasLibraryGateway::class.java)
        ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as NasLibraryGateway
    }
}
