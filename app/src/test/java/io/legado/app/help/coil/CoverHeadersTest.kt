package io.legado.app.help.coil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverHeadersTest {

    @Test
    fun `NAS token is attached only to the configured origin`() {
        assertEquals(
            mapOf("Authorization" to "Bearer secret"),
            nasCoverHeaders(
                coverUrl = "https://nas.example/covers/book.jpg",
                nasApiUrl = "https://nas.example/api",
                token = " secret ",
            ),
        )
        assertTrue(
            nasCoverHeaders(
                coverUrl = "https://cdn.example/book.jpg",
                nasApiUrl = "https://nas.example/api",
                token = "secret",
            ).isEmpty(),
        )
        assertTrue(
            nasCoverHeaders(
                coverUrl = "https://nas.example/admin/config.json",
                nasApiUrl = "https://nas.example/api",
                token = "secret",
            ).isEmpty(),
        )
    }

    @Test
    fun `NAS token is not emitted for malformed or control character values`() {
        assertTrue(nasCoverHeaders("not a url", "https://nas.example/api", "secret").isEmpty())
        assertTrue(nasCoverHeaders("https://nas.example/c.jpg", "https://nas.example/api", "bad\nvalue").isEmpty())
    }

    @Test
    fun `explicit headers override resolved headers case insensitively`() {
        assertEquals(
            mapOf(
                "Accept" to "*/*",
                "authorization" to "Bearer nas-token",
            ),
            mergeCoverHeaders(
                resolved = mapOf("Accept" to "*/*", "Authorization" to "Bearer source"),
                explicit = mapOf("authorization" to "Bearer nas-token"),
            ),
        )
    }
}
