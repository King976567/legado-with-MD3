package io.legado.app.data.repository

import android.app.Application
import io.legado.app.domain.model.NasTransferHistory
import io.legado.app.domain.model.NasUploadTask
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Small local history store for NAS transfers. It intentionally stores only
 * display metadata and never NAS addresses or credentials.
 */
class NasTransferHistoryRepository(application: Application) {
    private val file = File(application.noBackupFilesDir, FILE_NAME)
    private val lock = Any()
    private val _history = MutableStateFlow(load())

    val history: StateFlow<List<NasTransferHistory>> = _history.asStateFlow()

    fun recordStarted(
        id: String,
        direction: String,
        title: String,
        kind: String,
        remotePath: String? = null,
        retryKey: String? = null,
        replaceBookId: String? = null,
    ) {
        update { current ->
            val now = System.currentTimeMillis()
            val existing = current.firstOrNull { it.id == id }
            val item = NasTransferHistory(
                id = id,
                direction = direction,
                title = title,
                kind = kind,
                state = RUNNING,
                remotePath = remotePath,
                retryKey = retryKey,
                replaceBookId = replaceBookId,
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
            )
            listOf(item) + current.filterNot { it.id == id }
        }
    }

    fun recordFinished(
        id: String,
        state: String,
        message: String? = null,
        remotePath: String? = null,
    ) {
        update { current ->
            val existing = current.firstOrNull { it.id == id } ?: return@update current
            listOf(
                existing.copy(
                    state = state,
                    message = message,
                    remotePath = remotePath ?: existing.remotePath,
                    finishedAt = System.currentTimeMillis(),
                ),
            ) + current.filterNot { it.id == id }
        }
    }

    fun recordUpload(task: NasUploadTask) {
        if (!task.isFinished) return
        update { current ->
            val existing = current.firstOrNull { it.id == task.id }
            val item = NasTransferHistory(
                id = task.id,
                direction = "upload",
                title = task.title,
                kind = task.kind,
                state = task.state,
                message = task.message,
                remotePath = task.remotePath,
                retryKey = task.retryKey,
                replaceBookId = task.replaceBookId,
                createdAt = task.createdAt.takeIf { it > 0 } ?: existing?.createdAt ?: System.currentTimeMillis(),
                finishedAt = existing?.finishedAt ?: System.currentTimeMillis(),
            )
            listOf(item) + current.filterNot { it.id == task.id }
        }
    }

    fun delete(id: String) {
        update { current -> current.filterNot { it.id == id } }
    }

    fun clear() {
        update { emptyList() }
    }

    private fun update(transform: (List<NasTransferHistory>) -> List<NasTransferHistory>) {
        synchronized(lock) {
            val next = transform(_history.value).take(MAX_ITEMS)
            _history.value = next
            runCatching { FileUtils.writeTextAtomic(file.absolutePath, GSON.toJson(next)) }
        }
    }

    private fun load(): List<NasTransferHistory> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        val array = GSON.fromJson(file.readText(), Array<NasTransferHistory>::class.java)
        val now = System.currentTimeMillis()
        array?.toList().orEmpty().map { item ->
            // Downloads currently run in the app process. If the process was
            // restarted, a persisted RUNNING record can no longer be active.
            if (item.direction == "download" && item.state == RUNNING) {
                item.copy(state = CANCELLED, finishedAt = now)
            } else {
                item
            }
        }.take(MAX_ITEMS)
    }.getOrDefault(emptyList())

    companion object {
        private const val FILE_NAME = "nas-transfer-history.json"
        private const val MAX_ITEMS = 100
        const val RUNNING = "RUNNING"
        const val SUCCEEDED = "SUCCEEDED"
        const val FAILED = "FAILED"
        const val CANCELLED = "CANCELLED"
    }
}
