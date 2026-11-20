package io.legado.app.help.book

import java.io.Closeable

/** Logical lease for a reader window, including before the chapter's current cache path is known. */
internal class ChapterCacheUse : Closeable {
    fun retain(bookUrl: String?, chapterIndexes: Set<Int>) = synchronized(active) {
        if (bookUrl == null || chapterIndexes.isEmpty()) active.remove(this)
        else active[this] = Window(bookUrl, chapterIndexes)
        Unit
    }

    override fun close() = retain(null, emptySet())

    private data class Window(val bookUrl: String, val indices: Set<Int>)

    companion object {
        private val active = mutableMapOf<ChapterCacheUse, Window>()

        /** Hold the same logical guard used for single-chapter deletes, even with no open file. */
        fun deleteBookIfIdle(bookUrl: String, delete: () -> Boolean): Boolean = synchronized(active) {
            if (active.values.any { it.bookUrl == bookUrl }) false else delete()
        }

        /** Lock order: logical chapter lease → BookImageFileStore. Never enter from within it. */
        fun deleteIfIdle(
            bookUrl: String,
            index: Int,
            delete: () -> ChapterCacheDeleteResult,
        ): ChapterCacheDeleteResult = synchronized(active) {
            if (active.values.any { it.bookUrl == bookUrl && index in it.indices }) {
                ChapterCacheDeleteResult.Busy
            } else delete()
        }
    }
}
