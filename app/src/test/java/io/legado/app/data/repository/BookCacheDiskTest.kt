package io.legado.app.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookCacheDiskTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun countsLegacyAndCurrentCacheFilesAndDoesNotFollowSymlinks() = runTest {
        val root = folder.newFolder("cache")
        val outside = folder.newFile("nas-original").apply { writeBytes(ByteArray(999)) }
        root.resolve("legacy.nb").writeBytes(ByteArray(7))
        root.resolve("chapter/images").mkdirs()
        root.resolve("chapter/images/page.jpg").writeBytes(ByteArray(133))
        Files.createSymbolicLink(root.resolve("external").toPath(), outside.toPath())
        assertEquals(140L, bookCacheFileBytes(root.toPath()))
        assertEquals(0L, bookCacheFileBytes(root.resolve("missing").toPath()))
        assertFalse(root.resolve("missing").exists())
    }

    @Test
    fun imageOnlyCacheRemainsVisibleButEmptyDirectoriesDoNot() = runTest {
        val root = folder.newFolder("cache")
        val current = root.resolve("chapter/images").apply { mkdirs() }
        val legacy = root.resolve("images").apply { mkdirs() }
        assertFalse(hasBookCacheFiles(root.toPath()))
        assertEquals(0L, bookCacheFileBytes(root.toPath()))
        current.resolve("one.jpg").writeBytes(ByteArray(2))
        assertTrue(hasBookCacheFiles(root.toPath()))
        assertEquals(2L, bookCacheFileBytes(root.toPath()))
        current.resolve("one.jpg").delete()
        legacy.resolve("two.jpg").writeBytes(ByteArray(5))
        assertTrue(hasBookCacheFiles(root.toPath()))
    }

    @Test
    fun summaryMetadataSurvivesRoundTripWithoutSavingBookDetails() {
        val identity = CacheBookIdentity("https://example.test/private", "Private title", "Author", "root", "origin", 1, 0L, 8, 80L)
        val key = cacheSummaryKey(identity.bookUrl)
        val token = cacheSummaryIdentity(identity)
        assertNotEquals(token, cacheSummaryIdentity(identity.copy(latestChapterTime = 81L)))
        assertNotEquals(token, cacheSummaryIdentity(identity.copy(folderName = "new")))
        val expected = mapOf(key to BookCacheSummaryEntry(token, BookCacheSavedCount(7, 3)))
        val bytes = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { writeBookCacheSummaries(it, expected) } }.toByteArray()
        assertEquals(expected, DataInputStream(ByteArrayInputStream(bytes)).use(::readBookCacheSummaries))
        assertFalse(bytes.decodeToString().contains(identity.bookUrl))
        assertFalse(bytes.decodeToString().contains(identity.name))
        assertThrows(IOException::class.java) {
            DataInputStream(ByteArrayInputStream(bytes.copyOf(bytes.size - 2))).use(::readBookCacheSummaries)
        }
    }
}
