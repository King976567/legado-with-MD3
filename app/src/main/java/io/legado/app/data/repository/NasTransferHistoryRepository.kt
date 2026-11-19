package io.legado.app.data.repository

import android.app.Application
import android.util.Log
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
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
    private var stored = load()
    private val _history = MutableStateFlow(stored.items)

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

    /** Reconcile the complete WorkManager snapshot once, independently of the cache screen. */
    internal fun reconcileUploads(tasks: List<NasUploadTask>): Boolean = synchronized(lock) {
        val terminal = tasks.filter(NasUploadTask::isFinished)
        val known = stored.observedUploads + stored.items.filter { it.direction == "upload" }.map { it.id }
        val now = System.currentTimeMillis()
        val newItems = terminal.filterNot { it.id in known }
            .sortedByDescending { it.createdAt }
            .map { task ->
                NasTransferHistory(
                    id = task.id,
                    direction = "upload",
                    title = task.title,
                    kind = task.kind,
                    state = task.state,
                    message = task.message,
                    remotePath = task.remotePath,
                    retryKey = task.retryKey,
                    replaceBookId = task.replaceBookId,
                    destinationFingerprint = task.destinationFingerprint,
                    createdAt = task.createdAt.takeIf { it > 0 } ?: now,
                    finishedAt = now,
                )
            }
        // Keep receipts even for deleted/evicted rows, until WorkManager prunes those jobs.
        save(newItems + stored.items, terminal.mapTo(hashSetOf()) { it.id })
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val item = stored.items.find { it.id == id && it.state != RUNNING } ?: return@synchronized false
        save(stored.items.filterNot { it.id == id }, stored.observedUploads.withReceipt(item))
    }

    /** Only clear completed history; an in-process download still needs its row to finish. */
    fun clear(): List<NasTransferHistory> = synchronized(lock) {
        val (running, removed) = stored.items.partition { it.state == RUNNING }
        val receipts = stored.observedUploads + removed.filter { it.direction == "upload" }.map { it.id }
        if (save(running, receipts)) removed else emptyList()
    }

    private fun Set<String>.withReceipt(item: NasTransferHistory) =
        if (item.direction == "upload") this + item.id else this

    private fun update(transform: (List<NasTransferHistory>) -> List<NasTransferHistory>) {
        synchronized(lock) { save(transform(stored.items), stored.observedUploads) }
    }

    private fun save(items: List<NasTransferHistory>, observedUploads: Set<String>): Boolean {
        val ordered = items.sortedWith(compareByDescending<NasTransferHistory> { it.state == RUNNING }
            .thenByDescending { it.finishedAt ?: it.createdAt }.thenByDescending { it.createdAt })
        val runningCount = ordered.count { it.state == RUNNING }
        val next = StoredHistory(ordered.take(runningCount + MAX_ITEMS), observedUploads)
        if (stored == next) return true
        return runCatching { FileUtils.writeTextAtomic(file.absolutePath, GSON.toJson(next)) }
            .onSuccess {
                stored = next
                _history.value = next.items
            }
            .onFailure { Log.w("NasTransferHistory", "Could not save local NAS history", it) }
            .isSuccess
    }

    private fun load(): StoredHistory = runCatching {
        if (!file.isFile) return@runCatching StoredHistory()
        val json = JsonParser.parseString(file.readText())
        val data = if (json.isJsonArray) {
            StoredHistory(GSON.fromJson(json, Array<NasTransferHistory>::class.java).orEmpty().toList())
        } else {
            GSON.fromJson(json, StoredHistory::class.java)
        }
        val now = System.currentTimeMillis()
        data.copy(items = data.items.map { item ->
            if (item.direction == "download" && item.state == RUNNING) {
                item.copy(state = CANCELLED, finishedAt = now)
            } else item
        })
    }.getOrDefault(StoredHistory())

    private data class StoredHistory(
        @SerializedName("items") val items: List<NasTransferHistory> = emptyList(),
        @SerializedName("observedUploads") val observedUploads: Set<String> = emptySet(),
    )

    companion object {
        private const val FILE_NAME = "nas-transfer-history.json"
        private const val MAX_ITEMS = 100
        const val RUNNING = "RUNNING"
        const val SUCCEEDED = "SUCCEEDED"
        const val FAILED = "FAILED"
        const val CANCELLED = "CANCELLED"
    }
}
