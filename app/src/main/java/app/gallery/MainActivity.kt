package app.gallery

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.gallery.data.model.MediaSource
import app.gallery.ui.LocalAnimScope
import app.gallery.ui.LocalContainer
import app.gallery.ui.LocalSharedScope
import app.gallery.ui.Routes
import app.gallery.ui.albums.AllAlbumsScreen
import app.gallery.ui.clusters.ClustersScreen
import app.gallery.ui.grid.GridScreen
import app.gallery.ui.home.HomeScreen
import app.gallery.ui.home.PermissionScreen
import app.gallery.ui.map.MapScreen
import app.gallery.ui.nextcloud.NextcloudScreen
import app.gallery.ui.search.SearchScreen
import app.gallery.ui.settings.SettingsScreen
import app.gallery.ui.theme.G
import app.gallery.ui.theme.GalleryTheme
import app.gallery.ui.trash.TrashScreen
import app.gallery.ui.viewer.ViewerScreen
import app.gallery.ui.viewer.ExternalViewer
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as GalleryApp).container
    private var pendingOpen by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        pendingOpen = intent?.getStringExtra("open")
        val viewUri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        setContent {
            GalleryTheme {
                CompositionLocalProvider(LocalContainer provides container) {
                    Box(Modifier.fillMaxSize().background(G.colors.background)) {
                        if (viewUri != null) ExternalViewer(viewUri, intent.type) { finish() }
                        else Root()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingOpen = intent.getStringExtra("open")
    }

    private fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION)
        Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun hasMediaPermission(): Boolean {
        fun granted(p: String) = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return when {
            // Android 14+: "Allow limited access" grants only the selected photos; that still counts.
            Build.VERSION.SDK_INT >= 34 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    private fun Root() {
        var granted by remember { mutableStateOf(hasMediaPermission()) }
        var skipped by remember { mutableStateOf(false) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = hasMediaPermission()
            if (granted) lifecycleScope.launch { container.media.refreshLocal() }
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        if (!granted && !skipped) {
            PermissionScreen(onAllow = { launcher.launch(mediaPermissions()) }, onSkip = { skipped = true })
            return
        }
        LaunchedEffect(Unit) {
            container.media.refreshLocal()
            container.backup.ensureDefaultBuckets()
        }

        val nav = rememberNavController()
        LaunchedEffect(pendingOpen) {
            if (pendingOpen == "nextcloud") { nav.navigate(Routes.NEXTCLOUD); pendingOpen = null }
        }
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedScope provides this) {
                NavHost(
                    navController = nav,
                    startDestination = Routes.HOME,
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                ) {
                    screen(Routes.HOME) { HomeScreen(nav) }
                    screen(Routes.GRID) { GridScreen(nav, it.arguments?.getString("id")) }
                    screen(Routes.VIEWER) { ViewerScreen(nav, it.arguments?.getString("id")) }
                    screen(Routes.SEARCH) { SearchScreen(nav) }
                    screen(Routes.MAP) { MapScreen(nav) }
                    screen(Routes.TRASH) { TrashScreen(nav) }
                    screen(Routes.SETTINGS) { SettingsScreen(nav) }
                    screen(Routes.NEXTCLOUD) { NextcloudScreen(nav) }
                    screen(Routes.ALBUMS_ALL) { AllAlbumsScreen(nav) }
                    screen(Routes.CLUSTERS) { ClustersScreen(nav, it.arguments?.getString("backend") ?: "places") }
                }
            }
        }
    }

    private fun androidx.navigation.NavGraphBuilder.screen(route: String, content: @Composable (NavBackStackEntry) -> Unit) {
        composable(route) { entry ->
            CompositionLocalProvider(LocalAnimScope provides this) { content(entry) }
        }
    }
}
