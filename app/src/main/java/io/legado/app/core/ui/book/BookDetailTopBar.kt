package io.legado.app.core.ui.book

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.ComposeEngine
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.LocalHazeState
import io.legado.app.ui.theme.responsiveHazeEffectFixedStyle
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarScrollBehavior
import io.legado.app.ui.widget.components.topbar.M3GlassScrollBehavior
import io.legado.app.ui.widget.components.topbar.MiuixGlassScrollBehavior
import io.legado.app.ui.widget.components.topbar.TopBarActionsRow
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.ui.widget.components.topbar.miuixTopBarActionsEndPadding
import io.legado.app.ui.widget.components.topbar.miuixTopBarSlotPadding
import ui.feice.mochi.basic.MochiButtonDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar

/** Transparent/collapsing toolbar shared by local and NAS book details. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BookDetailTopBar(
    onBackPressed: () -> Unit,
    scrollBehavior: GlassTopAppBarScrollBehavior,
    actions: @Composable () -> Unit,
) {
    val hazeState = LocalHazeState.current
    val isMiuix = LegadoTheme.composeEngine == ComposeEngine.Miuix
    val isMochi = LegadoTheme.composeEngine == ComposeEngine.Mochi
    // The M3 host supplies 4dp inside each slot. Complete the engine's outer margin.
    val slotEdgePadding = if (isMochi) MochiButtonDefaults.BarHorizontalPadding - 4.dp else 12.dp
    val collapsedColor = if (isMiuix) {
        GlassTopAppBarDefaults.getMiuixAppBarColor()
    } else {
        GlassTopAppBarDefaults.scrolledContainerColor()
    }
    val isAtTop = scrollBehavior.collapsedFraction <= 0.001f
    val resolvedColor = if (isAtTop) Color.Transparent else collapsedColor
    val topBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = resolvedColor,
        scrolledContainerColor = resolvedColor,
    )

    if (isMiuix) {
        MiuixTopAppBar(
            modifier = hazeState?.let { Modifier.responsiveHazeEffectFixedStyle(it) } ?: Modifier,
            title = "",
            subtitle = "",
            navigationIcon = {
                TopBarNavigationButton(onClick = onBackPressed)
            },
            actions = {
                TopBarActionsRow(
                    modifier = Modifier.padding(
                        end = miuixTopBarActionsEndPadding()
                    )
                ) {
                    actions()
                }
            },
            color = resolvedColor,
            navigationIconPadding = miuixTopBarSlotPadding(),
            actionIconPadding = miuixTopBarSlotPadding(),
            scrollBehavior = (scrollBehavior as? MiuixGlassScrollBehavior)?.miuixBehavior,
        )
    } else {
        MediumFlexibleTopAppBar(
            modifier = hazeState?.let { Modifier.responsiveHazeEffectFixedStyle(it) } ?: Modifier,
            title = { AppText(text = "", maxLines = 1) },
            navigationIcon = {
                // This M3 slot contributes 4dp itself. Complete Mochi's 16dp edge margin.
                TopBarNavigationButton(
                    onClick = onBackPressed,
                    modifier = if (isMochi) {
                        Modifier.padding(start = slotEdgePadding)
                    } else Modifier,
                )
            },
            actions = {
                Box(modifier = Modifier.padding(end = slotEdgePadding)) {
                    TopBarActionsRow {
                        actions()
                    }
                }
            },
            scrollBehavior = (scrollBehavior as? M3GlassScrollBehavior)?.m3Behavior,
            colors = topBarColors,
        )
    }
}
