package io.legado.app.ui.main.nas

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.toUri
import io.legado.app.domain.gateway.NasSettingsGateway
import io.legado.app.domain.model.NasBook
import io.legado.app.domain.model.NasCheckKind
import io.legado.app.domain.model.NasCheckStatus
import io.legado.app.domain.model.NasDiagnosticReport
import io.legado.app.domain.model.NasDiagnosticStep
import io.legado.app.help.coil.CoverExtras
import io.legado.app.help.coil.nasCoverHeaders
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.collectLatest
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** Navigation-facing entry point. MainNavGraph can provide onBack/open hooks. */
@Composable
fun NasLibraryRouteScreen(
    viewModel: NasLibraryViewModel = koinViewModel(),
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nasSettingsGateway: NasSettingsGateway = koinInject()
    val nasSettings by nasSettingsGateway.settings.collectAsStateWithLifecycle(
        nasSettingsGateway.currentSettings
    )
    val context = LocalContext.current
    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.onIntent(NasLibraryIntent.UploadFileSelected(it)) }
    }

    LaunchedEffect(viewModel) {
        // The page owns its first refresh. HomeDashboard intentionally avoids
        // network work when the NAS card is hidden, while opening this route is
        // an explicit user action.
        viewModel.onIntent(NasLibraryIntent.Refresh)
        viewModel.onIntent(NasLibraryIntent.LoadTasks)
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                NasLibraryEffect.SelectUploadFile -> uploadLauncher.launch(arrayOf("*/*"))
                is NasLibraryEffect.OpenLocalBook -> context.startActivityForBook(effect.book)
                is NasLibraryEffect.ShowMessage -> context.toastOnUi(effect.message)
            }
        }
    }

    NasLibraryScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        nasApiUrl = nasSettings.apiUrl,
        nasToken = nasSettings.apiToken,
    )
}

