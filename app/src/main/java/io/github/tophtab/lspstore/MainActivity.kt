package io.github.tophtab.lspstore

import android.Manifest
import android.app.Activity
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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemColors
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.data.install.DownloadStatus
import io.github.tophtab.lspstore.di.AppContainer
import io.github.tophtab.lspstore.domain.model.ThemeMode
import io.github.tophtab.lspstore.ui.LocalAppContainer
import io.github.tophtab.lspstore.ui.navigation.Routes
import io.github.tophtab.lspstore.ui.screens.about.AboutScreen
import io.github.tophtab.lspstore.ui.screens.author.AuthorScreen
import io.github.tophtab.lspstore.ui.screens.detail.ModuleDetailScreen
import io.github.tophtab.lspstore.ui.screens.diagnostics.DiagnosticsScreen
import io.github.tophtab.lspstore.ui.screens.home.HomeScreen
import io.github.tophtab.lspstore.ui.screens.imports.ExternalImportScreen
import io.github.tophtab.lspstore.ui.screens.installed.InstalledScreen
import io.github.tophtab.lspstore.ui.screens.library.LibraryKind
import io.github.tophtab.lspstore.ui.screens.library.LibraryListScreen
import io.github.tophtab.lspstore.ui.screens.library.SkippedUpdatesScreen
import io.github.tophtab.lspstore.ui.screens.licenses.LicensesScreen
import io.github.tophtab.lspstore.ui.screens.mirrors.MirrorPickerScreen
import io.github.tophtab.lspstore.ui.screens.onboarding.OnboardingScreen
import io.github.tophtab.lspstore.ui.screens.settings.SettingsScreen
import io.github.tophtab.lspstore.ui.theme.LspStoreTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LspStoreApp).container
        // Keep the splash up until the stored theme has been read. Drawing the
        // first frame before that used the XML window background, which only
        // follows the system night mode and flashes white when the app itself
        // is set to dark.
        var themeReady = false
        splashScreen.setKeepOnScreenCondition { !themeReady }
        lifecycleScope.launch {
            // A failed read must not leave the splash on screen forever; fall
            // back to the Compose defaults.
            runCatching { container.settingsRepository.onboardingComplete.first() }
            themeReady = true
        }
        setContent {
            AppTheme(container) {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    AppRoot()
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

private data class NavItem(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val navItems = listOf(
    NavItem(Routes.INSTALLED, R.string.nav_installed, Icons.Filled.Apps),
    NavItem(Routes.HOME, R.string.nav_repository, Icons.Filled.Storefront),
    NavItem(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
private fun AppRoot() {
    val container = LocalAppContainer.current
    val onboardingComplete by container.settingsRepository.onboardingComplete
        .collectAsStateWithLifecycle<Boolean?>(initialValue = null)
    when (onboardingComplete) {
        null -> Box(Modifier.fillMaxSize())
        false -> OnboardingScreen()
        true -> MainContent()
    }
}

@Composable
private fun MainContent() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showNavigation = currentRoute in navItems.map { it.route }
    var reselectRoute by remember { mutableStateOf<String?>(null) }
    var reselectTick by remember { mutableIntStateOf(0) }

    // Drop a pending reselect signal once the tab changes, so returning to the
    // screen later does not trigger an unexpected scroll/refresh.
    LaunchedEffect(currentRoute) {
        if (currentRoute != reselectRoute) reselectRoute = null
    }

    RequestNotificationPermissionOnDownload()

    // NavigationSuiteScaffold follows the window size class: a bottom bar on
    // phones (portrait and landscape), a rail on tablets and other wide
    // windows. Screens that are not a root tab (details, settings pages) get
    // the full width, as they did when the bottom bar was hidden.
    val navigationSuiteState = rememberNavigationSuiteScaffoldState()
    LaunchedEffect(showNavigation) {
        if (showNavigation) navigationSuiteState.show() else navigationSuiteState.hide()
    }

    // Keep the palette the hand-rolled bar used. In dark mode primary differs
    // from the stock secondaryContainer indicator color.
    val navigationItemColors = NavigationItemColors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        selectedIndicatorColor = MaterialTheme.colorScheme.primary,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    NavigationSuiteScaffold(
        state = navigationSuiteState,
        navigationItems = {
            navItems.forEach { item ->
                val selected = item.route == currentRoute
                val label = stringResource(item.labelRes)
                NavigationSuiteItem(
                    selected = selected,
                    onClick = {
                        if (selected) {
                            // Re-selecting the current tab scrolls back to the top
                            // (and refreshes on Home/Installed).
                            reselectRoute = item.route
                            reselectTick++
                        } else {
                            reselectRoute = null
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = { Icon(imageVector = item.icon, contentDescription = label) },
                    label = { Text(text = label, maxLines = 1) },
                    colors = navigationItemColors,
                )
            }
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.INSTALLED,
            // Page switches are instant on purpose. The stock NavHost defaults
            // (700 ms cross-fade plus an animated content-size change with
            // TopStart alignment) made every switch to or from a screen without
            // the bottom bar look like the new page growing out of the top-left
            // corner.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
            sizeTransform = null,
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
private fun RequestNotificationPermissionOnDownload() {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val tasks by container.downloadManager.tasks.collectAsStateWithLifecycle()
    val downloading = tasks.values.any { it.status == DownloadStatus.RUNNING }
    var asked by rememberSaveable { mutableStateOf(false) }
    // The prompt used to open on every launch, before the user asked for
    // anything. The permission only gates the DownloadService progress
    // notification, so ask the first time there is a download to show.
    LaunchedEffect(downloading) {
        if (!downloading || asked) return@LaunchedEffect
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        asked = true
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
