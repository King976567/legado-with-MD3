package io.legado.app.data.repository

import android.app.Application
import io.legado.app.domain.model.NasUploadTask
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class NasTransferHistoryRepositoryTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private fun store() = NasTransferHistoryRepository(app)
    private fun upload(id: String, createdAt: Long = 1, state: String = "SUCCEEDED") =
        NasUploadTask(id = id, title = id, kind = "comic", state = state, createdAt = createdAt)

    @Test fun legacyArrayIsImportedAndRunningDownloadIsRecoveredAsCancelled() {
        val file = java.io.File(app.noBackupFilesDir, "nas-transfer-history.json")
        file.writeText("""[{"id":"legacy","direction":"download","title":"Book","kind":"book","state":"RUNNING"}]""")
        val repository = store()
        assertEquals(NasTransferHistoryRepository.CANCELLED, repository.history.value.single().state)
        repository.reconcileUploads(listOf(upload("new")))
        assertEquals(setOf("legacy", "new"), store().history.value.map { it.id }.toSet())
    }

    @Test fun deletedUploadDoesNotReturnAfterProcessRestart() {
        val old = upload("old")
        store().apply {
            reconcileUploads(listOf(old))
            delete(old.id)
        }
        store().apply {
            reconcileUploads(listOf(old, upload("new", 2)))
            assertEquals(listOf("new"), history.value.map { it.id })
        }
    }

    @Test fun clearingHistoryPreservesRunningDownloadsAndLaterCompletions() {
        val completed = upload("finished")
        val running = upload("active", 2, "RUNNING")
        store().apply {
            recordStarted("download", "download", "book", "book")
            reconcileUploads(listOf(completed, running))
            clear()
            assertEquals(listOf("download"), history.value.map { it.id })
            reconcileUploads(listOf(completed, running.copy(state = "SUCCEEDED")))
            recordFinished("download", NasTransferHistoryRepository.SUCCEEDED)
            assertEquals(setOf("download", "active"), history.value.map { it.id }.toSet())
        }
    }

    @Test fun replayOfRetainedWorkDoesNotBringBackEvictedEntriesOrReorderHistory() {
        val old = (1L..102L).map { upload("$it", it) }
        val repository = store()
        repository.reconcileUploads(old.reversed())
        val original = repository.history.value
        assertEquals((3L..102L).reversed().map(Long::toString), original.map { it.id })
        repository.reconcileUploads(old)
        assertEquals(original, repository.history.value)
        repository.delete("102")
        repository.reconcileUploads(old)
        assertEquals(original.drop(1), repository.history.value)
    }
}
