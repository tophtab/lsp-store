package io.github.tophtab.lspstore.ui.design

import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

/** Chips built on komi's Material Assist/Filter chips. */
@Composable
fun KomiInfoChip(
    label: String,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = {},
        label = { ChipLabel(label) },
        modifier = modifier,
    )
}

@Composable
fun KomiFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = { ChipLabel(label) },
    )
}

@Composable
private fun ChipLabel(text: String) {
    KomiText(
        text = text,
        role = KomiTextRole.Label,
        fontSize = 12.sp,
        maxLines = 1,
    )
}
