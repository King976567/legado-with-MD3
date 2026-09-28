package io.legado.app.utils

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.utils.compress.LibArchiveUtils
import splitties.init.appCtx
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/* 自动判断压缩文件后缀 然后再调用具体的实现 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
object ArchiveUtils {

    const val TEMP_FOLDER_NAME = "ArchiveTemp"

    // 临时目录 下次启动自动删除
    val TEMP_PATH: String by lazy {
        appCtx.externalCache.getFile(TEMP_FOLDER_NAME).createFolderReplace().absolutePath
    }

    fun deCompress(
        archiveUri: Uri,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(FileDoc.fromUri(archiveUri, false), path, filter)
    }

    fun deCompress(
        archivePath: String,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(Uri.parse(archivePath), path, filter)
    }

    fun deCompress(
        archiveFile: File,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(FileDoc.fromFile(archiveFile), path, filter)
    }

    fun deCompress(
        archiveDoc: DocumentFile,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(FileDoc.fromDocumentFile(archiveDoc), path, filter)
    }

    fun deCompress(
        archiveFileDoc: FileDoc,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        if (archiveFileDoc.isDir) throw IllegalArgumentException("Unexpected Folder input")
        val name = archiveFileDoc.name
        checkAchieve(name)
        val workPathFileDoc = getCacheFolderFileDoc(name, path)
        val workPath = workPathFileDoc.toString()

        // CBZ is a ZIP container. Some Android document providers expose a
        // seekable descriptor that libarchive can open but cannot enumerate
        // consistently, so use the platform ZIP reader as the primary path.
        if (name.endsWith(".cbz", ignoreCase = true)) {
            return unzip(archiveFileDoc, File(workPath), filter)
        }

        val primary = runCatching {
            archiveFileDoc.openReadPfd().getOrThrow().use {
                LibArchiveUtils.unArchive(it, File(workPath), filter)
            }
        }
        if (primary.isSuccess && primary.getOrThrow().isNotEmpty()) {
            return primary.getOrThrow()
        }
        if (!isZipArchive(name)) {
            return primary.getOrThrow()
        }

        // Some Android document providers expose a valid ZIP/CBZ through a
        // seekable descriptor that libarchive cannot enumerate reliably. ZIP
        // is the only archive format that needs a portable fallback here.
        File(workPath).deleteRecursively()
        return runCatching {
            unzip(archiveFileDoc, File(workPath), filter)
        }.getOrElse { fallbackError ->
            primary.exceptionOrNull()?.let { throw it }
            throw fallbackError
        }

    }

    /* 遍历目录获取文件名 */
    fun getArchiveFilesName(fileUri: Uri, filter: ((String) -> Boolean)? = null): List<String> =
        getArchiveFilesName(FileDoc.fromUri(fileUri, false), filter)


    fun getArchiveFilesName(
        fileDoc: FileDoc,
        filter: ((String) -> Boolean)? = null
    ): List<String> {
        val name = fileDoc.name
        checkAchieve(name)

        val primary = runCatching {
            fileDoc.openReadPfd().getOrThrow().use {
                LibArchiveUtils.getFilesName(it, filter)
            }
        }
        if (primary.isSuccess && primary.getOrThrow().isNotEmpty()) {
            return primary.getOrThrow()
        }
        if (!isZipArchive(name)) {
            return primary.getOrDefault(emptyList())
        }

        return runCatching {
            zipFileNames(fileDoc, filter)
        }.getOrElse { fallbackError ->
            primary.exceptionOrNull()?.let { throw it }
            throw fallbackError
        }
    }

    fun isArchive(name: String): Boolean {
        return archiveFileRegex.matches(name)
    }

    private fun checkAchieve(name: String) {
        if (!isArchive(name))
            throw IllegalArgumentException("Unexpected file suffix: Only 7z rar zip Accepted")
    }

    private fun isZipArchive(name: String): Boolean =
        name.endsWith(".zip", ignoreCase = true) ||
                name.endsWith(".cbz", ignoreCase = true)

    private fun zipFileNames(
        fileDoc: FileDoc,
        filter: ((String) -> Boolean)? = null,
    ): List<String> {
        val names = ArrayList<String>()
        fileDoc.openInputStream().getOrThrow().use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    if (!entry.isDirectory && (filter == null || filter(name))) {
                        names += name
                    }
                    zip.closeEntry()
                }
            }
        }
        return names
    }

    private fun unzip(
        fileDoc: FileDoc,
        destination: File,
        filter: ((String) -> Boolean)? = null,
    ): List<File> {
        val files = ArrayList<File>()
        val root = destination.canonicalFile
        root.mkdirs()
        fileDoc.openInputStream().getOrThrow().use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val entryName = entry.name.replace('\\', '/')
                    val output = File(root, entryName).canonicalFile
                    if (output != root &&
                        !output.path.startsWith(root.path + File.separator)
                    ) {
                        throw SecurityException("压缩文件只能解压到指定路径")
                    }
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else if (filter == null || filter(entryName)) {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).use { target -> zip.copyTo(target) }
                        files += output
                    }
                    zip.closeEntry()
                }
            }
        }
        return files
    }

    private fun getCacheFolderFileDoc(
        archiveName: String,
        workPath: String
    ): FileDoc {
        return FileDoc.fromUri(Uri.parse(workPath), true)
            .createFolderIfNotExist(MD5Utils.md5Encode16(archiveName))
    }
}
