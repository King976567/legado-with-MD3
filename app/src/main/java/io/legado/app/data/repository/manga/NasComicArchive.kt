package io.legado.app.data.repository.manga

import java.io.FilterOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class NasComicArchiveChapter(
    val title: String,
    val pages: List<File>,
)

internal data class NasComicArchiveResult(
    val file: File,
    val size: Long,
    val sha256: String,
    val firstPage: File,
)

internal fun nasComicSourceKey(origin: String, bookUrl: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$origin\u0000$bookUrl".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun sanitizeCbzPathSegment(raw: String, fallback: String): String {
    val cleaned = buildString {
        raw.trim().forEach { char ->
            append(if (char.code < 32 || char in "<>:\"/\\|?*") '_' else char)
        }
    }.trim('.', ' ')
    return cleaned.take(120).ifBlank { fallback }
}

internal fun buildComicInfoXml(
    title: String,
    author: String,
    summary: String,
    chapterCount: Int,
    pageCount: Int,
): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
    append("<ComicInfo>\n")
    append("  <Title>").append(xmlEscape(title)).append("</Title>\n")
    append("  <Writer>").append(xmlEscape(author)).append("</Writer>\n")
    append("  <Summary>").append(xmlEscape(summary)).append("</Summary>\n")
    append("  <PageCount>").append(pageCount).append("</PageCount>\n")
    append("  <ChapterCount>").append(chapterCount).append("</ChapterCount>\n")
    append("</ComicInfo>\n")
}

internal object NasComicCbzBuilder {
    fun build(
        output: File,
        title: String,
        author: String,
        summary: String,
        chapters: List<NasComicArchiveChapter>,
        maxBytes: Long,
        onPage: (chapter: Int, chapterCount: Int, page: Int, pageCount: Int) -> Unit = { _, _, _, _ -> },
    ): NasComicArchiveResult {
        require(chapters.isNotEmpty() && chapters.all { it.pages.isNotEmpty() })
        val allPages = chapters.flatMap { it.pages }
        require(allPages.all(File::isFile))
        val required = allPages.sumOf(File::length).coerceAtLeast(1L)
        if (output.parentFile?.usableSpace?.let { it <= required + MIN_FREE_BYTES } == true) {
            throw NasComicArchiveException(NasComicArchiveError.StorageFull)
        }
        output.parentFile?.mkdirs()
        val partial = File(output.parentFile, output.name + ".partial")
        partial.delete()
        try {
            val counting = CountingOutputStream(FileOutputStream(partial))
            ZipOutputStream(counting).use { zip ->
                writeEntry(
                    zip,
                    "ComicInfo.xml",
                    buildComicInfoXml(title, author, summary, chapters.size, allPages.size)
                        .toByteArray(Charsets.UTF_8),
                )
                chapters.forEachIndexed { chapterIndex, chapter ->
                    val folder = "%04d_%s".format(
                        chapterIndex + 1,
                        sanitizeCbzPathSegment(chapter.title, "chapter-${chapterIndex + 1}"),
                    )
                    chapter.pages.forEachIndexed { pageIndex, page ->
                        // Source image URLs often end in .php/.ashx or have no
                        // extension at all. The bytes are still valid images,
                        // but LocalBook uses the archive entry suffix to detect
                        // comic pages, so keep only extensions it recognizes.
                        val ext = page.extension.lowercase()
                            .takeIf { it in SUPPORTED_IMAGE_EXTENSIONS }
                            ?: "jpg"
                        val entry = ZipEntry("$folder/${"%04d".format(pageIndex + 1)}.$ext").apply { time = 0L }
                        zip.putNextEntry(entry)
                        page.inputStream().use { it.copyTo(zip, DEFAULT_BUFFER_SIZE) }
                        zip.closeEntry()
                        if (maxBytes > 0 && counting.count > maxBytes) {
                            throw NasComicArchiveException(NasComicArchiveError.TooLarge)
                        }
                        onPage(chapterIndex + 1, chapters.size, pageIndex + 1, chapter.pages.size)
                    }
                }
            }
            if (maxBytes > 0 && partial.length() > maxBytes) {
                throw NasComicArchiveException(NasComicArchiveError.TooLarge)
            }
            output.delete()
            if (!partial.renameTo(output)) {
                partial.copyTo(output, overwrite = true)
                partial.delete()
            }
            return NasComicArchiveResult(
                file = output,
                size = output.length(),
                sha256 = sha256(output),
                firstPage = allPages.first(),
            )
        } catch (error: Throwable) {
            partial.delete()
            output.delete()
            throw error
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val MIN_FREE_BYTES = 32L * 1024 * 1024
    private val SUPPORTED_IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "avif", "bmp",
    )
}

internal enum class NasComicArchiveError { StorageFull, TooLarge }

internal class NasComicArchiveException(val reason: NasComicArchiveError) : Exception(reason.name)

private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var count: Long = 0
        private set

    override fun write(value: Int) {
        out.write(value)
        count++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        count += length
    }
}

private fun xmlEscape(value: String): String = buildString(value.length) {
    value.forEach { char ->
        append(
            when (char) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '\"' -> "&quot;"
                '\'' -> "&apos;"
                else -> char
            },
        )
    }
}
