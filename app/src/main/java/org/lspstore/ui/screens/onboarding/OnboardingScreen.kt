@file:OptIn(ExperimentalMaterial3Api::class)

package org.lspstore.ui.screens.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lspstore.R
import org.lspstore.domain.model.ThemeMode
import org.lspstore.ui.LocalAppContainer
import org.lspstore.ui.design.KomiButton
import org.lspstore.ui.design.KomiButtonSize
import org.lspstore.ui.design.KomiButtonVariant
import org.lspstore.ui.design.KomiDivider
import org.lspstore.ui.design.KomiFilterChip
import org.lspstore.ui.design.KomiSettingsGroup
import org.lspstore.ui.design.KomiSettingsRow
import org.lspstore.ui.design.KomiSettingsSwitchRow
import org.lspstore.ui.design.KomiSurface
import org.lspstore.ui.design.KomiText
import org.lspstore.ui.labelRes
import org.lspstore.ui.theme.KomiTextRole

@Composable
fun OnboardingScreen() {
    val container = LocalAppContainer.current
    val viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permissions = rememberOnboardingPermissions()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KomiText(
                text = stringResource(R.string.app_name),
                role = KomiTextRole.Display,
                fontSize = 26.sp,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            StepIndicator(total = state.steps.size, currentIndex = state.currentIndex)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 20.dp)) {
            when (state.currentStep) {
                OnboardingStep.WELCOME -> WelcomeStep()
                OnboardingStep.APPEARANCE -> AppearanceStep(state, viewModel)
                OnboardingStep.PERMISSIONS -> PermissionsStep(permissions)
            }
        }
        ActionRow(
            state = state,
            onBack = viewModel::back,
            onSkip = viewModel::skip,
            onNext = viewModel::next,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StepIndicator(total: Int, currentIndex: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            val active = index <= currentIndex
            Box(
                modifier = Modifier
                    .size(width = if (index == currentIndex) 22.dp else 7.dp, height = 7.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = Color.Transparent,
            modifier = Modifier.size(104.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_icon),
                    contentDescription = null,
                    modifier = Modifier.size(104.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        KomiText(
            text = stringResource(R.string.onboarding_welcome_title),
            role = KomiTextRole.Display,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        KomiText(
            text = stringResource(R.string.onboarding_welcome_body),
            role = KomiTextRole.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AppearanceStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KomiText(
            text = stringResource(R.string.onboarding_appearance_title),
            role = KomiTextRole.Display,
            fontSize = 24.sp,
        )
        KomiText(
            text = stringResource(R.string.onboarding_appearance_subtitle),
            role = KomiTextRole.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KomiSettingsGroup {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        KomiFilterChip(
                            label = stringResource(mode.labelRes()),
                            selected = state.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                        )
                    }
                }
            }
            KomiDivider(color = MaterialTheme.colorScheme.outlineVariant)
            KomiSettingsSwitchRow(
                title = stringResource(R.string.settings_amoled),
                subtitle = stringResource(R.string.settings_amoled_desc),
                checked = state.amoled,
                onCheckedChange = viewModel::setAmoled,
            )
            KomiSettingsSwitchRow(
                title = stringResource(R.string.settings_dynamic),
                subtitle = stringResource(R.string.settings_dynamic_desc),
                checked = state.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
                last = true,
            )
        }
    }
}

@Composable
private fun PermissionsStep(permissions: OnboardingPermissions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KomiText(
            text = stringResource(R.string.onboarding_permissions_title),
            role = KomiTextRole.Display,
            fontSize = 24.sp,
        )
        KomiText(
            text = stringResource(R.string.onboarding_permissions_subtitle),
            role = KomiTextRole.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KomiSettingsGroup {
            PermissionRow(
                icon = Icons.Outlined.Notifications,
                title = stringResource(R.string.onboarding_notifications),
                description = stringResource(R.string.onboarding_notifications_desc),
                granted = permissions.notificationsGranted,
                onRequest = permissions.requestNotifications,
            )
            KomiDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PermissionRow(
                icon = Icons.Outlined.Download,
                title = stringResource(R.string.onboarding_install),
                description = stringResource(R.string.onboarding_install_desc),
                granted = permissions.installSourcesGranted,
                onRequest = permissions.requestInstallSources,
                last = true,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit,
    last: Boolean = false,
) {
    KomiSettingsRow(
        title = title,
        subtitle = description,
        last = last,
        leading = {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        },
        trailing = {
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(4.dp))
                    KomiText(
                        text = stringResource(R.string.onboarding_granted),
                        role = KomiTextRole.Label,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 12.sp,
                    )
                }
            } else {
                KomiButton(
                    onClick = onRequest,
                    label = stringResource(R.string.onboarding_allow),
                    variant = KomiButtonVariant.Outline,
                    size = KomiButtonSize.Small,
                )
            }
        },
    )
}

@Composable
private fun ActionRow(
    state: OnboardingUiState,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!state.isFirst) {
            KomiButton(
                onClick = onBack,
                label = stringResource(R.string.action_back),
                variant = KomiButtonVariant.Text,
                size = KomiButtonSize.Medium,
            )
        }
        Spacer(Modifier.weight(1f))
        if (!state.isLast) {
            KomiButton(
                onClick = onSkip,
                label = stringResource(R.string.onboarding_skip),
                variant = KomiButtonVariant.Text,
                size = KomiButtonSize.Medium,
            )
            Spacer(Modifier.width(6.dp))
        }
        KomiButton(
            onClick = onNext,
            label = stringResource(
                if (state.isLast) R.string.onboarding_get_started else R.string.onboarding_next,
            ),
            size = KomiButtonSize.Medium,
        )
    }
}

private data class OnboardingPermissions(
    val notificationsGranted: Boolean,
    val installSourcesGranted: Boolean,
    val requestNotifications: () -> Unit,
    val requestInstallSources: () -> Unit,
)

@Composable
private fun rememberOnboardingPermissions(): OnboardingPermissions {
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(readNotificationsGranted(context)) }
    var installSources by remember { mutableStateOf(readInstallSourcesGranted(context)) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = it
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifications = readNotificationsGranted(context)
                installSources = readInstallSourcesGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return OnboardingPermissions(
        notificationsGranted = notifications,
        installSourcesGranted = installSources,
        requestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                notifications = true
            }
        },
        requestInstallSources = {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        },
    )
}

private fun readNotificationsGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun readInstallSourcesGranted(context: Context): Boolean =
    context.packageManager.canRequestPackageInstalls()
