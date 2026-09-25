package io.legado.app.ui.main.nas

import org.junit.Assert.assertEquals
import org.junit.Test

class NasUploadFileNameTest {

    @Test
    fun `provider path components are reduced to a safe basename`() {
        assertEquals("book.epub", sanitizeNasUploadFileName("../../book.epub"))
        assertEquals("book.epub", sanitizeNasUploadFileName("..\\..\\book.epub"))
    }

    @Test
    fun `empty and dot names use a stable fallback`() {
        assertEquals("nas-upload.bin", sanitizeNasUploadFileName(""))
        assertEquals("nas-upload.bin", sanitizeNasUploadFileName(".."))
        assertEquals("nas-upload.bin", sanitizeNasUploadFileName("   "))
    }

    @Test
    fun `control and reserved filename characters are replaced`() {
        assertEquals("book_name_.epub", sanitizeNasUploadFileName("book:name?.epub"))
        assertEquals("book_name.epub", sanitizeNasUploadFileName("book\u0000name.epub"))
    }
}
