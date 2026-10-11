package io.legado.app.di

import io.legado.app.help.http.OkHttpExceptionInterceptor
import okhttp3.CookieJar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NasHttpClientTest {
    @Test
    fun nasClientReportsBlockedLocalNetworkWithoutSourceInterceptors() {
        val client = createNasHttpClient()

        assertEquals(listOf(OkHttpExceptionInterceptor), client.interceptors)
        assertTrue(client.networkInterceptors.isEmpty())
    }

    @Test
    fun nasClientCannotForwardBearerTokenThroughRedirects() {
        val client = createNasHttpClient()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun nasClientDoesNotShareBookSourceCookies() {
        val client = createNasHttpClient()

        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
    }
}
