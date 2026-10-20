@file:OptIn(ExperimentalMaterial3Api::class)

package org.lspstore.ui.screens.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import org.lspstore.BuildConfig
import org.lspstore.R
import org.lspstore.ui.design.KomiButton
import org.lspstore.ui.design.KomiButtonSize
import org.lspstore.ui.design.KomiButtonVariant
import org.lspstore.ui.design.KomiConstrainedContent
import org.lspstore.ui.design.KomiDivider
import org.lspstore.ui.design.KomiSurface
import org.lspstore.ui.design.KomiText
import org.lspstore.ui.design.KomiTopBar
import org.lspstore.ui.theme.KomiTextRole

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            KomiTopBar(
                title = stringResource(R.string.settings_about),
                onBack = onBack,
                backContentDescription = stringResource(R.string.action_back),
            )
        },
    ) { innerPadding ->
        KomiConstrainedContent(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KomiSurface(
                    modifier = Modifier.padding(top = 8.dp),
                    contentPadding = PaddingValues(18.dp),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(56.dp),
                                shape = MaterialTheme.shapes.medium,
                                color = Color.Transparent,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Image(
                                        painter = painterResource(R.drawable.ic_launcher_icon),
                                        contentDescription = null,
                                        modifier = Modifier.size(56.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                KomiText(
                                    text = stringResource(R.string.app_name),
                                    role = KomiTextRole.Display,
                                    fontSize = 24.sp,
                                    maxLines = 1,
                                )
                                KomiText(
                                    text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                                    role = KomiTextRole.Mono,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        KomiDivider(modifier = Modifier.padding(vertical = 14.dp))
                        KomiText(
                            text = stringResource(R.string.about_body),
                            role = KomiTextRole.Body,
                            fontSize = 13.5.sp,
                        )
                    }
                }

                KomiSurface(contentPadding = PaddingValues(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        KomiText(
                            text = stringResource(R.string.about_disclaimer),
                            role = KomiTextRole.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KomiButton(
                                onClick = { uriHandler.openUri("https://modules.lsposed.org/") },
                                label = stringResource(R.string.about_open_site),
                                variant = KomiButtonVariant.Outline,
                                size = KomiButtonSize.Small,
                            )
                            KomiButton(
                                onClick = { uriHandler.openUri("https://github.com/Xposed-Modules-Repo") },
                                label = stringResource(R.string.about_open_repo),
                                variant = KomiButtonVariant.Outline,
                                size = KomiButtonSize.Small,
                            )
                        }
                    }
                }
            }
        }
    }
}
