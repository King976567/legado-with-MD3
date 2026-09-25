package io.legado.app.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.gateway.NasLibraryGateway
import io.legado.app.domain.gateway.NasDownloadSink
import io.legado.app.domain.gateway.NasUploadSource
import io.legado.app.domain.gateway.NasChunkSink
import io.legado.app.domain.model.NasBook
import io.legado.app.domain.model.NasBookDetail
import io.legado.app.domain.model.NasBookPage
import io.legado.app.domain.model.NasCapabilities
import io.legado.app.domain.model.NasCapabilityPath
import io.legado.app.domain.model.NasCheckKind
import io.legado.app.domain.model.NasCheckStatus
import io.legado.app.domain.model.NasConnection
import io.legado.app.domain.model.NasDiagnosticReport
import io.legado.app.domain.model.NasDiagnosticStep
import io.legado.app.domain.model.NasDirectory
import io.legado.app.domain.model.NasHealth
import io.legado.app.domain.model.NasHttpException
import io.legado.app.domain.model.NasMoveResult
import io.legado.app.domain.model.NasTask
import io.legado.app.domain.model.NasTaskConflictException
import io.legado.app.domain.model.NasTaskStatus
import io.legado.app.domain.model.NasUploadResult
import io.legado.app.domain.model.NasWriteAccess
import io.legado.app.domain.model.settings.NasSettings
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.get
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.postMultipart
import io.legado.app.help.http.postJson
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Repository for the dedicated legado-nas-indexer API.
 *
 * This client deliberately does not use the WebDAV settings or WebDAV file
 * model. A single bearer token is attached to every request and all network
 * calls have a bounded timeout so a sleeping/offline NAS cannot block the UI.
 */
