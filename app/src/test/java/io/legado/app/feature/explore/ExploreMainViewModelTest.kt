package io.legado.app.feature.explore

import android.app.Application
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.repository.ExploreRepository
import io.legado.app.data.repository.SettingsRepository
import io.legado.app.data.repository.dataStore
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.config.SettingsWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ExploreMainViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repository = FakeExploreRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        AppConfigStore.init(RuntimeEnvironment.getApplication())
        // DataStore 使用真实 IO；先确认测试基线已落盘，不能用主线程测试调度器等待。
        runBlocking {
            AppConfigStore.putAllAndAwait(
                mapOf(
                    PreferKey.exploreMainContent to false,
                    PreferKey.exploreMainSourceUrl to "a",
                    PreferKey.exploreMainKind to "",
                ),
            )
        }
    }

    @After
    fun tearDown() {
        try {
            store.clear()
            // Robolectric 各用例共享设置写入器；不要把仍在 IO 的写入留给下个用例。
            runBlocking { SettingsWriter.awaitPendingWrites() }
            assertEquals(0, SettingsWriter.pendingWriteCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `source list does not request categories until content mode opens`() = runTest(dispatcher) {
        val model = createModel()
        runCurrent()
        assertTrue(model.uiState.value.ready)
        assertTrue(repository.requests.isEmpty())

        model.onIntent(ExploreMainIntent.ShowContent(true))
        runCurrent()
        assertEquals(listOf("a"), repository.requests)
        repository.responses.getValue("a").complete(listOf(kind("a")))
        runCurrent()
        assertEquals(listOf(kind("a")), model.uiState.value.kinds)

        model.onIntent(ExploreMainIntent.ShowContent(false))
        model.onIntent(ExploreMainIntent.ShowContent(true))
        runCurrent()
        assertEquals(listOf("a"), repository.requests)
    }

    @Test
    fun `switching source clears category owners and rejects late categories`() =
        runTest(dispatcher) {
            val model = createModel()
            runCurrent()
            model.onIntent(ExploreMainIntent.ShowContent(true))
            runCurrent()
            var categoryCleared = false
            model.viewModelStore.put("old-category", object : ViewModel() {
                override fun onCleared() {
                    categoryCleared = true
                }
            })

            model.onIntent(ExploreMainIntent.SelectSource("b"))
            runCurrent()
            assertTrue(categoryCleared)
            assertTrue(model.uiState.value.kindsLoading)
            repository.responses.getValue("b").complete(listOf(kind("b")))
            runCurrent()
            repository.responses.getValue("a").complete(listOf(kind("a")))
            runCurrent()

            assertEquals("b", model.uiState.value.selectedSourceUrl)
            assertEquals(listOf(kind("b")), model.uiState.value.kinds)
            assertFalse(model.uiState.value.kindsLoading)
        }

    @Test
    fun `consecutive source switches keep the latest selection`() = runTest(dispatcher) {
        val model = createModel()
        runCurrent()
        model.onIntent(ExploreMainIntent.SelectSource("b"))
        runCurrent()
        model.onIntent(ExploreMainIntent.SelectSource("a"))
        runCurrent()
        assertEquals("a", model.uiState.value.selectedSourceUrl)
    }

    @Test
    fun `repeated identical source emissions keep category owners`() = runTest(dispatcher) {
        val model = createModel()
        runCurrent()
        model.onIntent(ExploreMainIntent.ShowContent(true))
        runCurrent()
        var categoryCleared = false
        model.viewModelStore.put("category", object : ViewModel() {
            override fun onCleared() {
                categoryCleared = true
            }
        })

        repository.sources.value = repository.sources.value.toList()
        runCurrent()

        assertFalse(categoryCleared)
        assertEquals("a", model.uiState.value.selectedSourceUrl)
    }

    @Test
    fun `selecting a category persists it for the current source`() = runTest(dispatcher) {
        val model = createModel()
        runCurrent()
        model.onIntent(ExploreMainIntent.ShowContent(true))
        runCurrent()

        val category = kind("a")
        repository.responses.getValue("a").complete(listOf(category))
        runCurrent()
        assertEquals(listOf(category), model.uiState.value.kinds)

        val categoryKey = exploreKindKey(category)
        model.onIntent(ExploreMainIntent.SelectKind(categoryKey))
        runCurrent()
        assertEquals(categoryKey, model.uiState.value.selectedKindKey)

        val key = stringPreferencesKey(PreferKey.exploreMainKind)
        val persistedSelections = withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                RuntimeEnvironment.getApplication().dataStore.data.first {
                    parseExploreKindSelections(it[key].orEmpty())["a"] == categoryKey
                }[key].orEmpty()
            }
        }
        assertEquals(categoryKey, parseExploreKindSelections(persistedSelections)["a"])
    }

    @Test
    fun `saved category is restored per source`() = runTest(dispatcher) {
        AppConfigStore.putString(
            PreferKey.exploreMainKind,
            encodeExploreKindSelections(
                mapOf(
                    "a" to exploreKindKey(kind("a")),
                    "b" to exploreKindKey(kind("b")),
                )
            ),
        )
        val model = createModel()
        runCurrent()
        assertEquals(exploreKindKey(kind("a")), model.uiState.value.selectedKindKey)

        model.onIntent(ExploreMainIntent.SelectSource("b"))
        runCurrent()
        assertEquals(exploreKindKey(kind("b")), model.uiState.value.selectedKindKey)
    }

    private fun createModel() = ExploreMainViewModel(repository, SettingsRepository())
        .also { store.put("main", it) }

    private fun kind(source: String) = ExploreKind(title = source, url = "https://$source/books")

    private class FakeExploreRepository : ExploreRepository {
        val requests = mutableListOf<String>()
        val responses = mapOf(
            "a" to CompletableDeferred<List<ExploreKind>>(),
            "b" to CompletableDeferred<List<ExploreKind>>(),
        )
        val sources = MutableStateFlow(
            listOf(
                BookSourcePart(bookSourceUrl = "a", bookSourceName = "A"),
                BookSourcePart(bookSourceUrl = "b", bookSourceName = "B"),
            )
        )

        override fun getExploreSources(query: String, selectedGroup: String) = sources
        override suspend fun refreshSourceExploreKinds(sourceUrl: String) =
            getSourceExploreKinds(sourceUrl)
        override suspend fun getSourceExploreKinds(sourceUrl: String): List<ExploreKind> {
            requests += sourceUrl
            // Simulate a rule engine operation which cannot stop immediately on cancellation.
            return withContext(NonCancellable) { responses.getValue(sourceUrl).await() }
        }

        override fun getBookshelfItems() = flowOf(emptyList<SearchBook>())
        override fun getExploreGroups() = flowOf(emptyList<String>())
        override suspend fun getBookSource(sourceUrl: String): BookSource? = null
        override suspend fun topSource(bookSource: BookSourcePart) = Unit
        override suspend fun deleteSource(sourceUrl: String) = Unit
    }
}
