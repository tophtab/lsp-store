package org.lspstore

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import org.lspstore.ui.design.KomiBottomBar
import org.lspstore.ui.design.KomiNavItem
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import org.lspstore.R
import org.lspstore.data.work.UpdateNotifier
import org.lspstore.di.AppContainer
import org.lspstore.domain.model.ThemeMode
import org.lspstore.ui.LocalAppContainer
import org.lspstore.ui.navigation.Routes
import org.lspstore.ui.screens.about.AboutScreen
import org.lspstore.ui.screens.author.AuthorScreen
import org.lspstore.ui.screens.detail.ModuleDetailScreen
import org.lspstore.ui.screens.diagnostics.DiagnosticsScreen
import org.lspstore.ui.screens.home.HomeScreen
import org.lspstore.ui.screens.imports.ExternalImportScreen
import org.lspstore.ui.screens.installed.InstalledScreen
import org.lspstore.ui.screens.library.LibraryKind
import org.lspstore.ui.screens.library.LibraryListScreen
import org.lspstore.ui.screens.library.SkippedUpdatesScreen
import org.lspstore.ui.screens.licenses.LicensesScreen
import org.lspstore.ui.screens.mirrors.MirrorPickerScreen
import org.lspstore.ui.screens.onboarding.OnboardingScreen
import org.lspstore.ui.screens.settings.SettingsScreen
import org.lspstore.ui.theme.LspStoreTheme

