package io.legado.app.core.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoTheme

/** Shared by shelf and NAS details; slots keep book sources and actions out of core UI. */
@Composable
fun BookDetailHeaderLayout(
    applySeedOverlay: Boolean = false,
    cover: @Composable () -> Unit,
    details: @Composable ColumnScope.() -> Unit,
    labels: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(
            Brush.verticalGradient(listOf(
                Color.Transparent,
                (if (applySeedOverlay) {
                    lerp(LegadoTheme.colorScheme.surface, LegadoTheme.seedColor, 0.08f)
                } else LegadoTheme.colorScheme.surface).copy(alpha = 0.5f),
                LegadoTheme.colorScheme.surface,
            ))
        ).padding(top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.width(112.dp)) { cover() }
                Column(
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically)
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    content = details,
                )
            }
            labels()
        }
    }
}
