package io.legado.app.domain.model

/** A durable NAS transfer record shown in Cache Management history. */
data class NasTransferHistory(
    val id: String,
    val direction: String,
    val title: String,
    val kind: String,
    val state: String,
    val message: String? = null,
    val remotePath: String? = null,
    val retryKey: String? = null,
    val replaceBookId: String? = null,
    val createdAt: Long = 0L,
    val finishedAt: Long? = null,
)
