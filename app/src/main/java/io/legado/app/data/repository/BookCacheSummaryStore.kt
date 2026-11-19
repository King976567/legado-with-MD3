package io.legado.app.data.repository

import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Last verified, display-only counts. No file names, book URLs or chapter titles are stored. */
internal data class BookCacheSavedCount(val total: Int, val cached: Int)

internal data class BookCacheSummaryEntry(val identity: String, val count: BookCacheSavedCount)

internal class BookCacheSummaryStore(file: File) {
    private val file = AtomicFile(file)
    private val pending = linkedMapOf<String, BookCacheSummaryEntry?>()
    private val changed = Channel<Unit>(Channel.CONFLATED)
    // Only the IO worker/readers access the file; UI-side mutations just replace a pending entry.
    private val diskLock = Any()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (signal in changed) {
                delay(350)
                synchronized(diskLock) {
                    val edits = synchronized(pending) { pending.toMap().also { pending.clear() } }
                    if (edits.isEmpty()) return@synchronized
                    val entries = readDisk()
                    edits.forEach { (key, value) ->
                        entries.remove(key)
                        if (value != null) entries[key] = value
                    }
                    while (entries.size > BOOK_CACHE_SUMMARY_LIMIT) entries.remove(entries.keys.first())
                    writeDisk(entries)
                }
            }
        }
    }

    fun update(key: String, entry: BookCacheSummaryEntry?) {
        synchronized(pending) { pending[key] = entry }
        changed.trySend(Unit)
    }

    suspend fun read(): Map<String, BookCacheSummaryEntry> = withContext(Dispatchers.IO) {
        synchronized(diskLock) {
            readDisk().apply {
                synchronized(pending) {
                    pending.forEach { (key, entry) ->
                        if (entry == null) remove(key) else put(key, entry)
                    }
                }
            }
        }
    }

    private fun readDisk() = try {
        DataInputStream(file.openRead().buffered()).use(::readBookCacheSummaries)
    } catch (_: IOException) {
        linkedMapOf()
    }

    private fun writeDisk(entries: Map<String, BookCacheSummaryEntry>) {
        val stream = try { file.startWrite() } catch (_: IOException) { return }
        try {
            val output = DataOutputStream(stream)
            writeBookCacheSummaries(output, entries)
            file.finishWrite(stream)
        } catch (_: IOException) {
            file.failWrite(stream)
        }
    }
}

private const val BOOK_CACHE_SUMMARY_VERSION = 1
private const val BOOK_CACHE_SUMMARY_LIMIT = 1024

internal fun readBookCacheSummaries(input: DataInputStream): LinkedHashMap<String, BookCacheSummaryEntry> {
    if (input.readInt() != BOOK_CACHE_SUMMARY_VERSION) throw IOException("Unknown cache summary version")
    val size = input.readInt()
    if (size !in 0..BOOK_CACHE_SUMMARY_LIMIT) throw IOException("Invalid cache summary size")
    return linkedMapOf<String, BookCacheSummaryEntry>().apply {
        repeat(size) {
            val key = input.readUTF()
            val identity = input.readUTF()
            val total = input.readInt()
            val cached = input.readInt()
            if (key.length != 64 || identity.length != 64 || total < 0 || cached !in 0..total) {
                throw IOException("Invalid cache summary entry")
            }
            put(key, BookCacheSummaryEntry(identity, BookCacheSavedCount(total, cached)))
        }
    }
}

internal fun writeBookCacheSummaries(output: DataOutputStream, entries: Map<String, BookCacheSummaryEntry>) {
    output.writeInt(BOOK_CACHE_SUMMARY_VERSION)
    output.writeInt(entries.size)
    entries.forEach { (key, entry) ->
        output.writeUTF(key)
        output.writeUTF(entry.identity)
        output.writeInt(entry.count.total)
        output.writeInt(entry.count.cached)
    }
}
