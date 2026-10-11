package io.legado.app.domain.model

import com.google.gson.annotations.SerializedName

/** A durable NAS transfer record; JSON keys must survive R8 and app upgrades. */
data class NasTransferHistory(
    @SerializedName("id") val id: String,
    @SerializedName("direction") val direction: String,
    @SerializedName("title") val title: String,
    @SerializedName("kind") val kind: String,
    @SerializedName("state") val state: String,
    @SerializedName("message") val message: String? = null,
    @SerializedName("remotePath") val remotePath: String? = null,
    @SerializedName("retryKey") val retryKey: String? = null,
    @SerializedName("replaceBookId") val replaceBookId: String? = null,
    @SerializedName("createdAt") val createdAt: Long = 0L,
    @SerializedName("finishedAt") val finishedAt: Long? = null,
)
