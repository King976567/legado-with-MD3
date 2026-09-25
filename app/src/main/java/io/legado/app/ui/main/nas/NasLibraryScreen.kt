package io.legado.app.ui.main.nas

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudDownload
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import io.legado.app.domain.model.NasTask
import io.legado.app.R
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.domain.model.NasCheckKind
import io.legado.app.domain.model.NasCheckStatus
import io.legado.app.domain.model.NasDiagnosticReport
import io.legado.app.domain.model.NasDiagnosticStep
import io.legado.app.help.coil.CoverExtras
import io.legado.app.help.coil.nasCoverHeaders
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
    BackHandler(enabled = state.selectedBook != null) {
        viewModel.onIntent(NasLibraryIntent.DismissBook)
    }
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
    var showTasks by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val latestState by rememberUpdatedState(state)
    val latestIntent by rememberUpdatedState(onIntent)
    val focusManager = LocalFocusManager.current
    var lastGeneration by rememberSaveable { mutableStateOf(state.listGeneration) }
    LaunchedEffect(state.listGeneration) {
        if (lastGeneration != state.listGeneration) {
            listState.scrollToItem(0)
            lastGeneration = state.listGeneration
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            latestState.selectedBook == null && latestState.canGoNext && layout.totalItemsCount > 0 &&
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 3
        }.distinctUntilChanged().collect { shouldLoad ->
            if (shouldLoad) latestIntent(NasLibraryIntent.NextPage)
        }
    }
    fun openTasks() {
        showTasks = true
        onIntent(NasLibraryIntent.LoadTasks)
    }
    if (state.selectedBook == null) {
        Scaffold(
            modifier = Modifier.imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text("我的 NAS") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { onIntent(NasLibraryIntent.Refresh) }, enabled = !state.isRefreshing) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = ::openTasks) {
                            Icon(Icons.Default.History, contentDescription = stringResource(R.string.feature_nas_tasks))
                        }
                        LibraryManagementMenu(state, onIntent, onOpenSettings)
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .testTag("nas-library-list"),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "search") {
                    SearchBar(
                        query = state.query,
                        onQueryChanged = { onIntent(NasLibraryIntent.QueryChanged(it)) },
                        onSubmit = {
                            focusManager.clearFocus()
                            onIntent(NasLibraryIntent.SubmitSearch)
                        },
                        enabled = state.isConfigured,
                    )
                }
                item(key = "categories") { DirectoryFilter(state = state, onIntent = onIntent) }
                item(key = "connection") {
                    ConnectionBanner(state = state, onRetry = { onIntent(NasLibraryIntent.Retry) })
                }
                val runningTasks = state.tasks.count { it.status.lowercase() in RUNNING_TASK_STATUSES }
                if (runningTasks > 0) {
                    item(key = "running-tasks") {
                        TextButton(onClick = ::openTasks, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.feature_nas_running_tasks, runningTasks))
                        }
                    }
                }
                state.actionError?.let { error ->
                    item(key = "action-error") {
                        Text(error, color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
                when {
                    !state.isConfigured -> item(key = "unconfigured") { UnconfiguredContent() }
                    state.isLoading && state.books.isEmpty() -> item(key = "loading") { LoadingContent() }
                    state.error != null && state.books.isEmpty() -> item(key = "error") {
                        ErrorContent(state.error) { onIntent(NasLibraryIntent.Retry) }
                    }
                    state.books.isEmpty() -> item(key = "empty") { EmptyContent(state.appliedQuery) }
                    else -> {
                        items(state.books, key = { "book:${it.id}" }, contentType = { "book" }) { book ->
                            Box(Modifier.padding(horizontal = 16.dp)) {
                                NasBookRow(book, nasApiUrl, nasToken) {
                                    onIntent(NasLibraryIntent.OpenBook(book))
                                }
                            }
                        }
                        item(key = "load-more") { LoadMoreFooter(state, onIntent) }
                    }
                }
            }
        }
        AppModalBottomSheet(
            show = showTasks,
            onDismissRequest = { showTasks = false },
            title = stringResource(R.string.feature_nas_tasks),
        ) {
            TasksPanel(state = state, onIntent = onIntent)
        }
    } else {
        NasBookDetailScreen(
            state = state,
            onIntent = onIntent,
            coverRequest = rememberNasCoverRequest(state.selectedBook.coverUrl, nasApiUrl, nasToken),
            onBack = { onIntent(NasLibraryIntent.DismissBook) },
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
            enabled = state.isConfigured,
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
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.feature_nas_connected_count, state.total),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
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
private fun LibraryManagementMenu(
    state: NasLibraryUiState,
    onIntent: (NasLibraryIntent) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val canWrite = state.isConfigured && !state.isActionRunning && !state.writeAccessDenied
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.feature_nas_manage))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fun dispatch(intent: NasLibraryIntent) { expanded = false; onIntent(intent) }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_nas_upload)) },
                enabled = canWrite && state.capabilities.supports("upload"),
                onClick = { dispatch(NasLibraryIntent.RequestUpload) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_nas_index)) },
                enabled = canWrite && state.capabilities.supportsIndexer,
                onClick = { dispatch(NasLibraryIntent.RefreshIndex) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_nas_scrape_pending)) },
                enabled = canWrite && state.capabilities.supports("scraper"),
                onClick = { dispatch(NasLibraryIntent.ScrapePending) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_nas_retry_scrape)) },
                enabled = canWrite && state.capabilities.supports("scraper"),
                onClick = { dispatch(NasLibraryIntent.RetryFailedScrape) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_nas_diagnose)) },
                enabled = !state.isDiagnosing,
                onClick = { dispatch(NasLibraryIntent.Diagnose) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_nas_settings)) },
                onClick = { expanded = false; onOpenSettings() },
            )
        }
    }
}

