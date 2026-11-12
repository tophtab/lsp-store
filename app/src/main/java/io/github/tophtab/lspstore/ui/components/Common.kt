@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import io.github.tophtab.lspstore.data.device.PackageIcon
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.ui.design.KomiEmpty
import io.github.tophtab.lspstore.ui.design.KomiError
import io.github.tophtab.lspstore.ui.design.KomiLoading
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole
import java.time.Instant

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    KomiLoading(modifier)
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    KomiError(message = message, onRetry = onRetry, modifier = modifier)
}

@Composable
fun EmptyBox(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    KomiEmpty(
        message = message,
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

@Composable
fun VersionChip(text: String, container: Color, content: Color) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
    ) {
        KomiText(
            text = text,
            role = KomiTextRole.Label,
            color = content,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
        )
    }
}

@Composable
fun ModuleAvatar(module: Module, size: Int = 48, installed: InstalledModule? = null) {
    val avatar = module.contributorAvatarUrl
    val shape = RoundedCornerShape(14.dp)
    val monogram = module.title.trim().take(2).uppercase()
    when {
        installed != null -> PackageAvatar(
            packageName = installed.packageName,
            label = module.title,
            lastUpdateTime = installed.lastUpdateTime,
            size = size,
        )

        !avatar.isNullOrBlank() -> AsyncImage(
            model = avatar,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )

        else -> MonogramTile(monogram, size, shape)
    }
}

/**
 * Launcher icon for an installed package, with the same tile treatment as
 * [ModuleAvatar]. Used by the installed list, where only the package name is
 * known.
 */
@Composable
fun PackageAvatar(packageName: String, label: String?, lastUpdateTime: Long, size: Int = 44) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.dp.roundToPx() }
    val request = remember(context, packageName, lastUpdateTime, sizePx) {
        ImageRequest.Builder(context)
            .data(PackageIcon(packageName, lastUpdateTime, sizePx))
            .size(sizePx)
            .precision(Precision.EXACT)
            .build()
    }
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.size(size.dp).clip(shape)) {
        Box(Modifier.clearAndSetSemantics {}) {
            MonogramTile(label.orEmpty().trim().take(2).uppercase(), size, shape)
        }
        AsyncImage(
            model = request,
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun MonogramTile(
    monogram: String,
    size: Int,
    shape: androidx.compose.ui.graphics.Shape,
) {
    Surface(
        modifier = Modifier.size(size.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (monogram.isBlank()) {
                Icon(
                    Icons.Filled.Extension,
                    contentDescription = null,
                    modifier = Modifier.size((size * 0.4f).dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                KomiText(
                    text = monogram,
                    role = KomiTextRole.Title,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = (size * 0.36f).sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun ExtensionAvatar(size: Int = 44) {
    Surface(
        modifier = Modifier.size(size.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Extension,
                contentDescription = null,
                modifier = Modifier.size((size * 0.46f).dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun formatRelativeTime(instant: Instant?): String {
    if (instant == null) return ""
    return DateUtils.getRelativeTimeSpanString(
        instant.toEpochMilli(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
