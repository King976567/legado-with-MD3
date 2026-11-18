package io.legado.app.help.coil

import android.app.Application
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
        val request = ImageRequest.Builder(RuntimeEnvironment.getApplication<Application>())
            .data(url)
            .apply {
                extras[CoverExtras.SkipAnalysis] = true
                extras[CoverExtras.Headers] = headers
                extras[CoverExtras.SourceOrigin] = "must-not-be-resolved"
                extras[CoverExtras.PreferCache] = true
                extras[CoverExtras.BookUrl] = "nas-cover-fixture"
            }.build()
        val result = ErrorResult(request = request, throwable = IllegalStateException("fixture"))
        val chain = mockk<Interceptor.Chain>()
        every { chain.request } returns request
        coEvery { chain.proceed() } returns result

        assertSame(result, CoverInterceptor().intercept(chain))
        assertEquals(url, request.data)
        assertEquals(headers, request.extras[CoverExtras.Headers])
        coVerify(exactly = 1) { chain.proceed() }
    }
}