/** Stateless screen kept public for Compose/UI tests and previews. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NasLibraryScreen(
    state: NasLibraryUiState,
    onIntent: (NasLibraryIntent) -> Unit,
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    nasApiUrl: String = "",
    nasToken: String = "",
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的 NAS") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onIntent(NasLibraryIntent.Refresh) },
                        enabled = !state.isLoading,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(
                        onClick = { onIntent(NasLibraryIntent.Diagnose) },
                        enabled = !state.isDiagnosing,
                    ) {
                        Icon(Icons.Default.Info, contentDescription = "能力诊断")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "NAS 设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SearchBar(
                query = state.query,
                onQueryChanged = { onIntent(NasLibraryIntent.QueryChanged(it)) },
                onSubmit = { onIntent(NasLibraryIntent.SubmitSearch) },
                enabled = state.isConfigured && !state.isLoading,
            )
            DirectoryFilter(state = state, onIntent = onIntent)
            ConnectionBanner(state = state, onRetry = { onIntent(NasLibraryIntent.Retry) })

            when {
                !state.isConfigured -> UnconfiguredContent()
                state.isLoading && state.books.isEmpty() -> LoadingContent()
                state.error != null && state.books.isEmpty() -> ErrorContent(
                    message = state.error,
                    onRetry = { onIntent(NasLibraryIntent.Retry) },
                )
                state.books.isEmpty() -> EmptyContent(query = state.query)
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.books, key = { it.id }) { book ->
                            NasBookRow(
                                book = book,
                                nasApiUrl = nasApiUrl,
                                nasToken = nasToken,
                                onClick = {
                                    onIntent(NasLibraryIntent.OpenBook(book))
                                },
                            )
                        }
                        if (state.isLoading) {
                            item(key = "loading") {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }

            LibraryActions(state = state, onIntent = onIntent)
            if (state.actionError != null) {
                Text(
                    text = state.actionError,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            TasksPanel(state = state, onIntent = onIntent)
            PaginationBar(state = state, onIntent = onIntent)
        }
    }

    state.selectedBook?.let { book ->
        BookDetailDialog(
            book = book,
            nasApiUrl = nasApiUrl,
            nasToken = nasToken,
            isLoading = state.isLoadingDetail,
            error = state.detailError,
            onDismiss = { onIntent(NasLibraryIntent.DismissBook) },
            canDownload = state.isConfigured &&
                state.capabilities.supports("download") &&
                !state.isActionRunning,
            onDownload = { onIntent(NasLibraryIntent.DownloadBook(book)) },
            canEdit = state.capabilities.supports("books") &&
                !state.writeAccessDenied && !state.isActionRunning,
            onEdit = { onIntent(NasLibraryIntent.RequestEdit(book)) },
            canMove = state.capabilities.supports("libraryDirectories") &&
                state.directories.isNotEmpty() && !state.writeAccessDenied &&
                !state.isActionRunning,
            onMove = { onIntent(NasLibraryIntent.RequestMove(book)) },
            canScrape = state.capabilities.supports("scraper") &&
                !state.writeAccessDenied && !state.isActionRunning,
            onScrape = { onIntent(NasLibraryIntent.ScrapeBook(book)) },
        )
    }

    state.editingBook?.let { book ->
        MetadataDialog(
            book = book,
            enabled = !state.isActionRunning && !state.writeAccessDenied,
            onDismiss = { onIntent(NasLibraryIntent.DismissEditor) },
            onSave = { title, author, intro ->
                onIntent(NasLibraryIntent.SaveMetadata(book, title, author, intro))
            },
        )
    }
    state.movingBook?.let { book ->
        MoveBookDialog(
            book = book,
            directories = state.directories,
            enabled = !state.isActionRunning && !state.writeAccessDenied,
            onDismiss = { onIntent(NasLibraryIntent.DismissMove) },
            onMove = { directory, policy ->
                onIntent(NasLibraryIntent.MoveBook(book, directory, policy))
            },
        )
    }
    if (state.isDiagnosing || state.diagnostic != null || state.diagnosticError != null) {
        NasDiagnosticDialog(
            isRunning = state.isDiagnosing,
            report = state.diagnostic,
            error = state.diagnosticError,
            onDismiss = { onIntent(NasLibraryIntent.DismissDiagnostic) },
            onRetry = { onIntent(NasLibraryIntent.Diagnose) },
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        enabled = enabled,
        singleLine = true,
        label = { Text("搜索 NAS 书库") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onSubmit, enabled = enabled) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    )
}

@Composable
private fun DirectoryFilter(
    state: NasLibraryUiState,
    onIntent: (NasLibraryIntent) -> Unit,
) {
    if (!state.capabilities.supportsLibraryDirectories || state.directories.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val selected = state.directories.firstOrNull { it.directoryPath == state.directoryPath }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(selected?.displayName ?: "全部分类")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("全部分类") },
                onClick = {
                    expanded = false
                    onIntent(NasLibraryIntent.DirectoryChanged(null))
                },
            )
            state.directories.forEach { directory ->
                DropdownMenuItem(
                    text = { Text("${directory.displayName} (${directory.bookCount})") },
                    onClick = {
                        expanded = false
                        onIntent(NasLibraryIntent.DirectoryChanged(directory.directoryPath))
                    },
                )
            }
        }
    }
}

@Composable
private fun ConnectionBanner(state: NasLibraryUiState, onRetry: () -> Unit) {
    when {
        state.isLoading && state.isRefreshing -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.error != null && state.books.isNotEmpty() -> {
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(state.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
        }
        state.connection != null -> {
            val status = state.connection.health.status.ifBlank { "已连接" }
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("NAS $status · ${state.total} 本", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    if (state.capabilities.features.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "能力 ${state.capabilities.features.size}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NasBookRow(
    book: NasBook,
    nasApiUrl: String,
    nasToken: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = rememberNasCoverRequest(book.coverUrl, nasApiUrl, nasToken),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp, 72.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title.ifBlank { book.fileName },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                val author = book.author?.takeIf { it.isNotBlank() }
                if (author != null) {
                    Text(author, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = book.relativePath.ifBlank { book.fileName },
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            Icon(Icons.Default.Info, contentDescription = "查看详情")
        }
    }
}

@Composable
private fun rememberNasCoverRequest(
    coverUrl: String?,
    nasApiUrl: String,
    nasToken: String,
): ImageRequest? {
    val context = LocalContext.current
    val normalizedUrl = coverUrl?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val uri = runCatching { normalizedUrl.toUri() }.getOrNull() ?: return null
    return remember(context, normalizedUrl, nasApiUrl, nasToken) {
        ImageRequest.Builder(context)
            // A Coil Uri bypasses the generic book-source URL mapper. The
            // explicit flag also protects this request if a future mapper
            // normalizes the data back to a String.
            .data(uri)
            .apply {
                extras[CoverExtras.SkipAnalysis] = true
                nasCoverHeaders(normalizedUrl, nasApiUrl, nasToken)
                    .takeIf { it.isNotEmpty() }
                    ?.let { headers -> extras[CoverExtras.Headers] = headers }
            }
            .build()
    }
}

@Composable
private fun LibraryActions(state: NasLibraryUiState, onIntent: (NasLibraryIntent) -> Unit) {
    val canWrite = state.isConfigured && !state.isActionRunning && !state.writeAccessDenied
    val canUpload = canWrite && state.capabilities.supports("upload")
    val canIndex = canWrite && state.capabilities.supportsIndexer
    val canScrape = canWrite && state.capabilities.supports("scraper")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onIntent(NasLibraryIntent.RequestUpload) },
                enabled = canUpload,
                modifier = Modifier.weight(1f),
            ) {
                Text("上传")
            }
            OutlinedButton(
                onClick = { onIntent(NasLibraryIntent.RefreshIndex) },
                enabled = canIndex,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.isActionRunning) "处理中…" else "刷新索引")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onIntent(NasLibraryIntent.ScrapePending) },
                enabled = canScrape,
                modifier = Modifier.weight(1f),
            ) {
                Text("刮削待处理")
            }
            OutlinedButton(
                onClick = { onIntent(NasLibraryIntent.RetryFailedScrape) },
                enabled = canScrape,
                modifier = Modifier.weight(1f),
            ) {
                Text("重试失败")
            }
        }
    }
}

@Composable
private fun TasksPanel(
    state: NasLibraryUiState,
    onIntent: (NasLibraryIntent) -> Unit,
) {
    if (!state.isConfigured) return
    Surface(
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "任务中心",
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onIntent(NasLibraryIntent.LoadTasks) }) {
                    Text("刷新任务")
                }
            }
            if (state.tasks.isEmpty()) {
                Text(
                    "暂无任务",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            } else {
                state.tasks.take(5).forEach { task ->
                    val progress = if (task.total > 0) {
                        "${task.processed}/${task.total}"
                    } else {
                        task.status.ifBlank { "未知状态" }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${task.kind.ifBlank { task.type ?: "NAS" }} · $progress" +
                                (task.lastError?.let { " · $it" } ?: ""),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                        )
                        if (task.kind.equals("scraper", ignoreCase = true) ||
                            task.kind.equals("indexer", ignoreCase = true)
                        ) {
                            if (task.id.isNotBlank() && task.status.lowercase() in RUNNING_TASK_STATUSES
                            ) {
                                TextButton(
                                    onClick = {
                                        onIntent(NasLibraryIntent.CancelTask(task.id, task.kind))
                                    },
                                    enabled = !state.isActionRunning && !task.cancelRequested,
                                ) {
                                    Text(if (task.cancelRequested) "取消中" else "取消")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaginationBar(state: NasLibraryUiState, onIntent: (NasLibraryIntent) -> Unit) {
    if (state.pageCount <= 1 && state.total == 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onIntent(NasLibraryIntent.PreviousPage) },
            enabled = state.canGoPrevious,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一页")
        }
        Text("第 ${state.page} / ${state.pageCount.coerceAtLeast(1)} 页")
        IconButton(
            onClick = { onIntent(NasLibraryIntent.NextPage) },
            enabled = state.canGoNext,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一页")
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun UnconfiguredContent() {
    EmptyMessage(title = "尚未配置 NAS", detail = "请先在设置中的 NAS 设置填写服务地址并测试连接")
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(40.dp))
        Text(message)
        Button(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun EmptyContent(query: String) {
    EmptyMessage(
        title = if (query.isBlank()) "NAS 书库为空" else "没有匹配的书籍",
        detail = if (query.isBlank()) "刷新索引或上传书籍后再试" else "请更换搜索关键词",
    )
}

@Composable
private fun EmptyMessage(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(detail, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    }
}

/** Shows the non-mutating connection checks without exposing the bearer token. */
@Composable
private fun NasDiagnosticDialog(
    isRunning: Boolean,
    report: NasDiagnosticReport?,
    error: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = { Text("NAS 能力诊断") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isRunning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("正在检查地址、服务和书库接口…")
                    }
                }
                error?.let {
                    Text(
                        text = it,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    )
                }
                report?.let { diagnostic ->
                    diagnostic.normalizedUrl?.let { url ->
                        Text("地址：$url", maxLines = 2)
                    }
                    diagnostic.steps.forEach { step ->
                        DiagnosticStepRow(step)
                    }
                    diagnostic.connection?.let { connection ->
                        val capabilities = connection.capabilities
                        Text(
                            text = buildString {
                                append("服务：")
                                append(capabilities.service.ifBlank { connection.health.service ?: "未知" })
                                capabilities.version.takeIf(String::isNotBlank)?.let {
                                    append(" · 版本 ")
                                    append(it)
                                }
                            },
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                        val features = capabilities.features.toList().sorted()
                        if (features.isEmpty()) {
                            Text(
                                "未返回可用能力列表",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text(
                                text = "能力：${features.take(12).joinToString("、")}" +
                                    if (features.size > 12) " 等 ${features.size} 项" else "",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                            )
                        }
                    }
                    Text(
                        text = if (diagnostic.success) "诊断通过" else "诊断未通过",
                        color = if (diagnostic.success) {
                            androidx.compose.material3.MaterialTheme.colorScheme.primary
                        } else {
                            androidx.compose.material3.MaterialTheme.colorScheme.error
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        confirmButton = {
            if (!isRunning) {
                TextButton(onClick = onRetry) { Text("重新诊断") }
            }
        },
        dismissButton = {
            if (!isRunning) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun DiagnosticStepRow(step: NasDiagnosticStep) {
    val (label, status) = when (step.kind) {
        NasCheckKind.URL -> "服务地址" to step.status
        NasCheckKind.HEALTH -> "健康检查" to step.status
        NasCheckKind.AUTH -> "鉴权" to step.status
        NasCheckKind.BOOKS -> "书籍接口" to step.status
        NasCheckKind.CAPABILITIES -> "能力接口" to step.status
    }
    val statusText = when (status) {
        NasCheckStatus.PASS -> "通过"
        NasCheckStatus.FAIL -> "失败"
        NasCheckStatus.SKIP -> "跳过"
    }
    val statusColor = when (status) {
        NasCheckStatus.PASS -> androidx.compose.material3.MaterialTheme.colorScheme.primary
        NasCheckStatus.FAIL -> androidx.compose.material3.MaterialTheme.colorScheme.error
        NasCheckStatus.SKIP -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Text(statusText, color = statusColor)
        }
        step.detail?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = if (status == NasCheckStatus.FAIL) statusColor else {
                    androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun BookDetailDialog(
    book: NasBook,
    nasApiUrl: String,
    nasToken: String,
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    canDownload: Boolean,
    onDownload: () -> Unit,
    canEdit: Boolean,
    onEdit: () -> Unit,
    canMove: Boolean,
    onMove: () -> Unit,
    canScrape: Boolean,
    onScrape: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.title.ifBlank { book.fileName }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AsyncImage(
                    model = rememberNasCoverRequest(book.coverUrl, nasApiUrl, nasToken),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp, 132.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .align(Alignment.CenterHorizontally),
                )
                if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                book.author?.takeIf { it.isNotBlank() }?.let { Text("作者：$it") }
                book.intro?.takeIf { it.isNotBlank() }?.let { Text(it) }
                Text("路径：${book.relativePath.ifBlank { book.fileName }}")
                if (book.size > 0) Text("大小：${formatBytes(book.size)}")
                if (book.scrapeStatus.isNotBlank()) Text("刮削状态：${book.scrapeStatus}")
                Text("能力：${if (book.id.isNotBlank()) "详情可查看" else "未知"}")
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onEdit, enabled = canEdit && !isLoading) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("编辑")
                    }
                    TextButton(onClick = onMove, enabled = canMove && !isLoading) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("移动")
                    }
                    TextButton(onClick = onScrape, enabled = canScrape && !isLoading) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("刮削")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                TextButton(
                    onClick = onDownload,
                    enabled = canDownload && !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("下载并阅读")
                }
            }
        },
    )
}

@Composable
private fun MetadataDialog(
    book: NasBook,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var title by remember(book.id) { mutableStateOf(book.displayTitle) }
    var author by remember(book.id) { mutableStateOf(book.author.orEmpty()) }
    var intro by remember(book.id) { mutableStateOf(book.intro.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 NAS 元数据") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text("书名") },
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text("作者") },
                )
                OutlinedTextField(
                    value = intro,
                    onValueChange = { intro = it },
                    enabled = enabled,
                    minLines = 3,
                    label = { Text("简介") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), author.trim(), intro.trim()) },
                enabled = enabled && title.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MoveBookDialog(
    book: NasBook,
    directories: List<io.legado.app.domain.model.NasDirectory>,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onMove: (String, String) -> Unit,
) {
    var expanded by remember(book.id) { mutableStateOf(false) }
    var selectedPath by remember(book.id) {
        mutableStateOf(directories.firstOrNull { it.directoryPath != book.directoryPath }?.directoryPath.orEmpty())
    }
    var duplicatePolicy by remember(book.id) { mutableStateOf("skip") }
    val selected = directories.firstOrNull { it.directoryPath == selectedPath }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动书籍") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        enabled = enabled && directories.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(selected?.displayName ?: "选择目标分类")
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        directories.filter { it.directoryPath != book.directoryPath }.forEach { directory ->
                            DropdownMenuItem(
                                text = { Text(directory.displayName) },
                                onClick = {
                                    selectedPath = directory.directoryPath
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                Text("重复文件处理")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("skip" to "跳过", "overwrite" to "覆盖", "copy" to "保留副本").forEach { (value, label) ->
                        OutlinedButton(
                            onClick = { duplicatePolicy = value },
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (duplicatePolicy == value) "✓ $label" else label) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onMove(selectedPath, duplicatePolicy) },
                enabled = enabled && selectedPath.isNotBlank(),
            ) { Text("移动") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun formatBytes(value: Long): String = when {
    value >= 1024 * 1024 -> "%.1f MB".format(value / (1024f * 1024f))
    value >= 1024 -> "%.1f KB".format(value / 1024f)
    else -> "$value B"
}

private val RUNNING_TASK_STATUSES = setOf("running", "pending", "queued", "started")
