package io.legado.app.ui.main

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey

/** One reader slot keeps its session while requests or source-book URLs change. */
internal fun EntryProviderScope<NavKey>.mangaReaderEntry(
    metadata: Map<String, Any> = emptyMap(),
    content: @Composable (route: MainRouteReadManga, viewModelKey: String) -> Unit,
) {
    // Clearing the old store during a reopen would save its stale progress over a TOC selection.
    entry<MainRouteReadManga>(
        clazzContentKey = { "manga-reader" },
        metadata = metadata,
    ) { route ->
        content(route, "ReadManga")
    }
}
