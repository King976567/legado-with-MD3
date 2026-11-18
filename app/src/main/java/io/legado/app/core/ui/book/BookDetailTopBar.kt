package io.legado.app.core.ui.book

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
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
import io.legado.app.ui.theme.MiuixBlurredTopBar
import io.legado.app.ui.theme.responsiveHazeEffectFixedStyle
import io.legado.app.ui.widget.components.mochi.ProvideMochiScrollBlurProgress
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarScrollBehavior
import io.legado.app.ui.widget.components.topbar.M3GlassScrollBehavior
import io.legado.app.ui.widget.components.topbar.MiuixGlassScrollBehavior
import io.legado.app.ui.widget.components.topbar.MiuixNativeTopBar
import io.legado.app.ui.widget.components.topbar.TopBarActionsRow
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import ui.feice.mochi.basic.MochiButtonDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar

/** NAS details follow the upstream book-info toolbar's theme and blur behavior. */
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
        val miuixBehavior = (scrollBehavior as? MiuixGlassScrollBehavior)?.miuixBehavior
        val navigationIcon: @Composable () -> Unit = { TopBarNavigationButton(onClick = onBackPressed) }
        val barActions: @Composable RowScope.() -> Unit = { actions() }
        MiuixNativeTopBar(
            title = "",
            scrollBehavior = miuixBehavior,
            defaultWindowInsetsPadding = true,
            navigationIcon = navigationIcon,
            actions = barActions,
        ) {
            MiuixBlurredTopBar(scrollBehavior = miuixBehavior, fixedStyle = true) {
                MiuixTopAppBar(
                    title = "",
                    navigationIcon = navigationIcon,
                    actions = barActions,
                    color = resolvedColor,
                    scrollBehavior = miuixBehavior,
                )
            }
        }
    } else {
        if (isMochi) ProvideMochiScrollBlurProgress { scrollBehavior.collapsedFraction }
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
