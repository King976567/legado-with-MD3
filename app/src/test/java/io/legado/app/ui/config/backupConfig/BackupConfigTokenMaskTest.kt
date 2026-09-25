package io.legado.app.ui.config.backupConfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupConfigTokenMaskTest {

    @Test
    fun `empty token has no summary`() {
        assertEquals("", maskNasToken("   "))
    }

    @Test
    fun `short token is fully masked`() {
        val masked = maskNasToken("abcd")

        assertEquals("\u2022".repeat(8), masked)
        assertFalse(masked.contains("abcd"))
    }

    @Test
    fun `long token keeps only last four characters`() {
        val masked = maskNasToken("secret-token")

        assertEquals("\u2022".repeat(8) + "oken", masked)
        assertFalse(masked.contains("secret"))
    }
}
