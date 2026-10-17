package org.lspstore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.lspstore.di.AppContainer
import org.lspstore.domain.model.ThemeMode
import org.lspstore.ui.LocalAppContainer
import org.lspstore.ui.navigation.Routes
import org.lspstore.ui.screens.about.AboutScreen
import org.lspstore.ui.screens.detail.ModuleDetailScreen
import org.lspstore.ui.screens.home.HomeScreen
import org.lspstore.ui.screens.installed.InstalledScreen
import org.lspstore.ui.screens.library.LibraryKind
import org.lspstore.ui.screens.library.LibraryListScreen
import org.lspstore.ui.screens.library.SkippedUpdatesScreen
import org.lspstore.ui.screens.search.SearchScreen
import org.lspstore.ui.screens.settings.SettingsScreen
import org.lspstore.ui.theme.LspStoreTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LspStoreApp).container
        setContent {
            AppTheme(container) {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppTheme(container: AppContainer, content: @Composable () -> Unit) {
    val mode by container.settingsRepository.themeMode
        .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val amoled by container.settingsRepository.amoled
        .collectAsStateWithLifecycle(initialValue = false)
    val dynamicColor by container.settingsRepository.dynamicColor
        .collectAsStateWithLifecycle(initialValue = true)
    val darkTheme = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    LspStoreTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, amoled = amoled) {
        content()
    }
}

private data class BottomItem(val route: String, val label: String, val icon: ImageVector)

private val bottomItems = listOf(
    BottomItem(Routes.HOME, "Home", Icons.Filled.Home),
    BottomItem(Routes.SEARCH, "Search", Icons.Filled.Search),
    BottomItem(Routes.INSTALLED, "Installed", Icons.Filled.Apps),
    BottomItem(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomItems.map { it.route }

    RequestNotificationPermission()

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenModule = { navController.navigate(Routes.detail(it)) },
                    onOpenUpdates = {
                        navController.navigate(Routes.INSTALLED) { launchSingleTop = true }
                    },
                    onOpenSearch = {
                        navController.navigate(Routes.SEARCH) { launchSingleTop = true }
                    },
                    onOpenFavourites = { navController.navigate(Routes.FAVOURITES) },
                    onOpenRecentlyViewed = { navController.navigate(Routes.RECENTLY_VIEWED) },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(onOpenModule = { navController.navigate(Routes.detail(it)) })
            }
            composable(Routes.INSTALLED) {
                InstalledScreen(onOpenModule = { navController.navigate(Routes.detail(it)) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenFavourites = { navController.navigate(Routes.FAVOURITES) },
                    onOpenRecentlyViewed = { navController.navigate(Routes.RECENTLY_VIEWED) },
                    onOpenHidden = { navController.navigate(Routes.HIDDEN) },
                    onOpenSkipped = { navController.navigate(Routes.SKIPPED) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
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
            composable(Routes.HIDDEN) {
                LibraryListScreen(
                    kind = LibraryKind.HIDDEN,
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