class NasLibraryRepository(
    private val settingsGateway: NasSettingsGateway,
    private val httpClient: OkHttpClient = OkHttpClient.Builder().build(),
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) : NasLibraryGateway {

    // NAS credentials must never be forwarded to an unrelated redirect target.
    // Keep the injected client for testability, but make the production request
    // client independent of the app's cookie/user-agent interceptors and disable
    // both HTTP and HTTPS redirects.
    private val requestClient: OkHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun checkConnection(settings: NasSettings): NasConnection =
        withContext(Dispatchers.IO) {
            val base = requireBaseUrl(settings.apiUrl)
            val health = call(settings) { url(healthUrl(base)) }.use { response ->
                ensureSuccess(response, "NAS 健康检查失败")
                parseHealth(response.body.text())
            }
            if (health.status.isNotBlank() && !health.status.equals("ok", true)) {
                throw IllegalStateException("NAS 服务状态：${health.status}")
            }
            if (health.authRequired && settings.apiToken.isBlank()) {
                throw NasHttpException(401, "NAS 服务要求访问令牌")
            }
            val capabilities = fetchCapabilities(settings, base)
            // This request is intentionally part of the connection test: a
            // public health endpoint must not make an invalid token look valid.
            call(settings) {
                get(apiUrl(base, "books"), mapOf("page" to "1", "pageSize" to "1"))
            }.use { response -> ensureSuccess(response, "NAS 书库接口失败") }
            NasConnection(health, capabilities, probeWriteAccess(settings, base))
        }

    /** Runs the five user-visible diagnostics without leaking a token. */
    override suspend fun diagnoseConnection(
        apiUrl: String,
        token: String?,
    ): NasDiagnosticReport = withContext(Dispatchers.IO) {
        val steps = mutableListOf<NasDiagnosticStep>()
        val normalized = normalizeUrl(apiUrl)
        if (normalized == null) {
            steps += NasDiagnosticStep(NasCheckKind.URL, NasCheckStatus.FAIL, "无效的 http(s) 地址")
            steps += NasDiagnosticStep(NasCheckKind.HEALTH, NasCheckStatus.SKIP)
            steps += NasDiagnosticStep(NasCheckKind.AUTH, NasCheckStatus.SKIP)
            steps += NasDiagnosticStep(NasCheckKind.BOOKS, NasCheckStatus.SKIP)
            steps += NasDiagnosticStep(NasCheckKind.CAPABILITIES, NasCheckStatus.SKIP)
            return@withContext NasDiagnosticReport(false, steps)
        }
        steps += NasDiagnosticStep(NasCheckKind.URL, NasCheckStatus.PASS, normalized)
        val settings = NasSettings(apiUrl = normalized, apiToken = token.orEmpty())
        var health: NasHealth? = null
        var capabilities: NasCapabilities? = null
        runCatching {
            call(settings) { url(healthUrl(normalized)) }.use { response ->
                if (!response.isSuccessful) throw NasHttpException(response.code, "HTTP ${response.code}")
                parseHealth(response.body.text())
            }
        }.onSuccess {
            health = it
            if (it.status.isNotBlank() && !it.status.equals("ok", ignoreCase = true)) {
                steps += NasDiagnosticStep(NasCheckKind.HEALTH, NasCheckStatus.FAIL, "服务状态：${it.status}")
            } else {
                steps += NasDiagnosticStep(NasCheckKind.HEALTH, NasCheckStatus.PASS)
            }
        }.onFailure {
            steps += NasDiagnosticStep(NasCheckKind.HEALTH, NasCheckStatus.FAIL, it.safeMessage())
        }
        if (health != null && steps.none { it.kind == NasCheckKind.HEALTH && it.status == NasCheckStatus.FAIL }) {
            runCatching { fetchCapabilities(settings, normalized, allowMissing = false) }
                .onSuccess {
                    capabilities = it
                    steps += NasDiagnosticStep(NasCheckKind.CAPABILITIES, NasCheckStatus.PASS)
                }
                .onFailure {
                    if (it is NasCapabilityUnavailableException) {
                        steps += NasDiagnosticStep(
                            NasCheckKind.CAPABILITIES,
                            NasCheckStatus.SKIP,
                            "后端未提供能力接口",
                        )
                    } else {
                        steps += NasDiagnosticStep(NasCheckKind.CAPABILITIES, NasCheckStatus.FAIL, it.safeMessage())
                    }
                }
        } else {
            steps += NasDiagnosticStep(NasCheckKind.CAPABILITIES, NasCheckStatus.SKIP)
        }
        if (health?.authRequired == true && token.isNullOrBlank()) {
            steps += NasDiagnosticStep(NasCheckKind.AUTH, NasCheckStatus.FAIL, "服务要求令牌")
            steps += NasDiagnosticStep(NasCheckKind.BOOKS, NasCheckStatus.SKIP)
        } else {
            runCatching {
                call(settings) {
                    get(apiUrl(normalized, "books"), mapOf("page" to "1", "pageSize" to "1"))
                }.use { response ->
                    when (response.code) {
                        401, 403 -> throw NasHttpException(response.code, "令牌无效或无权限")
                        in 200..299 -> Unit
                        else -> throw NasHttpException(response.code, "HTTP ${response.code}")
                    }
                }
            }.onSuccess {
                steps += NasDiagnosticStep(NasCheckKind.AUTH, NasCheckStatus.PASS)
                steps += NasDiagnosticStep(NasCheckKind.BOOKS, NasCheckStatus.PASS)
            }.onFailure {
                val detail = it.safeMessage()
                steps += NasDiagnosticStep(
                    NasCheckKind.AUTH,
                    if (it is NasHttpException && it.statusCode in 401..403) NasCheckStatus.FAIL else NasCheckStatus.SKIP,
                    detail,
                )
                steps += NasDiagnosticStep(NasCheckKind.BOOKS, NasCheckStatus.FAIL, detail)
            }
        }
        val connection = if (steps.none { it.status == NasCheckStatus.FAIL } && health != null) {
            NasConnection(
                health,
                capabilities ?: NasCapabilities(),
                probeWriteAccess(settings, normalized),
            )
        } else null
        NasDiagnosticReport(
            success = steps.none { it.status == NasCheckStatus.FAIL },
            steps = steps,
            connection = connection,
            normalizedUrl = normalized,
        )
    }

    override suspend fun listBooks(
        page: Int,
        pageSize: Int,
        search: String?,
        directoryPath: String?,
        scrapeStatus: String?,
        manualConfirmed: Boolean?,
        sortBy: String,
        sortOrder: String,
        settings: NasSettings,
    ): NasBookPage = withContext(Dispatchers.IO) {
        val base = requireBaseUrl(settings.apiUrl)
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 200)
        val query = buildMap {
            put("page", safePage.toString())
            put("pageSize", safeSize.toString())
            put("sortBy", sortBy)
            put("sortOrder", sortOrder)
            search?.trim()?.takeIf(String::isNotEmpty)?.let { put("search", it) }
            directoryPath?.trim()?.takeIf(String::isNotEmpty)?.let { put("directoryPath", it) }
            scrapeStatus?.trim()?.takeIf(String::isNotEmpty)?.let { put("scrapeStatus", it) }
            manualConfirmed?.let { put("manualConfirmed", it.toString()) }
        }
        call(settings) { get(apiUrl(base, "books"), query) }.use { response ->
            ensureSuccess(response, "NAS 书库请求失败")
            parsePage(response.body.text(), safePage, safeSize, base)
        }
    }

    suspend fun search(query: String, page: Int = 1, pageSize: Int = 30): NasBookPage =
        listBooks(
            page = page,
            pageSize = pageSize,
            search = query,
            directoryPath = null,
            scrapeStatus = null,
            manualConfirmed = null,
            sortBy = "updated_at",
            sortOrder = "DESC",
            settings = settingsGateway.currentSettings,
        )

    suspend fun recent(limit: Int = 20): List<NasBook> =
        listBooks(
            page = 1,
            pageSize = limit,
            search = null,
            directoryPath = null,
            scrapeStatus = null,
            manualConfirmed = null,
            sortBy = "updated_at",
            sortOrder = "DESC",
            settings = settingsGateway.currentSettings,
        ).items

    override suspend fun detail(id: String, settings: NasSettings): NasBookDetail =
        withContext(Dispatchers.IO) {
            val base = requireBaseUrl(settings.apiUrl)
            call(settings) { url(apiUrl(base, "books/${encode(id)}")) }.use { response ->
                ensureSuccess(response, "NAS 书籍详情请求失败")
                parseDetail(response.body.text(), base)
            }
        }

    override suspend fun updateMetadata(
        id: String,
        title: String,
        author: String,
        intro: String,
        settings: NasSettings,
    ): NasBookDetail = withContext(Dispatchers.IO) {
        val base = requireBaseUrl(settings.apiUrl)
        call(settings) {
            url(apiUrl(base, "books/${encode(id)}/metadata"))
            method(
                "PUT",
                GSON.toJson(mapOf("name" to title, "author" to author, "intro" to intro))
                    .toRequestBody(JSON),
            )
        }.use { response ->
            ensureSuccess(response, "NAS 元数据保存失败")
            parseDetail(response.body.text(), base)
        }
    }

    override suspend fun moveBook(
        id: String,
        targetDirectoryPath: String,
        duplicatePolicy: String,
        settings: NasSettings,
    ): NasMoveResult = withContext(Dispatchers.IO) {
        val base = requireBaseUrl(settings.apiUrl)
        call(settings) {
            url(apiUrl(base, "books/${encode(id)}/move"))
            postJson(GSON.toJson(mapOf("targetDirectoryPath" to targetDirectoryPath, "duplicatePolicy" to duplicatePolicy)))
        }.use { response ->
            if (!response.isSuccessful && response.code != 409) {
                throw NasHttpException(response.code, "NAS 移动书籍失败：HTTP ${response.code}")
            }
            parseMove(response.body.text(), base)
        }
    }

    override suspend fun uploadBook(
        source: NasUploadSource,
        fileName: String,
        contentLength: Long?,
        duplicatePolicy: String,
        title: String,
        author: String,
        intro: String,
        directoryPath: String,
        settings: NasSettings,
    ): NasUploadResult = withContext(Dispatchers.IO) {
        val base = requireBaseUrl(settings.apiUrl)
        val uploadBody = object : okhttp3.RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = contentLength ?: -1L
            override fun writeTo(sink: okio.BufferedSink) {
                source.writeTo(NasChunkSink { chunk, offset, length ->
                    sink.write(chunk, offset, length)
                })
            }
        }
        val form = linkedMapOf<String, Any>(
            "duplicatePolicy" to duplicatePolicy,
            "name" to title,
            "author" to author,
            "intro" to intro,
            "directoryPath" to directoryPath,
            "file" to mapOf(
                "fileName" to fileName,
                "file" to uploadBody,
                "contentType" to "application/octet-stream",
            ),
        )
        call(settings) {
            url(apiUrl(base, "books/upload"))
            postMultipart("multipart/form-data", form)
        }.use { response ->
            if (!response.isSuccessful && response.code != 409) {
                throw NasHttpException(response.code, "NAS 上传失败：HTTP ${response.code}")
            }
            parseUpload(response.body.text(), base)
        }
    }

    override suspend fun downloadBook(
        id: String,
        sink: NasDownloadSink,
        settings: NasSettings,
    ): Unit = withContext(Dispatchers.IO) {
        val base = requireBaseUrl(settings.apiUrl)
        call(settings) { url(apiUrl(base, "books/${encode(id)}/download")) }.use { response ->
            ensureSuccess(response, "NAS 下载失败")
            response.body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_TRANSFER_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) sink.write(buffer, 0, count)
                }
            }
        }
    }

    override suspend fun refreshIndex(settings: NasSettings): NasTaskStatus =
        postTask("indexer/index", settings, "NAS 索引刷新失败")

    override suspend fun indexerStatus(settings: NasSettings): NasTaskStatus =
        getTask("indexer/status", settings, "NAS 索引状态获取失败")

    override suspend fun indexerTasks(limit: Int, settings: NasSettings): List<NasTask> =
        getTaskList("indexer/tasks", limit, settings, "NAS 索引任务获取失败")

    override suspend fun cancelIndexer(settings: NasSettings): NasTaskStatus =
        postTask("indexer/cancel", settings, "NAS 索引取消失败")

    override suspend fun scrapeBook(id: String, settings: NasSettings): NasTaskStatus =
        postTask("scraper/scrape/${encode(id)}", settings, "NAS 刮削提交失败")

    override suspend fun scrapeAllPending(directoryPath: String?, settings: NasSettings): NasTaskStatus =
        postTask("scraper/scrape/all-pending", settings, "NAS 批量刮削提交失败", directoryPath)

    override suspend fun scrapeAllFailed(directoryPath: String?, settings: NasSettings): NasTaskStatus =
        postTask("scraper/scrape/all-failed", settings, "NAS 失败刮削重试失败", directoryPath)

    override suspend fun cancelScraperTask(taskId: String, settings: NasSettings): NasTaskStatus =
        postTask("scraper/tasks/${encode(taskId)}/cancel", settings, "NAS 刮削任务取消失败")

    override suspend fun tasks(limit: Int, settings: NasSettings): List<NasTask> =
        getTaskList("tasks", limit, settings, "NAS 任务中心获取失败")

    override suspend fun libraryDirectories(settings: NasSettings): List<NasDirectory> =
        getJsonList("library-directories", settings, "NAS 分类获取失败") { parseDirectory(it) }

    suspend fun createLibraryDirectory(
        name: String,
        directoryPath: String,
        scrapeMode: String = "",
        enabled: Boolean = true,
        settings: NasSettings = settingsGateway.currentSettings,
    ): NasDirectory = mutateDirectory("library-directories", mapOf("name" to name, "directoryPath" to directoryPath, "scrapeMode" to scrapeMode, "enabled" to enabled), settings)

    suspend fun updateLibraryDirectory(
        id: Long,
        name: String,
        directoryPath: String,
        scrapeMode: String = "",
        enabled: Boolean = true,
        settings: NasSettings = settingsGateway.currentSettings,
    ): NasDirectory = mutateDirectory("library-directories/${encode(id.toString())}", mapOf("name" to name, "directoryPath" to directoryPath, "scrapeMode" to scrapeMode, "enabled" to enabled), settings, "PUT")

    suspend fun deleteLibraryDirectory(id: Long, settings: NasSettings = settingsGateway.currentSettings) {
        withContext(Dispatchers.IO) {
            val base = requireBaseUrl(settings.apiUrl)
            call(settings) {
                url(apiUrl(base, "library-directories/${encode(id.toString())}"))
                delete()
            }.use { response -> ensureSuccess(response, "NAS 分类删除失败") }
        }
    }

    suspend fun supportsLibraryDirectories(settings: NasSettings = settingsGateway.currentSettings): Boolean =
        runCatching { checkConnection(settings).capabilities.supportsLibraryDirectories }.getOrDefault(false)

    fun buildDownloadUrl(bookId: String, settings: NasSettings = settingsGateway.currentSettings): String? {
        val base = normalizeUrl(settings.apiUrl) ?: return null
        return apiUrl(base, "books/${encode(bookId)}/download")
    }

    /** Normalises host-only input and strips trailing slashes without storing credentials. */
    override fun normalizeUrl(raw: String): String? {
        val candidate = raw.trim().takeIf(String::isNotEmpty) ?: return null
        val withScheme = if (candidate.contains("://")) candidate else "http://$candidate"
        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "http" && parsed.scheme != "https") return null
        // Userinfo is not part of the NAS service address.  Reject it instead of
        // persisting or exporting an embedded username/password in the URL.
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (!parsed.query.isNullOrEmpty() || !parsed.fragment.isNullOrEmpty()) return null
        return parsed.toString().trimEnd('/')
    }

    private suspend fun fetchCapabilities(
        settings: NasSettings,
        base: String,
        allowMissing: Boolean = true,
    ): NasCapabilities =
        try {
            call(settings) { url(apiUrl(base, "capabilities")) }.use { response ->
                if (response.code == 404 || response.code == 405) {
                    if (allowMissing) return@use NasCapabilities()
                    throw NasCapabilityUnavailableException()
                }
                ensureSuccess(response, "NAS 能力接口失败")
                parseCapabilities(response.body.text())
            }
        } catch (error: NasHttpException) {
            if (allowMissing && (error.statusCode == 404 || error.statusCode == 405)) {
                NasCapabilities()
            } else {
                throw error
            }
        }

    private suspend fun postTask(
        path: String,
        settings: NasSettings,
        message: String,
        directoryPath: String? = null,
    ): NasTaskStatus = withContext(Dispatchers.IO) {
        val base = requireBaseUrl(settings.apiUrl)
        val endpoint = if (directoryPath.isNullOrBlank()) apiUrl(base, path)
        else apiUrl(base, path) + "?directoryPath=${encode(directoryPath)}"
        call(settings) { url(endpoint); post("".toRequestBody()) }.use { response ->
            val body = response.body.text()
            if (response.code == 409) {
                throw taskConflictException(body, message)
            }
            if (!response.isSuccessful) {
                throw NasHttpException(response.code, "$message：HTTP ${response.code}")
            }
            parseTaskStatus(body)
        }
    }

    /**
     * The current Go API has no read-only capability field. Its confirm
     * endpoint authenticates before rejecting an empty book list, which gives
     * us a non-mutating way to distinguish a read token from a write token.
     * Older services may not expose the endpoint; keep that result unknown and
     * preserve compatibility with their existing write behavior.
     */
    private suspend fun probeWriteAccess(settings: NasSettings, base: String): NasWriteAccess = try {
        call(settings) {
            url(apiUrl(base, "books/confirm"))
            post(
                GSON.toJson(mapOf("bookIds" to emptyList<String>()))
                    .toRequestBody(JSON),
            )
        }.use { response ->
            when {
                response.code == 401 || response.code == 403 -> NasWriteAccess.DENIED
                response.code == 404 || response.code == 405 -> NasWriteAccess.UNKNOWN
                response.code == 400 || response.isSuccessful -> NasWriteAccess.ALLOWED
                else -> NasWriteAccess.UNKNOWN
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        NasWriteAccess.UNKNOWN
    }

    private suspend fun getTask(path: String, settings: NasSettings, message: String): NasTaskStatus =
        withContext(Dispatchers.IO) {
            val base = requireBaseUrl(settings.apiUrl)
            call(settings) { url(apiUrl(base, path)) }.use { response ->
                ensureSuccess(response, message)
                parseTaskStatus(response.body.text())
            }
        }

    private suspend fun getTaskList(path: String, limit: Int, settings: NasSettings, message: String): List<NasTask> =
        getJsonList("$path?limit=${limit.coerceIn(1, 100)}", settings, message) { parseTask(it) }

    private suspend fun <T> getJsonList(path: String, settings: NasSettings, message: String, parser: (JsonObject) -> T): List<T> =
        withContext(Dispatchers.IO) {
            val base = requireBaseUrl(settings.apiUrl)
            call(settings) { url(apiUrl(base, path)) }.use { response ->
                ensureSuccess(response, message)
                val root = GSON.fromJson(response.body.text(), JsonElement::class.java)
                val array = when {
                    root.isJsonArray -> root.asJsonArray
                    root.isJsonObject -> root.asJsonObject.array("items", "data", "tasks", "directories") ?: JsonArray()
                    else -> JsonArray()
                }
                array.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let(parser) }
            }
        }

    private suspend fun mutateDirectory(
        path: String,
        body: Map<String, Any>,
        settings: NasSettings,
        method: String = "POST",
    ): NasDirectory = withContext(Dispatchers.IO) {
        val base = requireBaseUrl(settings.apiUrl)
        call(settings) {
            url(apiUrl(base, path))
            if (method == "POST") postJson(GSON.toJson(body))
            else method(method, GSON.toJson(body).toRequestBody(JSON))
        }.use { response ->
            ensureSuccess(response, "NAS 分类保存失败")
            parseDirectory(GSON.fromJson(response.body.text(), JsonObject::class.java))
        }
    }

    private suspend fun call(
        settings: NasSettings,
        configure: okhttp3.Request.Builder.() -> Unit,
    ): okhttp3.Response = withTimeout(requestTimeoutMs) {
        requestClient.newCallResponse {
            configure()
            addHeaders(nasAuthorizationHeaders(settings.apiToken))
        }
    }

    private fun requireBaseUrl(raw: String): String =
        normalizeUrl(raw) ?: throw IllegalArgumentException("NAS 地址无效")

    private fun ensureSuccess(response: okhttp3.Response, message: String) {
        if (!response.isSuccessful) {
            throw NasHttpException(response.code, "$message：HTTP ${response.code}")
        }
    }

    internal fun apiUrl(base: String, path: String): String {
        val clean = path.trimStart('/')
        return when {
            // The Go service currently exposes its routes below /api. Accept
            // the older /api/v1 form as an input alias, but never send the
            // unsupported /api/v1/* path to the server.
            base.endsWith("/api/v1") -> "${base.removeSuffix("/api/v1")}/api/$clean"
            base.endsWith("/api") -> "$base/$clean"
            else -> "$base/api/$clean"
        }
    }

    private fun healthUrl(base: String): String {
        val root = when {
            base.endsWith("/api/v1") -> base.removeSuffix("/api/v1")
            base.endsWith("/api") -> base.removeSuffix("/api")
            else -> base
        }
        return "$root/health"
    }

    /** URL-encode path/query values without depending on Android framework APIs. */
    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun parseHealth(text: String): NasHealth {
        val root = GSON.fromJson(text, JsonObject::class.java)
        return NasHealth(
            status = root.string("status").orEmpty(),
            service = root.string("service"),
            version = root.string("version"),
            authRequired = root.bool("authRequired", "auth_required"),
            features = root.stringArray("features"),
        )
    }

    private fun parseCapabilities(text: String): NasCapabilities {
        val root = GSON.fromJson(text, JsonObject::class.java)
        val features = when {
            root.get("features")?.isJsonArray == true -> root.stringArray("features").toSet()
            root.get("features")?.isJsonObject == true -> root.getAsJsonObject("features")
                .entrySet().filter { it.value.asBoolean }.map { it.key }.toSet()
            else -> emptySet()
        }
        val paths = root.obj("paths")?.entrySet()?.associate { (key, value) ->
            key to parseCapabilityPath(value.asJsonObject)
        }.orEmpty()
        return NasCapabilities(
            features = features,
            service = root.string("service").orEmpty(),
            version = root.string("version").orEmpty(),
            defaultUploadDirectory = root.string("defaultUploadDirectory", "default_upload_directory").orEmpty(),
            paths = paths,
        )
    }

    private fun parseCapabilityPath(root: JsonObject) = NasCapabilityPath(
        path = root.string("path").orEmpty(),
        configured = root.bool("configured"),
        exists = root.bool("exists"),
        isDir = root.bool("isDir", "is_dir"),
        readable = root.bool("readable"),
        writable = root.bool("writable"),
        error = root.string("error", "writeError", "write_error"),
    )

    private fun parsePage(text: String, page: Int, pageSize: Int, base: String): NasBookPage {
        val root = GSON.fromJson(text, JsonObject::class.java)
        val array = root.array("items", "books") ?: JsonArray()
        val items = array.mapNotNull {
            it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let { item -> parseBook(item, base) }
        }
            .distinctBy { it.id.ifBlank { it.relativePath } }
        val total = root.int("total", "count") ?: items.size
        val actualPage = (root.int("page") ?: page).coerceAtLeast(1)
        val actualSize = (root.int("pageSize", "page_size", "limit") ?: pageSize)
            .coerceIn(1, 200)
        return NasBookPage(items, actualPage, actualSize, total.coerceAtLeast(0))
    }

    private fun parseDetail(text: String, base: String): NasBookDetail {
        val root = GSON.fromJson(text, JsonObject::class.java)
        val book = root.obj("book") ?: root
        return parseBook(book, base).let {
            NasBookDetail(
                id = it.id,
                relativePath = it.relativePath,
                fileName = it.fileName,
                title = it.title,
                author = it.author,
                intro = it.intro,
                size = it.size,
                lastModified = it.lastModified,
                indexedAt = it.indexedAt,
                scrapeStatus = it.scrapeStatus,
                manualConfirmed = it.manualConfirmed,
                suggestedTitle = it.suggestedTitle,
                suggestedAuthor = it.suggestedAuthor,
                suggestedRelativePath = it.suggestedRelativePath,
                confidence = it.confidence,
                needsReview = it.needsReview,
                coverUrl = it.coverUrl,
            )
        }
    }

    private fun parseBook(item: JsonObject, base: String? = null): NasBook {
        val parsedName = item.string("parsedName", "parsed_name")
        val title = item.string("manualName", "manual_name")?.takeIf(String::isNotBlank)
            ?: item.string("scrapedName", "scraped_name")?.takeIf(String::isNotBlank)
            ?: parsedName
            ?: item.string("title", "name")
            ?: item.string("fileName", "file_name")?.substringBeforeLast('.')
            .orEmpty()
        val relativePath = item.string("relativePath", "relative_path", "filePath", "file_path", "path").orEmpty()
        return NasBook(
            id = item.string("id") ?: relativePath,
            relativePath = relativePath,
            fileName = item.string("fileName", "file_name", "name").orEmpty(),
            title = title,
            author = item.string("manualAuthor", "manual_author", "scrapedAuthor", "scraped_author", "parsedAuthor", "parsed_author", "author"),
            intro = item.string("manualIntro", "manual_intro", "scrapedIntro", "scraped_intro", "intro"),
            size = item.long("fileSize", "file_size", "size"),
            lastModified = item.long("lastModified", "last_modified", "updatedAt", "updated_at"),
            indexedAt = item.long("indexedAt", "indexed_at"),
            scrapeStatus = item.string("scrapeStatus", "scrape_status").orEmpty(),
            manualConfirmed = item.bool("manualConfirmed", "manual_confirmed"),
            suggestedTitle = item.string("suggestedTitle", "suggested_title"),
            suggestedAuthor = item.string("suggestedAuthor", "suggested_author"),
            suggestedRelativePath = item.string("suggestedRelativePath", "suggested_relative_path"),
            confidence = item.double("confidence"),
            needsReview = item.bool("needsReview", "needs_review"),
            coverUrl = resolveCoverUrl(base, item.string("coverUrl", "cover_url", "cover")),
        )
    }

    /**
     * The indexer serializes local covers as paths such as `/covers/book.jpg`.
     * Compose/Coil cannot resolve those paths without the NAS origin, so turn
     * them into an absolute URL while preserving externally hosted covers.
     */
    internal fun resolveCoverUrl(base: String?, raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val lower = value.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return value
        val origin = base
            ?.let(::serviceRoot)
            ?.toHttpUrlOrNull()
            ?: return value
        val path = if (value.startsWith('/')) value else "/$value"
        return origin.resolve(path)?.toString() ?: value
    }

    private fun serviceRoot(base: String): String = when {
        base.endsWith("/api/v1") -> base.removeSuffix("/api/v1")
        base.endsWith("/api") -> base.removeSuffix("/api")
        else -> base
    }

    private fun parseDirectory(root: JsonObject) = NasDirectory(
        id = root.long("id"),
        name = root.string("name").orEmpty(),
        directoryPath = root.string("directoryPath", "directory_path", "path").orEmpty(),
        scrapeMode = root.string("scrapeMode", "scrape_mode").orEmpty(),
        enabled = root.bool("enabled", default = true),
        bookCount = root.long("bookCount", "book_count"),
    )

    private fun parseTask(root: JsonObject): NasTask = NasTask(
        id = root.string("id", "taskId", "task_id").orEmpty(),
        kind = root.string("kind").orEmpty(),
        type = root.string("type"),
        status = root.string("status").orEmpty(),
        total = root.int("total", "totalFiles", "total_files") ?: 0,
        processed = root.int("processed", "processedFiles", "processed_files") ?: 0,
        success = root.int("success", "succeeded") ?: 0,
        failed = root.int("failed", "errorCount", "error_count") ?: 0,
        current = root.string("current", "currentFile", "current_file", "currentBook"),
        lastError = root.string("lastError", "last_error", "error"),
        cancelRequested = root.bool("cancelRequested", "cancel_requested"),
        startedAt = root.string("startedAt", "started_at"),
        finishedAt = root.string("finishedAt", "finished_at"),
    )

    private fun parseTaskStatus(text: String): NasTaskStatus {
        val root = GSON.fromJson(text, JsonObject::class.java)
        val task = root.obj("task", "status") ?: root
        val parsed = parseTask(task)
        return NasTaskStatus(
            taskId = parsed.id.takeIf(String::isNotBlank),
            status = parsed.status,
            total = parsed.total,
            processed = parsed.processed,
            success = parsed.success,
            failed = parsed.failed,
            current = parsed.current,
            lastError = parsed.lastError,
        )
    }

    /** Parses the Go API's 409 envelope without treating it as a success. */
    private fun parseTaskConflict(text: String): ParsedTaskConflict = runCatching {
        val root = GSON.fromJson(text, JsonObject::class.java)
        val task = root.obj("task", "status")?.let { parseTaskStatus(GSON.toJson(it)) }
        ParsedTaskConflict(
            message = root.string("error", "message"),
            task = task,
        )
    }.getOrDefault(ParsedTaskConflict())

    internal fun taskConflictException(text: String, operationMessage: String): NasTaskConflictException {
        val conflict = parseTaskConflict(text)
        return NasTaskConflictException(
            message = conflict.message?.let { "$operationMessage：$it" }
                ?: "$operationMessage：已有相同任务正在运行",
            currentTask = conflict.task,
        )
    }

    private fun parseMove(text: String, base: String): NasMoveResult {
        val root = GSON.fromJson(text, JsonObject::class.java)
        val item = root.obj("item") ?: root
        return NasMoveResult(root.string("status").orEmpty(), parseBook(item, base))
    }

    private fun parseUpload(text: String, base: String): NasUploadResult {
        val root = GSON.fromJson(text, JsonObject::class.java)
        fun books(key: String) = root.array(key)?.mapNotNull {
            it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let { item -> parseBook(item, base) }
        }.orEmpty()
        return NasUploadResult(
            uploaded = books("uploaded"),
            duplicates = books("duplicates"),
            skipped = books("skipped"),
            overwritten = books("overwritten"),
            failed = root.array("failed")?.mapNotNull { element ->
                if (element.isJsonObject) element.asJsonObject.string("error", "message") else element.asString
            }.orEmpty(),
        )
    }

    private fun JsonObject.array(vararg names: String): JsonArray? = names.firstNotNullOfOrNull { name ->
        get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
    }

    private fun JsonObject.obj(vararg names: String): JsonObject? = names.firstNotNullOfOrNull { name ->
        get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
    }

    private fun JsonObject.string(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        get(name)?.takeUnless(JsonElement::isJsonNull)?.let { element ->
            runCatching { element.asString }.getOrNull()
        }
    }

    private fun JsonObject.stringArray(name: String): List<String> =
        get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.mapNotNull {
            runCatching { it.asString }.getOrNull()
        }.orEmpty()

    private fun JsonObject.bool(vararg names: String, default: Boolean = false): Boolean =
        names.firstNotNullOfOrNull { name -> get(name)?.takeUnless(JsonElement::isJsonNull)?.let { runCatching { it.asBoolean }.getOrNull() } }
            ?: default

    private fun JsonObject.long(vararg names: String): Long =
        names.firstNotNullOfOrNull { name -> get(name)?.takeUnless(JsonElement::isJsonNull)?.let { runCatching { it.asLong }.getOrNull() } }
            ?: 0L

    private fun JsonObject.int(vararg names: String): Int? =
        names.firstNotNullOfOrNull { name -> get(name)?.takeUnless(JsonElement::isJsonNull)?.let { runCatching { it.asInt }.getOrNull() } }

    private fun JsonObject.double(vararg names: String): Double =
        names.firstNotNullOfOrNull { name -> get(name)?.takeUnless(JsonElement::isJsonNull)?.let { runCatching { it.asDouble }.getOrNull() } }
            ?: 0.0

    private fun Throwable.safeMessage(): String = localizedMessage?.takeIf(String::isNotBlank) ?: "网络请求失败"

    private class NasCapabilityUnavailableException : Exception()

    private data class ParsedTaskConflict(
        val message: String? = null,
        val task: NasTaskStatus? = null,
    )

    private companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 15_000L
        const val DEFAULT_TRANSFER_BUFFER_SIZE = 64 * 1024
        val JSON = "application/json; charset=UTF-8".toMediaType()
    }
}

internal fun nasAuthorizationHeaders(token: String): Map<String, String> {
    val bearer = token.trim().takeIf(String::isNotEmpty) ?: return emptyMap()
    require(bearer.none { it.code < 0x20 || it.code == 0x7F }) {
        "NAS 访问令牌包含无效字符"
    }
    return mapOf("Authorization" to "Bearer $bearer")
}
