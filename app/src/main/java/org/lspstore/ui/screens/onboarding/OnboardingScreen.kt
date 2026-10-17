@file:OptIn(ExperimentalMaterial3Api::class)

package org.lspstore.ui.screens.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lspstore.domain.model.ThemeMode
import org.lspstore.ui.LocalAppContainer

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
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StepIndicator(total = state.steps.size, currentIndex = state.currentIndex)
        Spacer(Modifier.height(28.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (state.currentStep) {
                OnboardingStep.WELCOME -> WelcomeStep()
                OnboardingStep.APPEARANCE -> AppearanceStep(state, viewModel)
                OnboardingStep.PERMISSIONS -> PermissionsStep(permissions)
            }
        }
        ActionRow(state = state, onBack = viewModel::back, onSkip = viewModel::skip, onNext = viewModel::next)
    }
}

@Composable
private fun StepIndicator(total: Int, currentIndex: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { index ->
            val active = index <= currentIndex
            Box(
                modifier = Modifier
                    .size(width = if (index == currentIndex) 24.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
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
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Welcome to LSPosed Store",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Browse the LSPosed module repository, install modules and keep the " +
                "ones already on your device up to date. Everything is open source and " +
                "module data comes from modules.lsposed.org.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AppearanceStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Pick your look",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "You can change all of this later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.themeMode == mode,
                    onClick = { viewModel.setThemeMode(mode) },
                    label = { Text(mode.label) },
                )
            }
        }
        SwitchRow(
            title = "AMOLED black",
            subtitle = "Use pure black surfaces in dark mode",
            checked = state.amoled,
            onCheckedChange = viewModel::setAmoled,
        )
        SwitchRow(
            title = "Dynamic color",
            subtitle = "Use Material You colors from the wallpaper",
            checked = state.dynamicColor,
            onCheckedChange = viewModel::setDynamicColor,
        )
    }
}

@Composable
private fun PermissionsStep(permissions: OnboardingPermissions) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Two quick prompts",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Both are optional. You can change them later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PermissionRow(
            icon = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
            title = "Notifications",
            description = "Update alerts and install progress",
            granted = permissions.notificationsGranted,
            onRequest = permissions.requestNotifications,
        )
        PermissionRow(
            icon = { Icon(Icons.Outlined.Download, contentDescription = null) },
            title = "Install unknown apps",
            description = "Required to install APKs outside the Play Store",
            granted = permissions.installSourcesGranted,
            onRequest = permissions.requestInstallSources,
        )
    }
}

@Composable
private fun PermissionRow(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Text("Granted", style = MaterialTheme.typography.labelMedium)
            }
        } else {
            OutlinedButton(onClick = onRequest) { Text("Allow") }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(
    state: OnboardingUiState,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!state.isFirst) {
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.weight(1f))
        if (!state.isLast) {
            TextButton(onClick = onSkip) { Text("Skip") }
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onNext) {
            Text(if (state.isLast) "Get started" else "Next")
        }
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
                data = Uri.parse("package:${context.packageName}")
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
