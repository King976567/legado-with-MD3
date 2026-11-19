package io.legado.app.data.repository

import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Logical bytes of regular files in the book's cache, without following symbolic links. */
internal suspend fun bookCacheFileBytes(root: Path): Long? {
    val context = currentCoroutineContext()
    var bytes = 0L
    return try {
        val rootAttributes = try {
            Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return 0L
        }
        if (!rootAttributes.isDirectory) return 0L
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                context.ensureActive()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                context.ensureActive()
                if (attrs.isRegularFile) bytes += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                context.ensureActive()
                // Online cache files can disappear as we visit the directory.
                if (exc !is NoSuchFileException) throw exc
                return FileVisitResult.CONTINUE
            }
        })
        bytes
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

/** Only inspect the cache layout used by BookHelp, not the contents/size of every image. */
internal fun hasBookCacheFiles(root: Path): Boolean {
    fun hasChild(path: Path, predicate: (Path) -> Boolean): Boolean = try {
        Files.isDirectory(path, NOFOLLOW_LINKS) && Files.newDirectoryStream(path).use { entries ->
            entries.any(predicate)
        }
    } catch (_: IOException) {
        false
    } catch (_: java.nio.file.DirectoryIteratorException) {
        false
    } catch (_: SecurityException) {
        false
    }
    val isFile = { path: Path -> Files.isRegularFile(path, NOFOLLOW_LINKS) }
    return hasChild(root) { entry ->
        isFile(entry) || hasChild(entry) { child ->
            isFile(child) || (child.fileName.toString() == "images" && hasChild(child, isFile))
        }
    }
}
