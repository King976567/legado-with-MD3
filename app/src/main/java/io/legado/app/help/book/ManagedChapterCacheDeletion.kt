package io.legado.app.help.book

import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal enum class ChapterCacheDeleteResult(val refreshBook: Boolean = false) {
    Cleared(true),
    TextClearedImagesKept(true),
    ImagesPartlyCleared(true),
    ImagesKept,
    Busy,
    Absent(true),
    PartlyCleared(true),
    Failed(true),
    ChapterChanged(true),
}

/** Only the explicit, chapter-scoped delete action uses this policy. Never walks a shared tree. */
internal class ManagedChapterCacheDeletion(private val store: BookImageFileStore) {
    fun delete(
        root: File,
        currentText: File,
        legacyText: File,
        imageDir: File,
        legacyImages: File,
        chapterKey: String,
        isComic: Boolean,
        reusedImages: Set<String>,
    ): ChapterCacheDeleteResult {
        val stamps = listOf(currentText, legacyText).map { File(it.path + ".ruby-v1") }
        val chapterDir = requireNotNull(currentText.parentFile)
        return store.mutateIfIdle(listOf(chapterDir, legacyText, stamps.last())) {
            val boundary = root.toPath().toAbsolutePath().normalize()
            val change = Changes()
            val images = imageDir.toPath()
            val shared = legacyImages.toPath()
            // Inspect only this chapter's originals. The old flat images directory may serve
            // any chapter, so it is never modified and is reported conservatively.
            change.attempt {
                change.imagesKept = children(boundary, shared).any { isPossibleOriginal(it) }
            }
            change.attempt {
                for (file in children(boundary, images).filter(::isPossibleOriginal)) {
                    change.attempt {
                        val owned = isComic && file.toString() !in reusedImages &&
                            checkedAttributes(boundary, file)?.isRegularFile == true &&
                            hasExclusiveIndex(boundary, file, chapterKey)
                        if (!owned) {
                            change.imagesKept = true
                        } else if (remove(boundary, file)) {
                            change.imageRemoved = true
                            // Never remove a retained original's metadata. Keep the ownership
                            // index until other sidecars are gone, so a retry can finish cleanup.
                            removeSidecars(boundary, file)
                        }
                    }
                }
            }
            change.attempt {
                val indexes = images.resolve(BookImageFileStore.DOWNLOAD_INDEX_DIRECTORY)
                if (isComic) for (index in children(boundary, indexes)) {
                    val file = images.resolve(index.fileName)
                    if (isPossibleOriginal(file) && checkedAttributes(boundary, file) == null &&
                        hasExclusiveIndex(boundary, file, chapterKey)
                    ) change.attempt {
                        removeSidecars(boundary, file)
                        change.metadataRemoved = true
                    }
                }
            }
            for ((body, stamp) in listOf(currentText, legacyText).zip(stamps)) {
                change.attempt {
                    if (remove(boundary, body.toPath())) change.textRemoved = true
                    if (remove(boundary, stamp.toPath())) change.metadataRemoved = true
                }
            }
            change.result()
        } ?: ChapterCacheDeleteResult.Busy
    }

    private fun isPossibleOriginal(path: Path): Boolean = path.fileName.toString().let {
        !it.startsWith('.') && !it.endsWith(".tmp")
    }

    private fun removeSidecars(root: Path, original: Path) {
        val imageDir = original.parent
        for (kind in listOf(BookImageFileStore.ONLINE_MARKER_DIRECTORY, ".dimensions",
            BookImageFileStore.DOWNLOAD_INDEX_DIRECTORY)) {
            remove(root, imageDir.resolve(kind).resolve(original.fileName))
        }
    }

    private fun hasExclusiveIndex(root: Path, file: Path, chapterKey: String): Boolean {
        val index = file.parent.resolve(BookImageFileStore.DOWNLOAD_INDEX_DIRECTORY).resolve(file.fileName)
        val attrs = checkedAttributes(root, index) ?: return false
        // A real index is tiny. Treat unrecognized, oversized or malformed metadata as uncertain.
        if (!attrs.isRegularFile || attrs.size() > 16 * 1024) return false
        return store.hasExclusiveDownloadChapter(file.toFile(), chapterKey)
    }

    private fun children(root: Path, dir: Path): List<Path> {
        val attrs = checkedAttributes(root, dir) ?: return emptyList()
        if (!attrs.isDirectory) throw IOException("Invalid cache directory")
        return Files.newDirectoryStream(dir).use { it.toList() }
    }

    private fun remove(root: Path, file: Path): Boolean {
        val attrs = checkedAttributes(root, file) ?: return false
        if (!attrs.isRegularFile) throw IOException("Invalid cache file")
        val deleted = Files.deleteIfExists(file)
        if (checkedAttributes(root, file) != null) throw IOException("Cache file still exists")
        return deleted
    }

    private fun checkedAttributes(root: Path, file: Path): BasicFileAttributes? {
        val path = file.toAbsolutePath().normalize()
        if (!path.startsWith(root)) throw IOException("Cache path outside root")
        // NOFOLLOW_LINKS only applies to the final segment. Check each ancestor as well.
        val attrs = if (path == root) null else checkedAttributes(root, path.parent) ?: return null
        if (attrs != null && !attrs.isDirectory) throw IOException("Invalid cache parent")
        return try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            null
        }
    }

    private class Changes {
        var textRemoved = false
        var imageRemoved = false
        var metadataRemoved = false
        var imagesKept = false
        private var failed = false

        inline fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (_: IOException) {
                failed = true
            } catch (_: DirectoryIteratorException) {
                failed = true
            } catch (_: SecurityException) {
                failed = true
            }
        }

        fun result() = when {
            failed && (textRemoved || imageRemoved || metadataRemoved) -> ChapterCacheDeleteResult.PartlyCleared
            failed -> ChapterCacheDeleteResult.Failed
            imagesKept && textRemoved -> ChapterCacheDeleteResult.TextClearedImagesKept
            imagesKept && (imageRemoved || metadataRemoved) -> ChapterCacheDeleteResult.ImagesPartlyCleared
            imagesKept -> ChapterCacheDeleteResult.ImagesKept
            textRemoved || imageRemoved || metadataRemoved -> ChapterCacheDeleteResult.Cleared
            else -> ChapterCacheDeleteResult.Absent
        }
    }
}
