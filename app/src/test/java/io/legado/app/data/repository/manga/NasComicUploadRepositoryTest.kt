package io.legado.app.data.repository.manga

import android.app.Application
import androidx.room.Room
import coil3.ImageLoader
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.domain.gateway.NasLibraryGateway
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.model.NasComicUploadException
import io.legado.app.domain.model.NasComicUploadFailure
import io.legado.app.domain.model.settings.NasSettings
import io.legado.app.utils.GSON
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NasComicUploadRepositoryTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val settings = MutableStateFlow(NasSettings("https://nas.example", "test-token", true, true))
    private lateinit var database: AppDatabase
    private lateinit var repository: NasComicUploadRepository
    private lateinit var imageLoader: ImageLoader
    private val book = Book(bookUrl = "https://source.example/comic", name = "Comic", origin = "https://source.example")
    private val sourceKey = nasComicSourceKey(book.origin, book.bookUrl)

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        imageLoader = ImageLoader.Builder(app).build()
        val api = Proxy.newProxyInstance(NasLibraryGateway::class.java.classLoader,
            arrayOf(NasLibraryGateway::class.java)) { _, method, args ->
            if (method.name == "normalizeUrl") args?.first() else error("Unexpected NAS request: ${method.name}")
        } as NasLibraryGateway
        val preferences = object : NasSettingsGateway {
            override val currentSettings get() = settings.value
            override val settings get() = this@NasComicUploadRepositoryTest.settings
            override suspend fun update(transform: (NasSettings) -> NasSettings) = this.settings.compareAndSet(
                currentSettings, transform(currentSettings),
            ).let { Unit }
        }
        repository = NasComicUploadRepository(app, database, api, preferences, imageLoader)
    }

    @After fun tearDown() {
        imageLoader.shutdown()
        database.close()
    }

    @Test fun oldHistoryCleanupDoesNotTouchNewTaskOrLegacySnapshot() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        writeSnapshot(null, "legacy")
        writeSnapshot(first, "old")
        writeSnapshot(second, "new")
        repository.cleanupHistoryArtifacts("scoped:$sourceKey", first)
        assertNull(repository.loadSnapshot(sourceKey, first))
        assertEquals("new", repository.loadSnapshot(sourceKey, second)?.replaceBookId)
        assertEquals("legacy", repository.loadSnapshot(sourceKey, null)?.replaceBookId)
    }

    @Test fun scopedRetryDoesNotFallBackToLegacySnapshot() = runBlocking {
        writeSnapshot(null, "legacy")
        assertEquals(NasComicUploadFailure.RefreshFailed, retryFailure(UUID.randomUUID(), "legacy"))
    }

    @Test fun retryRejectsChangedDestinationOrWrongReplacementBeforeCallingNas() = runBlocking {
        val taskId = UUID.randomUUID()
        writeSnapshot(taskId, "approved")
        assertEquals(NasComicUploadFailure.RemoteChanged, retryFailure(taskId, "other-id"))
        settings.value = settings.value.copy(apiUrl = "https://other.example")
        assertEquals(NasComicUploadFailure.ConfigurationChanged, retryFailure(taskId, "approved"))
    }

    @Test fun legacyReplacementMustBeReconfirmedInsteadOfRetriedBlindly() = runBlocking {
        val taskId = UUID.randomUUID()
        writeSnapshot(taskId, "approved")
        assertEquals(NasComicUploadFailure.RemoteChanged, retryFailure(taskId, "approved"))
    }

    private suspend fun retryFailure(taskId: UUID, replaceBookId: String): NasComicUploadFailure? =
        (runCatching { repository.retry("scoped:$sourceKey", taskId, replaceBookId) }
            .exceptionOrNull() as? NasComicUploadException)?.reason

    private fun writeSnapshot(taskId: UUID?, replaces: String) {
        val snapshot = NasComicUploadRepository.ComicSnapshot(
            book.bookUrl, book, book, emptyList(), sourceKey, repository.configurationFingerprint(settings.value),
            maxUploadBytes = 0, replaceBookId = replaces,
        )
        val directory = repository.taskDirectory(sourceKey, taskId).apply { mkdirs() }
        File(directory, "snapshot.json").writeText(GSON.toJson(snapshot))
    }
}
