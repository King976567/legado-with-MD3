package io.legado.app.domain.model

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NasTransferHistoryJsonTest {
    // Model fields may be renamed by R8. SerializedName must remain the disk contract
    // even if the runtime's field-name policy changes; a plain round trip would miss this.
    private val gson = GsonBuilder()
        .setFieldNamingStrategy { field -> "renamed_${field.name}" }
        .create()

    @Test
    fun persistedHistoryUsesStableKeysAndReadsExistingJson() {
        val existingJson = """
            {"id":"transfer-42","direction":"upload","title":"漫画历史",
             "kind":"comic","state":"SUCCEEDED","message":"封面已上传",
             "remotePath":"漫画/漫画历史.cbz","retryKey":"source-key",
             "replaceBookId":"book-17","createdAt":1728000000000,"finishedAt":1728000123456}
        """.trimIndent()

        val restored = gson.fromJson(existingJson, NasTransferHistory::class.java)

        assertEquals(
            NasTransferHistory(
                id = "transfer-42", direction = "upload", title = "漫画历史",
                kind = "comic", state = "SUCCEEDED", message = "封面已上传",
                remotePath = "漫画/漫画历史.cbz", retryKey = "source-key",
                replaceBookId = "book-17", createdAt = 1728000000000L,
                finishedAt = 1728000123456L,
            ),
            restored,
        )
        assertEquals(
            setOf("id", "direction", "title", "kind", "state", "message", "remotePath",
                "retryKey", "replaceBookId", "createdAt", "finishedAt"),
            gson.toJsonTree(restored).asJsonObject.keySet(),
        )
    }

    @Test
    fun legacyHistoryWithoutOptionalFieldsIsStillReadable() {
        val restored = gson.fromJson(
            """{"id":"download-1","direction":"download","title":"旧书",
                "kind":"book","state":"SUCCEEDED","createdAt":1728000000000}""",
            NasTransferHistory::class.java,
        )

        assertEquals("download-1", restored.id)
        assertEquals("旧书", restored.title)
        assertEquals(1728000000000L, restored.createdAt)
        assertNull(restored.message)
        assertNull(restored.retryKey)
        assertNull(restored.replaceBookId)
        assertNull(restored.finishedAt)
    }
}
