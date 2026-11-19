package io.legado.app.data.repository

import io.legado.app.domain.model.NasBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NasBookDownloadRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun epubIsNotAComicEvenWhenItsContainerHasImages() {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("<container/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("cover.jpg"))
            zip.write(byteArrayOf(0, 1))
            zip.closeEntry()
        }
        val declared = nasDownloadFormat(NasBook("a", fileName = "Novel.epub", chapterCount = 12), file)
        assertEquals(NasDownloadFormat("Novel.epub", false), declared)
        assertEquals(NasDownloadFormat("Novel.epub", false), nasDownloadFormat(NasBook("a", fileName = "Novel"), file))
    }

    @Test fun sameNameDifferentServerBookOrRevisionCannotShareAFile() {
        val root = temporary.root
        val first = nasDownloadTarget(root, "https://a.invalid/api", "id1", "123", "Volume.cbz")
        val alternateBook = nasDownloadTarget(root, "https://a.invalid/api", "id2", "123", "Volume.cbz")
        val alternateServer = nasDownloadTarget(root, "https://b.invalid/api", "id1", "123", "Volume.cbz")
        val alternateVersion = nasDownloadTarget(root, "https://a.invalid/api", "id1", "227", "Volume.cbz")
        assertEquals(4, setOf(first, alternateBook, alternateServer, alternateVersion).size)
        assertEquals(first, nasDownloadTarget(root, "https://a.invalid/api", "id1", "123", "Volume.cbz"))
        assertNotEquals(first.parentFile, alternateBook.parentFile)
    }

    @Test fun unsafeAndOversizeNamesRemainBasenames() {
        val longName = "a/b\u0000" + "漫画".repeat(120) + ".cbz"
        val name = nasDownloadFormat(NasBook("id", fileName = longName)).fileName
        assertTrue(name.endsWith(".cbz"))
        assertTrue(name.toByteArray().size < 255)
        assertTrue('/' !in name && '\u0000' !in name)
    }
}
