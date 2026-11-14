package io.github.tophtab.lspstore.ui.screens.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tophtab.lspstore.ui.components.LibraryModuleHeading
import io.github.tophtab.lspstore.ui.design.KomiButton
import io.github.tophtab.lspstore.ui.design.KomiButtonSize
import io.github.tophtab.lspstore.ui.design.KomiButtonVariant
import io.github.tophtab.lspstore.ui.design.KomiSurface

/** The restore action and module heading share one layout across library lists. */
@Composable
internal fun LibraryRestoreCard(
    title: String,
    packageName: String,
    restoreLabel: String,
    onRestore: () -> Unit,
    onOpen: (() -> Unit)? = null,
    description: @Composable () -> Unit = {},
) {
    KomiSurface(modifier = Modifier.fillMaxWidth(), onClick = onOpen, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LibraryModuleHeading(title, packageName, Modifier.weight(1f)) { description() }
            KomiButton(onClick = onRestore, label = restoreLabel, variant = KomiButtonVariant.Outline, size = KomiButtonSize.Small)
        }
    }
}
