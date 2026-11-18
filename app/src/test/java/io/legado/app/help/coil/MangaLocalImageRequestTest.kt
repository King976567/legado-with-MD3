package io.legado.app.help.coil

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import coil3.ImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Dimension
import coil3.size.Size
import io.legado.app.ui.book.manga.MangaReaderItemUi
import io.legado.app.ui.book.manga.MangaReaderSettings
import io.legado.app.ui.book.manga.config.MangaScrollMode
import io.legado.app.ui.book.manga.imageRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MangaLocalImageRequestTest {
    @Test
    fun `SAF long pages keep original proportions before bounded preview is displayed`() = runBlocking {
        val root = Files.createTempDirectory("saf-manga-request").toFile()
        val image = File(root, "page.png")
        val context = RuntimeEnvironment.getApplication<Application>()
        val authority = "io.legado.test.manga.original"
        val uri = Uri.parse("content://$authority/pages/1")
        writeLongPage(image)
        val provider = PageProvider(image).apply {
            attachInfo(context, ProviderInfo().apply { this.authority = authority })
        }
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val loader = localLoader()
        try {
            for (mode in listOf(MangaScrollMode.WEBTOON, MangaScrollMode.PAGE_LEFT_TO_RIGHT)) {
                val owner = MangaImageFileOwner()
                val ratios = mutableListOf<Float>()
                var ratioAtSuccess: Float? = null
                try {
                    val request = page(uri.toString()).imageRequest(
                        MangaReaderSettings(scrollMode = mode), context,
                        Size(Dimension(64), Dimension.Undefined), owner,
                        onAspectRatio = { ratios += it },
                        onSuccess = { ratioAtSuccess = owner.aspectRatio() },
                    ).newBuilder().allowHardware(false).build()
                    // SAF stays on the upstream string-URI path used to read original dimensions.
                    assertEquals(uri.toString(), request.data)
                    val result = loader.execute(request)
                    if (result is ErrorResult) throw result.throwable
                    require(result is SuccessResult)
                    assertTrue("The display preview must be downsampled", result.image.height < HEIGHT)
                    assertEquals(WIDTH.toFloat() / HEIGHT, ratioAtSuccess!!, 0f)
                    assertEquals(listOf(WIDTH.toFloat() / HEIGHT), ratios)
                } finally {
                    owner.close()
                }
            }
        } finally {
            loader.shutdown()
            provider.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun `NAS imported file URI preserves original geometry and encoded filename`() = runBlocking {
        val root = Files.createTempDirectory("nas-manga-request").toFile()
        val image = File(root, "第一话 #1.png")
        val owner = MangaImageFileOwner()
        val loader = localLoader()
        try {
            writeLongPage(image)
            var ratioAtSuccess: Float? = null
            val request = page(image.toURI().toString()).imageRequest(
                MangaReaderSettings(), RuntimeEnvironment.getApplication<Application>(),
                Size(Dimension(64), Dimension.Undefined), owner,
                onSuccess = { ratioAtSuccess = owner.aspectRatio() },
            ).newBuilder().allowHardware(false).build()
            assertEquals(image, request.data)
            val result = loader.execute(request)
            if (result is ErrorResult) throw result.throwable
            require(result is SuccessResult)
            assertTrue(result.image.height < HEIGHT)
            assertEquals(WIDTH.toFloat() / HEIGHT, ratioAtSuccess!!, 0f)
        } finally {
            owner.close()
            loader.shutdown()
            root.deleteRecursively()
        }
    }

    private fun localLoader() = ImageLoader.Builder(RuntimeEnvironment.getApplication<Application>())
        .components {
            add(BitmapFactoryDecoder.Factory())
            add(CoverInterceptor { _, _ -> error("Local manga must not acquire an online page") })
        }.build()

    private fun page(imageUrl: String) = MangaReaderItemUi.Page(
        key = "page:0:0:$imageUrl", imageUrl = imageUrl, bookUrl = "local-manga",
        chapterIndex = 0, chapterCount = 1, pageIndex = 0, pageCount = 1,
        chapterName = "chapter", chapterSubject = "chapter",
    )

    private fun writeLongPage(file: File) {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private class PageProvider(private val image: File) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                addRow(columns.map {
                    when (it) {
                        OpenableColumns.DISPLAY_NAME -> image.name
                        OpenableColumns.SIZE -> image.length()
                        else -> null
                    }
                }.toTypedArray())
            }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
            error("Read only")
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
        ): Int = error("Read only")
    }

    private companion object {
        const val WIDTH = 127
        const val HEIGHT = 12_001
    }
}