class MainActivity : ComponentActivity() {

    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingRoute = routeFromIntent(intent)
        val container = (application as LspStoreApp).container
        setContent {
            AppTheme(container) {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    AppRoot(
                        pendingRoute = pendingRoute,
                        onRouteConsumed = { pendingRoute = null },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Delegated installs never reach PackageEventReceiver while we are
        // backgrounded, so reconcile pending installs whenever we come back.
        val container = (application as LspStoreApp).container
        lifecycleScope.launch { container.reconcilePendingInstalls() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeFromIntent(intent)?.let { pendingRoute = it }
    }

    private fun routeFromIntent(intent: Intent?): String? =
        if (intent?.getBooleanExtra(UpdateNotifier.EXTRA_OPEN_UPDATES, false) == true) {
            Routes.INSTALLED
        } else {
            null
        }
}

@Composable
private fun AppTheme(container: AppContainer, content: @Composable () -> Unit) {
    val mode by container.settingsRepository.themeMode
        .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val amoled by container.settingsRepository.amoled
        .collectAsStateWithLifecycle(initialValue = false)
    val dynamicColor by container.settingsRepository.dynamicColor
        .collectAsStateWithLifecycle(initialValue = false)
    val darkTheme = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }
    LspStoreTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        amoled = amoled,
    ) {
        content()
    }
}

private data class BottomItem(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val bottomItems = listOf(
    BottomItem(Routes.INSTALLED, R.string.nav_installed, Icons.Filled.Apps),
    BottomItem(Routes.HOME, R.string.nav_repository, Icons.Filled.Storefront),
    BottomItem(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
private fun AppRoot(pendingRoute: String?, onRouteConsumed: () -> Unit) {
    val container = LocalAppContainer.current
    val onboardingComplete by container.settingsRepository.onboardingComplete
        .collectAsStateWithLifecycle<Boolean?>(initialValue = null)
    when (onboardingComplete) {
        null -> Box(Modifier.fillMaxSize())
        false -> OnboardingScreen()
        true -> MainContent(pendingRoute = pendingRoute, onRouteConsumed = onRouteConsumed)
    }
}

@Composable
private fun MainContent(pendingRoute: String?, onRouteConsumed: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomItems.map { it.route }
    var reselectRoute by remember { mutableStateOf<String?>(null) }
    var reselectTick by remember { mutableIntStateOf(0) }

    // Drop a pending reselect signal once the tab changes, so returning to the
    // screen later does not trigger an unexpected scroll/refresh.
    LaunchedEffect(currentRoute) {
        if (currentRoute != reselectRoute) reselectRoute = null
    }

    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            navController.navigate(pendingRoute) { launchSingleTop = true }
            onRouteConsumed()
        }
    }

    RequestNotificationPermission()

    val navItems = bottomItems.map { item ->
        KomiNavItem(
            id = item.route,
            label = stringResource(item.labelRes),
            icon = item.icon,
        )
    }

    Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        // The screens own the status bar inset via their own top bar; only the
        // bottom bar's height is passed on to the nav host. Without this every
        // page gets the status bar inset twice (Scaffold + KomiTopBar).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                KomiBottomBar(
                    items = navItems,
                    selectedId = currentRoute,
                    onSelect = { route ->
                        if (route == currentRoute) {
                            // Re-selecting the current tab scrolls back to the top
                            // (and refreshes on Home/Installed).
                            reselectRoute = route
                            reselectTick++
                        } else {
                            reselectRoute = null
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.INSTALLED,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenModule = { navController.navigate(Routes.detail(it)) },
                    reselectTick = if (reselectRoute == Routes.HOME) reselectTick else 0,
                )
            }
            composable(Routes.INSTALLED) {
                InstalledScreen(
                    onOpenModule = { navController.navigate(Routes.detail(it)) },
                    onOpenImports = { navController.navigate(Routes.IMPORTS) },
                    reselectTick = if (reselectRoute == Routes.INSTALLED) reselectTick else 0,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenFavourites = { navController.navigate(Routes.FAVOURITES) },
                    onOpenRecentlyViewed = { navController.navigate(Routes.RECENTLY_VIEWED) },
                    onOpenSkipped = { navController.navigate(Routes.SKIPPED) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    onOpenImports = { navController.navigate(Routes.IMPORTS) },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onOpenMirrors = { navController.navigate(Routes.MIRRORS) },
                    reselectTick = if (reselectRoute == Routes.SETTINGS) reselectTick else 0,
                )
            }
            composable(Routes.MIRRORS) {
                MirrorPickerScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLicenses = { navController.navigate(Routes.LICENSES) },
                )
            }
            composable(Routes.LICENSES) {
                LicensesScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.IMPORTS) {
                ExternalImportScreen(
                    onBack = { navController.popBackStack() },
                    onOpenModule = { navController.navigate(Routes.detail(it)) },
                )
            }
            composable(Routes.FAVOURITES) {
                LibraryListScreen(
                    kind = LibraryKind.FAVOURITES,
                    onBack = { navController.popBackStack() },
                    onOpenModule = { navController.navigate(Routes.detail(it)) },
                )
            }
            composable(Routes.RECENTLY_VIEWED) {
                LibraryListScreen(
                    kind = LibraryKind.RECENTLY_VIEWED,
                    onBack = { navController.popBackStack() },
                    onOpenModule = { navController.navigate(Routes.detail(it)) },
                )
            }
            composable(Routes.SKIPPED) {
                SkippedUpdatesScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument(Routes.DETAIL_ARG) { type = NavType.StringType }),
            ) { entry ->
                val packageName = entry.arguments?.getString(Routes.DETAIL_ARG).orEmpty()
                ModuleDetailScreen(
                    packageName = packageName,
                    onBack = { navController.popBackStack() },
                    onOpenAuthor = { navController.navigate(Routes.author(it)) },
                )
            }
            composable(
                route = Routes.AUTHOR,
                arguments = listOf(navArgument(Routes.AUTHOR_ARG) { type = NavType.StringType }),
            ) { entry ->
                val author = Uri.decode(entry.arguments?.getString(Routes.AUTHOR_ARG).orEmpty())
                AuthorScreen(
                    author = author,
                    onBack = { navController.popBackStack() },
                    onOpenModule = { navController.navigate(Routes.detail(it)) },
                )
            }
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
