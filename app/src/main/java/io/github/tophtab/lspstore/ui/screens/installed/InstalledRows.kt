package io.github.tophtab.lspstore.ui.screens.installed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.UpdateInfo
import io.github.tophtab.lspstore.domain.usecase.BatchInstallPhase
import io.github.tophtab.lspstore.domain.usecase.BatchInstallState
import io.github.tophtab.lspstore.ui.components.ModuleAvatar
import io.github.tophtab.lspstore.ui.components.PackageAvatar
import io.github.tophtab.lspstore.ui.design.KomiButton
import io.github.tophtab.lspstore.ui.design.KomiButtonSize
import io.github.tophtab.lspstore.ui.design.KomiButtonVariant
import io.github.tophtab.lspstore.ui.design.KomiSurface
import io.github.tophtab.lspstore.ui.design.KomiText
import io.github.tophtab.lspstore.ui.theme.KomiTextRole

@Composable
internal fun UpdateRow(
    info: UpdateInfo,
    install: BatchInstallState?,
    isUpdateIgnored: Boolean,
    onOpen: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    onChooseVariant: () -> Unit,
    onSkipVersion: () -> Unit,
    onToggleIgnoreUpdate: () -> Unit,
    preReleasesEnabled: Boolean,
    canTrackPreReleases: Boolean,
    onTogglePreReleases: () -> Unit,
    onUninstall: () -> Unit,
) {
    var actionsOpen by remember { mutableStateOf(false) }
    val active = install?.takeIf {
        it.phase == BatchInstallPhase.QUEUED ||
            it.phase == BatchInstallPhase.PREPARING ||
            it.phase == BatchInstallPhase.DOWNLOADING ||
            it.phase == BatchInstallPhase.WAITING_TO_INSTALL ||
            it.phase == BatchInstallPhase.INSTALLING
    }
    KomiSurface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModuleAvatar(
                info.module,
                size = 44,
                installed = info.installed,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                KomiText(
                    text = info.module.title,
                    role = KomiTextRole.Title,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                KomiText(
                    text = stringResource(
                        R.string.installed_version_arrow,
                        info.installed.versionName ?: "?",
                        info.latest.versionName,
                    ),
                    role = KomiTextRole.Mono,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                active != null -> InstallPill(
                    state = active,
                    // Once the APK is downloaded and waiting for the installer
                    // there is nothing left to cancel.
                    onCancel = if (
                        active.phase == BatchInstallPhase.QUEUED ||
                        active.phase == BatchInstallPhase.PREPARING ||
                        active.phase == BatchInstallPhase.DOWNLOADING
                    ) {
                        onCancel
                    } else {
                        null
                    },
                )

                install?.phase == BatchInstallPhase.COMPLETED && install.noticeVisible ->
                    InstallPill(state = install, onCancel = null)

                install?.phase == BatchInstallPhase.FAILED && install.noticeVisible ->
                    InstallPill(state = install, onCancel = onUpdate, retry = true)

                // Cancelled/skipped rows, and terminal marks that already
                // expired, go back to the normal action. Other rows installing
                // never disables this one: each update runs on its own chain.
                else -> KomiButton(
                    onClick = onUpdate,
                    label = stringResource(R.string.action_update),
                    variant = KomiButtonVariant.Primary,
                    size = KomiButtonSize.Small,
                    leadingIcon = Icons.Filled.SystemUpdate,
                )
            }
            Spacer(Modifier.width(2.dp))
            MoreActionsButton { actionsOpen = true }
        }
    }

    if (actionsOpen) {
        ModuleActionsSheet(
            title = info.module.title,
            repoPackageName = info.module.packageName,
            isUpdateIgnored = isUpdateIgnored,
            onDismiss = { actionsOpen = false },
            onChooseVariant = onChooseVariant,
            onSkipVersion = onSkipVersion,
            onToggleIgnoreUpdate = onToggleIgnoreUpdate,
            preReleasesEnabled = if (canTrackPreReleases) preReleasesEnabled else null,
            onTogglePreReleases = onTogglePreReleases,
            onUninstall = onUninstall,
        )
    }
}

@Composable
internal fun InstalledRow(
    module: InstalledModule,
    repoPackageName: String?,
    isUpdateIgnored: Boolean,
    onClick: (() -> Unit)?,
    onChooseVariant: (() -> Unit)?,
    onToggleIgnoreUpdate: (() -> Unit)?,
    preReleasesEnabled: Boolean,
    canTrackPreReleases: Boolean,
    onTogglePreReleases: (() -> Unit)?,
    onUninstall: () -> Unit,
) {
    val context = LocalContext.current
    var actionsOpen by remember { mutableStateOf(false) }
    // Some LSPosed modules have no launcher activity. For those, the Open action
    // is intentionally absent; the More menu still exposes variant/update
    // actions and uninstall.
    // Key on versionCode too so an update that adds/removes a launcher is picked
    // up without leaving and re-entering the screen.
    val launchIntent = remember(module.packageName, module.versionCode) {
        context.packageManager.getLaunchIntentForPackage(module.packageName)
    }

    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PackageAvatar(
                packageName = module.packageName,
                label = module.label,
                lastUpdateTime = module.lastUpdateTime,
                size = 48,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f, fill = true)) {
                KomiText(
                    text = module.label,
                    role = KomiTextRole.Title,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                KomiText(
                    text = module.versionName ?: "?",
                    role = KomiTextRole.Mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp).fillMaxWidth(),
                )
            }

            if (launchIntent != null) {
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { context.startActivity(launchIntent) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.detail_open_app),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.width(4.dp))
            MoreActionsButton { actionsOpen = true }
        }
    }
    if (onClick != null) {
        KomiSurface(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            content = content,
        )
    } else {
        KomiSurface(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            content = content,
        )
    }

    if (actionsOpen) {
        ModuleActionsSheet(
            title = module.label,
            repoPackageName = repoPackageName,
            isUpdateIgnored = isUpdateIgnored,
            onDismiss = { actionsOpen = false },
            onChooseVariant = onChooseVariant,
            onToggleIgnoreUpdate = onToggleIgnoreUpdate,
            preReleasesEnabled = if (canTrackPreReleases) preReleasesEnabled else null,
            onTogglePreReleases = onTogglePreReleases,
            onUninstall = onUninstall,
        )
    }
}
