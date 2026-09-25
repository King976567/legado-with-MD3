package io.legado.app.ui.main.nas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.legado.app.R
import io.legado.app.core.ui.book.BookDetailHeaderLayout
import io.legado.app.core.ui.book.BookDetailTopBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.ThemeResolver
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.card.TextCard
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.text.AnimatedTextLine
import io.legado.app.ui.widget.components.topbar.M3GlassScrollBehavior
import io.legado.app.ui.widget.components.topbar.MiuixGlassScrollBehavior
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior

/** Full-page detail state inside the NAS destination, using the shelf detail layout. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NasBookDetailScreen(
    state: NasLibraryUiState,
    onIntent: (NasLibraryIntent) -> Unit,
    coverRequest: ImageRequest?,
    onBack: () -> Unit,
) {
    val book = state.selectedBook ?: return
    key(book.id) {
        var expandedTitle by rememberSaveable { mutableStateOf(false) }
        val listState = rememberLazyListState()
        val scrollBehavior = if (ThemeResolver.isMiuixEngine(LegadoTheme.composeEngine)) {
            MiuixGlassScrollBehavior(MiuixScrollBehavior())
        } else {
            M3GlassScrollBehavior(TopAppBarDefaults.exitUntilCollapsedScrollBehavior())
        }
        val canAct = state.isConfigured && !state.isLoadingDetail && !state.isActionRunning
        val canDownload = canAct && state.capabilities.supports("download")
        AppScaffold(
            modifier = Modifier.fillMaxSize().testTag("nas-book-detail")
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                BookDetailTopBar(onBack, scrollBehavior) {
                    NasBookDetailMenu(state, onIntent)
                }
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { if (canDownload) onIntent(NasLibraryIntent.DownloadBook(book)) },
                    modifier = Modifier.testTag("nas-book-read").semantics {
                        if (!canDownload) disabled()
                    },
                    containerColor = if (canDownload) LegadoTheme.colorScheme.primaryContainer
                        else LegadoTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (canDownload) LegadoTheme.colorScheme.onPrimaryContainer
                        else LegadoTheme.colorScheme.onSurfaceVariant,
                    icon = {
                        if (state.isActionRunning) CircularProgressIndicator(Modifier.size(24.dp))
                        else Icon(Icons.Default.Book, null)
                    },
                    text = { Text(stringResource(if (state.isActionRunning)
                        R.string.feature_nas_book_working else R.string.feature_nas_book_read)) },
                )
            },
            alwaysDrawBehindBars = true,
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                // Use the already scoped/authenticated request, including for the backdrop.
                if (coverRequest != null) {
                    AsyncImage(
                        model = coverRequest, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(480.dp).blur(24.dp),
                    )
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                    LegadoTheme.colorScheme.surface.copy(alpha = 0.35f),
                    LegadoTheme.colorScheme.surface,
                ))))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 840.dp)
                        .fillMaxSize().testTag("nas-book-detail-list"),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 104.dp,
                    ),
                ) {
                    item(key = "header") {
                        BookDetailHeaderLayout(
                            cover = {
                                Box(Modifier.fillMaxWidth().aspectRatio(5f / 7f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(LegadoTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Default.Book, null, modifier = Modifier.size(40.dp),
                                        tint = LegadoTheme.colorScheme.onSurfaceVariant)
                                    AsyncImage(
                                        model = coverRequest, contentDescription = null,
                                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            },
                            details = {
                                AnimatedTextLine(book.displayTitle,
                                    style = LegadoTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                                    maxLines = if (expandedTitle) Int.MAX_VALUE else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { expandedTitle = !expandedTitle })
                                AnimatedTextLine(stringResource(R.string.author_show,
                                    book.author.orEmpty().ifBlank { stringResource(R.string.feature_nas_book_unknown_author) }),
                                    style = LegadoTheme.typography.bodyLarge,
                                    color = LegadoTheme.colorScheme.onSurfaceVariant)
                                AnimatedTextLine(stringResource(R.string.origin_show, "NAS"),
                                    style = LegadoTheme.typography.labelMedium,
                                    color = LegadoTheme.colorScheme.primary)
                            },
                            labels = {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item { DetailTag(stringResource(R.string.feature_nas_book_remote)) }
                                    val extension = book.fileName.substringAfterLast('.', "")
                                    if (extension.isNotBlank()) item { DetailTag(extension.uppercase()) }
                                    if (book.size > 0) item { DetailTag(formatBytes(book.size)) }
                                }
                            },
                        )
                    }
                    if (state.isLoadingDetail) item(key = "loading") {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("nas-book-loading"))
                    }
                    state.detailError?.let { error -> item(key = "detail-error") {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Text(error, color = LegadoTheme.colorScheme.error)
                            TextButton(onClick = { onIntent(NasLibraryIntent.OpenBook(book)) },
                                enabled = !state.isLoadingDetail) { Text(stringResource(R.string.retry)) }
                        }
                    } }
                    state.actionError?.let { error -> item(key = "action-error") {
                        Text(error, color = LegadoTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp))
                    } }
                    item(key = "intro") {
                        Column(Modifier.fillMaxWidth().background(LegadoTheme.colorScheme.surface)
                            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.feature_nas_book_download_hint),
                                style = LegadoTheme.typography.labelMedium,
                                color = LegadoTheme.colorScheme.onSurfaceVariant)
                            AnimatedTextLine(book.intro?.takeIf(String::isNotBlank)
                                ?: stringResource(R.string.intro_show_null),
                                style = LegadoTheme.typography.bodyMedium)
                        }
                    }
                    item(key = "file") {
                        Column(Modifier.fillMaxWidth().background(LegadoTheme.colorScheme.surface)
                            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.feature_nas_book_file),
                                style = LegadoTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(book.relativePath.ifBlank { book.fileName },
                                style = LegadoTheme.typography.bodyMedium)
                            if (book.scrapeStatus.isNotBlank()) Text(
                                stringResource(R.string.feature_nas_book_scrape_status, book.scrapeStatus),
                                style = LegadoTheme.typography.labelMedium,
                                color = LegadoTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailTag(text: String) {
    TextCard(text = text, textStyle = LegadoTheme.typography.labelLargeEmphasized,
        backgroundColor = LegadoTheme.colorScheme.surfaceContainer,
        contentColor = LegadoTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NasBookDetailMenu(state: NasLibraryUiState, onIntent: (NasLibraryIntent) -> Unit) {
    val book = state.selectedBook ?: return
    var expanded by rememberSaveable(book.id) { mutableStateOf(false) }
    val canAct = state.isConfigured && !state.isActionRunning && !state.isLoadingDetail
    val canWrite = canAct && !state.writeAccessDenied
    Box {
        TopBarActionButton(onClick = { expanded = true }, imageVector = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.feature_nas_book_actions))
        RoundDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            fun dispatch(intent: NasLibraryIntent) { expanded = false; onIntent(intent) }
            RoundDropdownMenuItem(text = stringResource(R.string.refresh), enabled = canAct,
                onClick = { dispatch(NasLibraryIntent.OpenBook(book)) })
            RoundDropdownMenuItem(text = stringResource(R.string.feature_nas_book_edit),
                enabled = canWrite && state.capabilities.supports("books"),
                onClick = { dispatch(NasLibraryIntent.RequestEdit(book)) })
            RoundDropdownMenuItem(text = stringResource(R.string.feature_nas_book_move),
                enabled = canWrite && state.capabilities.supports("libraryDirectories") && state.directories.isNotEmpty(),
                onClick = { dispatch(NasLibraryIntent.RequestMove(book)) })
            RoundDropdownMenuItem(text = stringResource(R.string.feature_nas_book_scrape),
                enabled = canWrite && state.capabilities.supports("scraper"),
                onClick = { dispatch(NasLibraryIntent.ScrapeBook(book)) })
        }
    }
}
