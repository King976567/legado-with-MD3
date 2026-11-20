package io.legado.app.data.repository.manga

import com.google.gson.GsonBuilder
import io.legado.app.domain.model.NasBookDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NasComicSnapshotJsonTest {
    // Book/BookChapter already have entity keep rules. The task's wrapper must
    // retain its JSON keys independently of its compiled field names.
    private val gson = GsonBuilder().setFieldNamingStrategy { field ->
        if (field.declaringClass == NasComicUploadRepository.ComicSnapshot::class.java) {
            "renamed_${field.name}"
        } else {
            field.name
        }
    }.create()

    @Test
    fun existingSnapshotRetainsCacheIdentityChapterOrderAndReplacement() {
        val existingJson = """
            {"bookUrl":"https://source.example/book/1",
             "book":{"bookUrl":"https://source.example/book/1","name":"刷新后书名","origin":"source"},
             "cacheBook":{"bookUrl":"https://source.example/book/1","name":"原缓存书名","origin":"source"},
             "chapters":[
               {"url":"chapter-1","title":"第一章","bookUrl":"https://source.example/book/1","index":0},
               {"url":"chapter-2","title":"第二章","bookUrl":"https://source.example/book/1","index":1}],
             "clientSourceKey":"stable-source-key","configurationFingerprint":"nas-config-fingerprint",
             "maxUploadBytes":4294967296,"replaceBookId":"existing-book-12","expectedRemoteHash":"${"a".repeat(64)}"}
        """.trimIndent()

        val restored = gson.fromJson(existingJson, NasComicUploadRepository.ComicSnapshot::class.java)

        assertEquals("https://source.example/book/1", restored.bookUrl)
        assertEquals("刷新后书名", restored.book.name)
        assertEquals("原缓存书名", restored.cacheBook?.name)
        assertEquals("source", restored.cacheBook?.origin)
        assertEquals(listOf("chapter-1", "chapter-2"), restored.chapters.map { it.url })
        assertEquals(listOf("第一章", "第二章"), restored.chapters.map { it.title })
        assertEquals(listOf(0, 1), restored.chapters.map { it.index })
        assertEquals("stable-source-key", restored.clientSourceKey)
        assertEquals("nas-config-fingerprint", restored.configurationFingerprint)
        assertEquals(4294967296L, restored.maxUploadBytes)
        assertEquals("existing-book-12", restored.replaceBookId)
        assertEquals("a".repeat(64), restored.expectedRemoteHash)
        assertEquals(
            setOf("bookUrl", "book", "cacheBook", "chapters", "clientSourceKey",
                "configurationFingerprint", "maxUploadBytes", "replaceBookId", "expectedRemoteHash"),
            gson.toJsonTree(restored).asJsonObject.keySet(),
        )
    }

    @Test
    fun legacySnapshotWithoutCacheBookRemainsReadable() {
        val restored = gson.fromJson(
            """{"bookUrl":"old-book","book":{"bookUrl":"old-book","name":"旧任务"},
                "chapters":[{"url":"chapter-1","title":"第一章","index":0}],
                "clientSourceKey":"source-key","configurationFingerprint":"fingerprint",
                "maxUploadBytes":1073741824}""",
            NasComicUploadRepository.ComicSnapshot::class.java,
        )

        assertEquals("旧任务", restored.book.name)
        assertEquals("chapter-1", restored.chapters.single().url)
        assertEquals("source-key", restored.clientSourceKey)
        assertEquals("fingerprint", restored.configurationFingerprint)
        assertEquals(1073741824L, restored.maxUploadBytes)
        assertNull(restored.cacheBook)
        assertNull(restored.replaceBookId)
        assertNull(restored.expectedRemoteHash)
    }

    @Test
    fun replacementRequiresTheOriginalHashEvenWhenTheIdIsUnchanged() {
        val hash = "a".repeat(64)
        val approved = NasBookDetail(id = "same-book", contentHash = hash.uppercase())
        assertTrue(matchesComicReplacement(approved.id, hash, approved))
        assertFalse(matchesComicReplacement(approved.id, hash, approved.copy(contentHash = "b".repeat(64))))
        assertFalse(matchesComicReplacement(approved.id, hash, approved.copy(contentHash = "")))
        assertFalse(matchesComicReplacement(approved.id, null, approved))
        assertFalse(matchesComicReplacement("other-book", hash, approved))
        assertFalse(matchesComicReplacement(null, null, approved))
        assertTrue(matchesComicReplacement(null, null, null))
    }
}
