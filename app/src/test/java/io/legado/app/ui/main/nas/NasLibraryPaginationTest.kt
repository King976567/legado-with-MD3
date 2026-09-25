package io.legado.app.ui.main.nas

import android.app.Application
import androidx.lifecycle.ViewModelStore
import io.legado.app.domain.gateway.NasLibraryGateway
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.model.NasBook
import io.legado.app.domain.model.NasBookPage
import io.legado.app.domain.model.NasCapabilities
import io.legado.app.domain.model.NasConnection
import io.legado.app.domain.model.NasHealth
import io.legado.app.domain.model.settings.NasSettings
import io.legado.app.domain.usecase.NasLibraryUseCase
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
class NasLibraryPaginationTest {
    private val store = ViewModelStore()
    private val settings = Settings()
    private val api = Library()
    private lateinit var model: NasLibraryViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        model = NasLibraryViewModel(RuntimeEnvironment.getApplication(), NasLibraryUseCase(api, settings), settings)
        store.put("nas", model)
    }

    @After fun tearDown() {
        api.all.forEach { it.response.cancel() }
        store.clear()
        Dispatchers.resetMain()
    }

    private suspend fun request() = withTimeout(5_000) { api.requests.receive() }
    private suspend fun state(predicate: (NasLibraryUiState) -> Boolean) =
        withTimeout(5_000) { model.uiState.first(predicate) }

    private suspend fun firstPage() {
        model.refresh()
        request().reply("1", "2", "3")
        state { !it.isLoading && it.books.size == 3 }
    }

    @Test fun `appends deduplicated pages and ignores repeat load events`() = runBlocking {
        firstPage()
        model.loadNextPage()
        val next = request()
        repeat(10) { model.loadNextPage() }
        assertEquals(2, next.page)
        assertTrue(api.requests.tryReceive().isFailure)
        assertEquals(listOf("1", "2", "3"), model.uiState.value.books.map { it.id })
        next.reply("3", "4", "5")
        val loaded = state { !it.isLoading && it.page == 2 }
        assertEquals(listOf("1", "2", "3", "4", "5"), loaded.books.map { it.id })
    }

    @Test fun `failed append preserves page and waits for explicit retry`() = runBlocking {
        firstPage()
        model.loadNextPage()
        request().response.completeExceptionally(IOException("offline"))
        val failed = state { it.loadMoreError != null }
        assertEquals(1, failed.page)
        assertEquals(3, failed.books.size)
        repeat(10) { model.loadNextPage() }
        assertTrue(api.requests.tryReceive().isFailure)
        model.onIntent(NasLibraryIntent.RetryLoadMore)
        val retried = request()
        assertEquals(2, retried.page)
        retried.reply("4", "5", "6")
        assertEquals(6, state { !it.isLoading && it.page == 2 }.books.size)
    }

    @Test fun `new search replaces in-flight append and discards stale response`() = runBlocking {
        firstPage()
        model.loadNextPage()
        val old = request()
        model.onIntent(NasLibraryIntent.QueryChanged("new search"))
        model.onIntent(NasLibraryIntent.SubmitSearch)
        val search = request()
        assertEquals("new search", search.search)
        assertEquals(1, search.page)
        old.reply("old")
        search.reply("new")
        val loaded = state { !it.isLoading && it.appliedQuery == "new search" }
        assertEquals(listOf("new"), loaded.books.map { it.id })
    }

    @Test fun `category change resets and pending query does not mix pages`() = runBlocking {
        firstPage()
        model.onIntent(NasLibraryIntent.QueryChanged("draft"))
        model.loadNextPage()
        assertTrue(api.requests.tryReceive().isFailure)
        model.onIntent(NasLibraryIntent.DirectoryChanged("fiction"))
        val filtered = request()
        assertEquals("fiction", filtered.directory)
        assertEquals("draft", filtered.search)
        assertEquals(1, filtered.page)
        filtered.reply("filtered")
        assertEquals(listOf("filtered"), state { !it.isLoading }.books.map { it.id })
    }

    @Test fun `duplicate-only response stops automatic loading`() = runBlocking {
        firstPage()
        model.loadNextPage()
        request().reply("1", "2", "3")
        val loaded = state { !it.isLoading && it.page == 2 }
        assertTrue(loaded.reachedEnd)
        assertFalse(loaded.canGoNext)
        model.loadNextPage()
        assertTrue(api.requests.tryReceive().isFailure)
    }

    @Test fun `changing NAS cannot append the old servers books`() = runBlocking {
        firstPage()
        model.loadNextPage()
        val old = request()
        settings.update { it.copy(apiUrl = "https://new.example", apiToken = "new-token") }
        model.refresh()
        val fresh = request()
        old.reply("old-server")
        fresh.reply("new-server")
        assertEquals(listOf("new-server"), state { !it.isLoading }.books.map { it.id })
    }

    private class Settings : NasSettingsGateway {
        override val settings = MutableStateFlow(NasSettings(
            apiUrl = "https://nas.example", apiToken = "test-token", connectionVerified = true))
        override val currentSettings get() = settings.value
        override suspend fun update(transform: (NasSettings) -> NasSettings) { settings.value = transform(settings.value) }
    }

    private data class Request(val page: Int, val search: String?, val directory: String?) {
        val response = CompletableDeferred<NasBookPage>()
        fun reply(vararg ids: String) { response.complete(NasBookPage(ids.map { NasBook(it) }, page, 3, 12)) }
    }

    private class Library : NasLibraryGateway by unsupportedGateway() {
        val requests = Channel<Request>(Channel.UNLIMITED)
        val all = CopyOnWriteArrayList<Request>()
        override fun normalizeUrl(raw: String) = raw.trimEnd('/')
        override suspend fun checkConnection(settings: NasSettings) = NasConnection(NasHealth("ok"), NasCapabilities())
        override suspend fun listBooks(page: Int, pageSize: Int, search: String?, directoryPath: String?,
            scrapeStatus: String?, manualConfirmed: Boolean?, sortBy: String, sortOrder: String,
            settings: NasSettings): NasBookPage {
            val request = Request(page, search, directoryPath)
            all += request
            requests.send(request)
            // Deliberately ignore cancellation to exercise the stale-response guard.
            return withContext(NonCancellable) { request.response.await() }
        }
    }

    companion object {
        private fun unsupportedGateway(): NasLibraryGateway = Proxy.newProxyInstance(
            NasLibraryGateway::class.java.classLoader, arrayOf(NasLibraryGateway::class.java)
        ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as NasLibraryGateway
    }
}
