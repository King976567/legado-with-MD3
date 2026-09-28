package io.legado.app.domain.model

/** A WorkManager-backed NAS upload item shown in Cache Management. */
data class NasUploadTask(
    val id: String,
    val title: String,
    val kind: String,
    val state: String,
    val stage: String? = null,
    val current: Int = 0,
    val total: Int = 0,
    val chapter: Int = 0,
    val chapterCount: Int = 0,
    val page: Int = 0,
    val pageCount: Int = 0,
    val cacheReused: Int = 0,
    val downloaded: Int = 0,
    val message: String? = null,
    val remotePath: String? = null,
    val retryKey: String? = null,
    val replaceBookId: String? = null,
    val createdAt: Long = 0L,
) {
    val isRunning: Boolean
        get() = state == "ENQUEUED" || state == "BLOCKED" || state == "RUNNING"

    val isFinished: Boolean
        get() = state == "SUCCEEDED" || state == "FAILED" || state == "CANCELLED"
}
