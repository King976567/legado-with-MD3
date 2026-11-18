package io.legado.app.help.coil

import android.app.Application
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CoverInterceptorNasTest {
    @Test
    fun `final NAS cover URL and bearer headers reach the fetcher unchanged`() = runTest {
        val url = "https://nas.example/api/books/1/cover"
        val headers = mapOf("Authorization" to "Bearer fixture-token")
        val application = RuntimeEnvironment.getApplication()
        val request = ImageRequest.Builder(application)
            .data(url)
            .apply {
                extras[CoverExtras.SkipAnalysis] = true
                extras[CoverExtras.Headers] = headers
                extras[CoverExtras.SourceOrigin] = "must-not-be-resolved"
                extras[CoverExtras.PreferCache] = true
                extras[CoverExtras.BookUrl] = "nas-cover-fixture"
            }.build()
        var interceptedRequest: ImageRequest? = null
        val intercepted = IllegalStateException("Stopped by test interceptor")
        val loader = ImageLoader.Builder(application).components {
            add(CoverInterceptor())
            add(Interceptor { chain ->
                interceptedRequest = chain.request
                ErrorResult(image = null, request = chain.request, throwable = intercepted)
            })
        }.build()

        try {
            val result = loader.execute(request)
            assertSame(intercepted, (result as ErrorResult).throwable)
            val forwarded = requireNotNull(interceptedRequest)
            assertEquals(url, forwarded.data)
            assertEquals(headers, forwarded.extras[CoverExtras.Headers])
        } finally {
            loader.shutdown()
        }
    }
}
