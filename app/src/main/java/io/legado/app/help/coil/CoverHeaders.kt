package io.legado.app.help.coil

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Merges explicit request headers over headers resolved from a book source.
 * Header names are compared case-insensitively so OkHttp never receives two
 * competing values for the same header (especially Authorization).
 */
internal fun mergeCoverHeaders(
    resolved: Map<String, String>?,
    explicit: Map<String, String>?,
): Map<String, String> {
    if (resolved.isNullOrEmpty()) return explicit.orEmpty()
    if (explicit.isNullOrEmpty()) return resolved
    val merged = linkedMapOf<String, String>()
    resolved.forEach { (name, value) -> merged[name] = value }
    explicit.forEach { (name, value) ->
        merged.keys
            .filter { it.equals(name, ignoreCase = true) }
            .toList()
            .forEach(merged::remove)
        merged[name] = value
    }
    return merged
}

/**
 * Returns a NAS bearer header only when [coverUrl] belongs to the configured
 * NAS origin. External scraped/CDN covers must never receive the NAS token.
 */
internal fun nasCoverHeaders(
    coverUrl: String?,
    nasApiUrl: String?,
    token: String?,
): Map<String, String> {
    val bearer = token?.trim()?.takeIf(String::isNotEmpty) ?: return emptyMap()
    // OkHttp rejects control characters in headers. Rejecting them here keeps
    // this helper side-effect free and avoids turning a bad setting into a
    // request-building crash.
    if (bearer.any { it.code < 0x20 || it.code == 0x7F }) return emptyMap()

    val cover = coverUrl?.trim()?.toHttpUrlOrNull() ?: return emptyMap()
    val rawBase = nasApiUrl?.trim()?.takeIf(String::isNotEmpty) ?: return emptyMap()
    val base = (if (rawBase.contains("://")) rawBase else "http://$rawBase")
        .toHttpUrlOrNull()
        ?: return emptyMap()
    if (cover.scheme != base.scheme || cover.host != base.host || cover.port != base.port) {
        return emptyMap()
    }
    // The indexer serves covers from its dedicated public path. Do not let a
    // same-origin URL under an unrelated path inherit the NAS credential.
    // This keeps bearer tokens away from admin, frontend, and user-content
    // endpoints even when a book record contains a malformed cover path.
    val coverPath = cover.encodedPath
    val allowed = coverPath == "/covers" || coverPath.startsWith("/covers/") ||
        coverPath == "/api/covers" || coverPath.startsWith("/api/covers/")
    if (!allowed) return emptyMap()
    return mapOf("Authorization" to "Bearer $bearer")
}
