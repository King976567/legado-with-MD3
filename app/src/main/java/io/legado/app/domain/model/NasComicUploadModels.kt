package io.legado.app.domain.model

enum class NasComicUploadStage {
    Refreshing,
    Downloading,
    Packaging,
    Checking,
    Uploading,
    UploadingCover,
    Complete,
}

enum class NasComicUploadFailure {
    NotConfigured,
    ReadOnly,
    UnsupportedBackend,
    SourceMissing,
    RefreshFailed,
    EmptyChapter,
    ImageFailed,
    StorageFull,
    TooLarge,
    ConfigurationChanged,
    RemoteChanged,
    Unauthorized,
    Network,
    Unknown,
}

data class NasComicUploadPreparation(
    val clientSourceKey: String,
    val title: String,
    val chapterCount: Int,
    val remoteBook: NasBookDetail? = null,
    val maxUploadBytes: Long = 0,
)

data class NasComicUploadState(
    val running: Boolean = false,
    val stage: NasComicUploadStage? = null,
    val chapter: Int = 0,
    val chapterCount: Int = 0,
    val page: Int = 0,
    val pageCount: Int = 0,
    val cacheReused: Int = 0,
    val downloaded: Int = 0,
    val failure: NasComicUploadFailure? = null,
    val message: String? = null,
    val remotePath: String? = null,
    val warning: String? = null,
)

class NasComicUploadException(
    val reason: NasComicUploadFailure,
    message: String? = null,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message ?: reason.name, cause)