@Composable
private fun TasksPanel(state: NasLibraryUiState, onIntent: (NasLibraryIntent) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag("nas-task-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = { onIntent(NasLibraryIntent.LoadTasks) }, enabled = state.isConfigured) {
                Text(stringResource(R.string.feature_nas_refresh_tasks))
            }
        }
        state.actionError?.let { error ->
            item {
                Text(error, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
        }
        if (state.tasks.isEmpty()) {
            item { Text(stringResource(R.string.feature_nas_no_tasks)) }
        }
        items(state.tasks) { task ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val kind = when (task.kind.lowercase()) {
                        "indexer" -> stringResource(R.string.feature_nas_index_task)
                        "scraper" -> stringResource(R.string.feature_nas_scrape_task)
                        else -> task.kind.ifBlank { task.type ?: "NAS" }
                    }
                    Text("$kind · ${taskStatusLabel(task)}")
                    if (task.total > 0) {
                        Text("${task.processed} / ${task.total}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                    (task.finishedAt ?: task.startedAt)?.let {
                        Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                    task.lastError?.let {
                        Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    }
                }
                if (task.kind.lowercase() in setOf("indexer", "scraper") &&
                    task.id.isNotBlank() && task.status.lowercase() in RUNNING_TASK_STATUSES
                ) {
                    TextButton(
                        onClick = { onIntent(NasLibraryIntent.CancelTask(task.id, task.kind)) },
                        enabled = !state.isActionRunning && !state.writeAccessDenied && !task.cancelRequested,
                    ) {
                        Text(stringResource(if (task.cancelRequested) R.string.feature_nas_cancelling
                            else R.string.feature_nas_cancel_task))
                    }
                }
            }
        }
    }
}

@Composable
private fun taskStatusLabel(task: NasTask): String = stringResource(
    when (task.status.lowercase()) {
        "running", "started" -> R.string.feature_nas_task_running
        "pending", "queued" -> R.string.feature_nas_task_queued
        "completed", "complete", "success", "succeeded", "done", "finished" -> R.string.feature_nas_task_done
        "failed", "error" -> R.string.feature_nas_task_failed
        "cancelled", "canceled" -> R.string.feature_nas_task_cancelled
        else -> R.string.feature_nas_task_unknown
    }
)

@Composable
private fun LoadMoreFooter(state: NasLibraryUiState, onIntent: (NasLibraryIntent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("nas-load-more"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            state.isLoadingMore -> CircularProgressIndicator(Modifier.size(24.dp))
            state.loadMoreError != null -> {
                Text(state.loadMoreError, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                TextButton(onClick = { onIntent(NasLibraryIntent.RetryLoadMore) }) {
                    Text(stringResource(R.string.feature_nas_retry_load))
                }
            }
            state.canGoNext -> TextButton(onClick = { onIntent(NasLibraryIntent.NextPage) }) {
                Text(stringResource(R.string.feature_nas_load_more))
            }
            else -> Text(stringResource(R.string.feature_nas_loaded_count, state.books.size))
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

internal fun formatBytes(value: Long): String = when {
    value >= 1024 * 1024 -> "%.1f MB".format(value / (1024f * 1024f))
    value >= 1024 -> "%.1f KB".format(value / 1024f)
    else -> "$value B"
}

private val RUNNING_TASK_STATUSES = setOf("running", "pending", "queued", "started")
