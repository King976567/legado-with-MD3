package io.legado.app.domain.model

/** A book returned by the legado-nas-indexer library API. */
data class NasBook(
    val id: String,
    val relativePath: String = "",
    val fileName: String = "",
    val title: String = "",
    val author: String? = null,
    val intro: String? = null,
    val size: Long = 0,
    val lastModified: Long = 0,
    val indexedAt: Long = 0,
    val scrapeStatus: String = "",
    val manualConfirmed: Boolean = false,
    val suggestedTitle: String? = null,
    val suggestedAuthor: String? = null,
    val suggestedRelativePath: String? = null,
    val confidence: Double = 0.0,
    val needsReview: Boolean = false,
    val coverUrl: String? = null,
    val contentHash: String = "",
) {
    val directoryPath: String
        get() = relativePath.substringBeforeLast('/', "")

    val displayTitle: String
        get() = title.ifBlank { fileName.substringBeforeLast('.') }
}

typealias NasSearchBook = NasBook

data class NasBookDetail(
    val id: String,
    val relativePath: String = "",
    val fileName: String = "",
    val title: String = "",
    val author: String? = null,
    val intro: String? = null,
    val size: Long = 0,
    val lastModified: Long = 0,
    val indexedAt: Long = 0,
    val scrapeStatus: String = "",
    val manualConfirmed: Boolean = false,
    val suggestedTitle: String? = null,
    val suggestedAuthor: String? = null,
    val suggestedRelativePath: String? = null,
    val confidence: Double = 0.0,
    val needsReview: Boolean = false,
    val coverUrl: String? = null,
) {
    fun toBook() = NasBook(
        id = id,
        relativePath = relativePath,
        fileName = fileName,
        title = title,
        author = author,
        intro = intro,
        size = size,
        lastModified = lastModified,
        indexedAt = indexedAt,
        scrapeStatus = scrapeStatus,
        manualConfirmed = manualConfirmed,
        suggestedTitle = suggestedTitle,
        suggestedAuthor = suggestedAuthor,
        suggestedRelativePath = suggestedRelativePath,
        confidence = confidence,
        needsReview = needsReview,
        coverUrl = coverUrl,
    )
}

data class NasBookPage(
    val items: List<NasBook>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
)

data class NasHealth(
    val status: String = "",
    val service: String? = null,
    val version: String? = null,
    val authRequired: Boolean = false,
    val features: List<String> = emptyList(),
)

data class NasCapabilityPath(
    val path: String = "",
    val configured: Boolean = false,
    val exists: Boolean = false,
    val isDir: Boolean = false,
    val readable: Boolean = false,
    val writable: Boolean = false,
    val error: String? = null,
)

data class NasCapabilities(
    val features: Set<String> = emptySet(),
    val service: String = "",
    val version: String = "",
    val defaultUploadDirectory: String = "",
    val paths: Map<String, NasCapabilityPath> = emptyMap(),
) {
    fun supports(feature: String): Boolean = feature in features

    val supportsIndexer: Boolean get() = supports("indexer")
    val supportsLibraryDirectories: Boolean get() = supports("libraryDirectories")
}

enum class NasWriteAccess { ALLOWED, DENIED, UNKNOWN }

data class NasConnection(
    val health: NasHealth,
    val capabilities: NasCapabilities,
    val writeAccess: NasWriteAccess = NasWriteAccess.UNKNOWN,
)

enum class NasCheckStatus { PASS, FAIL, SKIP }

enum class NasCheckKind { URL, HEALTH, AUTH, BOOKS, CAPABILITIES }

data class NasDiagnosticStep(
    val kind: NasCheckKind,
    val status: NasCheckStatus,
    val detail: String? = null,
)

data class NasDiagnosticReport(
    val success: Boolean,
    val steps: List<NasDiagnosticStep>,
    val connection: NasConnection? = null,
    val normalizedUrl: String? = null,
)

data class NasDirectory(
    val id: Long = 0,
    val name: String = "",
    val directoryPath: String = "",
    val scrapeMode: String = "",
    val enabled: Boolean = true,
    val bookCount: Long = 0,
) {
    val displayName: String get() = name.ifBlank { directoryPath }
}

data class NasTask(
    val id: String = "",
    val kind: String = "",
    val type: String? = null,
    val status: String = "",
    val total: Int = 0,
    val processed: Int = 0,
    val success: Int = 0,
    val failed: Int = 0,
    val current: String? = null,
    val lastError: String? = null,
    val cancelRequested: Boolean = false,
    val startedAt: String? = null,
    val finishedAt: String? = null,
)

data class NasTaskStatus(
    val taskId: String? = null,
    val status: String = "",
    val total: Int = 0,
    val processed: Int = 0,
    val success: Int = 0,
    val failed: Int = 0,
    val current: String? = null,
    val lastError: String? = null,
)

data class NasMoveResult(
    val status: String = "",
    val book: NasBook? = null,
) {
    val isDuplicate: Boolean get() = status.equals("duplicate", ignoreCase = true)
    val isSuccess: Boolean
        get() = status.lowercase() in setOf("moved", "skipped", "overwritten")
}

data class NasUploadResult(
    val uploaded: List<NasBook> = emptyList(),
    val duplicates: List<NasBook> = emptyList(),
    val skipped: List<NasBook> = emptyList(),
    val overwritten: List<NasBook> = emptyList(),
    val failed: List<String> = emptyList(),
) {
    val hasDuplicate: Boolean get() = duplicates.isNotEmpty()
    val primaryBook: NasBook?
        get() = uploaded.firstOrNull() ?: overwritten.firstOrNull() ?: skipped.firstOrNull()
}

/** HTTP failures are kept typed so the UI can distinguish auth from offline errors. */
open class NasHttpException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val isUnauthorized: Boolean get() = statusCode == 401
    val isForbidden: Boolean get() = statusCode == 403
}

/**
 * A task submission was rejected because an equivalent task is already
 * running. The indexer API returns the current task in either a `task` or a
 * `status` envelope; keeping it on the exception lets the UI refresh or show
 * useful progress instead of reporting a false successful submission.
 */
class NasTaskConflictException(
    message: String,
    val currentTask: NasTaskStatus? = null,
) : NasHttpException(409, message)
