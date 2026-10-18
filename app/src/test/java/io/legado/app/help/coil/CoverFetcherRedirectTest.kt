package io.legado.app.help.coil

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CoverFetcherRedirectTest {
    @Test
    fun `authorized cover requests remove Cronet and reject cross-origin redirects`() {
        val nas = MockWebServer()
        val redirectTarget = MockWebServer()
        nas.start()
        redirectTarget.start()
        try {
            nas.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .setHeader("Location", redirectTarget.url("/collect-token").toString())
                    .build(),
            )
            val cronetInterceptor = Interceptor { chain -> chain.proceed(chain.request()) }
            val sourceClient = OkHttpClient.Builder()
                .addInterceptor(cronetInterceptor)
                .build()

            val client = authenticatedCoverCallFactory(sourceClient, cronetInterceptor)
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
            assertTrue(client.interceptors.none { it === cronetInterceptor })

            val response = client.newCall(
                Request.Builder()
                    .url(nas.url("/covers/book.jpg"))
                    .header("Authorization", "Bearer nas-token")
                    .build(),
            ).execute()
            response.use {
                assertEquals(302, it.code)
                assertEquals("Bearer nas-token", nas.takeRequest().headers["Authorization"])
                assertEquals(0, redirectTarget.requestCount)
            }
        } finally {
            nas.close()
            redirectTarget.close()
        }
    }

    @Test
    fun `authorized cover requests without Cronet still disable redirects`() {
        val sourceInterceptor = Interceptor { chain -> chain.proceed(chain.request()) }
        val sourceClient = OkHttpClient.Builder()
            .addInterceptor(sourceInterceptor)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val client = authenticatedCoverCallFactory(sourceClient, null)

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertTrue(client.interceptors.any { it === sourceInterceptor })
        assertTrue(sourceClient.followRedirects)
        assertTrue(sourceClient.followSslRedirects)
    }

    @Test
    fun `unsupported authorized cover factory fails closed without creating a call`() {
        var callCreated = false
        val unsupportedFactory = object : Call.Factory {
            override fun newCall(request: Request): Call {
                callCreated = true
                throw AssertionError("Unsupported factory must not receive authenticated requests")
            }
        }

        assertThrows(IOException::class.java) {
            authenticatedCoverCallFactory(unsupportedFactory, null)
        }
        assertFalse(callCreated)
    }
}
