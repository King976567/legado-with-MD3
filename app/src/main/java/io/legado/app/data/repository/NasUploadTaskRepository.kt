package io.legado.app.data.repository

import android.app.Application
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.legado.app.data.repository.manga.NasComicUploadRepository
import io.legado.app.domain.model.NasUploadTask
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.UUID

class NasUploadTaskRepository(application: Application) {
    private val application = application
    private val workManager by lazy(LazyThreadSafetyMode.NONE) {
        WorkManager.getInstance(application)
    }

    fun observe(): Flow<List<NasUploadTask>> = callbackFlow {
        val liveData = workManager.getWorkInfosByTagLiveData(TAG)
        val observer = Observer<List<WorkInfo>> { infos ->
            trySend(infos.orEmpty().map(WorkInfo::toNasUploadTask).sortedByDescending { it.id })
        }
        liveData.observeForever(observer)
        awaitClose { liveData.removeObserver(observer) }
    }.distinctUntilChanged()

    fun cancel(id: String) {
        runCatching {
            val workId = UUID.fromString(id)
            val work = workManager.getWorkInfoById(workId).get()
            work?.tags?.firstOrNull { it.startsWith(COMIC_WORK_PREFIX) }
                ?.removePrefix(COMIC_WORK_PREFIX)
                ?.let { sourceKey ->
                    java.io.File(application.noBackupFilesDir, "nas-comic/$sourceKey")
                        .listFiles().orEmpty()
                        .filterNot { it.name == NasComicUploadRepository.SNAPSHOT_FILE }
                        .forEach(java.io.File::deleteRecursively)
                }
            workManager.cancelWorkById(workId)
        }
    }

    companion object {
        const val TAG = "nas-upload"
        const val COMIC_TAG = "nas-upload-comic"
        const val TITLE_TAG_PREFIX = "nas-upload-title:"
        const val CREATED_TAG_PREFIX = "nas-upload-created:"
        const val RETRY_KEY_TAG_PREFIX = "nas-upload-retry-key:"
        const val REPLACE_BOOK_ID_TAG_PREFIX = "nas-upload-replace-id:"
        const val COMIC_WORK_PREFIX = "nas-comic:"
        const val KEY_TITLE = "nas_upload_title"
        const val KEY_KIND = "nas_upload_kind"
        const val KEY_STAGE = "stage"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_CHAPTER = "chapter"
        const val KEY_CHAPTER_COUNT = "chapter_count"
        const val KEY_PAGE = "page"
        const val KEY_PAGE_COUNT = "page_count"
        const val KEY_OVERALL_CURRENT = "overall_current"
        const val KEY_OVERALL_TOTAL = "overall_total"
        const val KEY_CACHE_REUSED = "cache_reused"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_MESSAGE = "message"
        const val KEY_REMOTE_PATH = "remote_path"
        const val KEY_CREATED_AT = "created_at"
    }
}

private fun WorkInfo.toNasUploadTask(): NasUploadTask {
    val terminal = state == WorkInfo.State.SUCCEEDED || state == WorkInfo.State.FAILED ||
        state == WorkInfo.State.CANCELLED
    val liveData = if (terminal) outputData else progress
    fun string(key: String): String? = liveData.getString(key)
    fun int(key: String, default: Int = 0): Int = liveData.getInt(key, default)
    val title = string(NasUploadTaskRepository.KEY_TITLE)
        ?: tags.firstOrNull { it.startsWith(NasUploadTaskRepository.TITLE_TAG_PREFIX) }
            ?.removePrefix(NasUploadTaskRepository.TITLE_TAG_PREFIX)
            .orEmpty()
    val kind = string(NasUploadTaskRepository.KEY_KIND)
        ?: if (tags.contains(NasUploadTaskRepository.COMIC_TAG)) "comic" else "book"
    return NasUploadTask(
        id = id.toString(),
        title = title,
        kind = kind,
        state = state.name,
        stage = string(NasUploadTaskRepository.KEY_STAGE),
        current = int(NasUploadTaskRepository.KEY_CURRENT),
        total = int(NasUploadTaskRepository.KEY_TOTAL),
        chapter = int(NasUploadTaskRepository.KEY_CHAPTER),
        chapterCount = int(NasUploadTaskRepository.KEY_CHAPTER_COUNT),
        page = int(NasUploadTaskRepository.KEY_PAGE),
        pageCount = int(NasUploadTaskRepository.KEY_PAGE_COUNT),
        cacheReused = int(NasUploadTaskRepository.KEY_CACHE_REUSED),
        downloaded = int(NasUploadTaskRepository.KEY_DOWNLOADED),
        message = string(NasUploadTaskRepository.KEY_MESSAGE),
        remotePath = string(NasUploadTaskRepository.KEY_REMOTE_PATH),
        retryKey = tags.firstOrNull { it.startsWith(NasUploadTaskRepository.RETRY_KEY_TAG_PREFIX) }
            ?.removePrefix(NasUploadTaskRepository.RETRY_KEY_TAG_PREFIX),
        replaceBookId = tags.firstOrNull { it.startsWith(NasUploadTaskRepository.REPLACE_BOOK_ID_TAG_PREFIX) }
            ?.removePrefix(NasUploadTaskRepository.REPLACE_BOOK_ID_TAG_PREFIX),
        createdAt = tags.firstOrNull { it.startsWith(NasUploadTaskRepository.CREATED_TAG_PREFIX) }
            ?.removePrefix(NasUploadTaskRepository.CREATED_TAG_PREFIX)
            ?.toLongOrNull()
            ?: 0L,
    )
}
