package app.gallery.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import app.gallery.data.model.MediaSource
import app.gallery.ui.GridArgs
import app.gallery.ui.LocalContainer
import app.gallery.ui.Routes
import app.gallery.ui.albums.AlbumsTab
import app.gallery.ui.collections.CollectionsTab
import app.gallery.ui.components.FloatingPillBar
import app.gallery.ui.components.PillTab
import app.gallery.ui.components.TextPill
import app.gallery.ui.openGrid
import app.gallery.ui.pictures.PicturesTab
import app.gallery.ui.theme.G

/** Main screen: Pictures · Albums · Collections tabs and the Menu sheet. */
@Composable
fun HomeScreen(nav: NavController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var hideBar by remember { mutableStateOf(false) }
    val c = LocalContainer.current
    val pendingOps by c.db.pendingOps().observeCount().collectAsState(0)

    BackHandler(tab != 0) { tab = 0 }

    Box(Modifier.fillMaxSize().background(G.colors.background)) {
        AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
            when (t) {
                0 -> PicturesTab(nav, onSelectionMode = { hideBar = it })
                1 -> AlbumsTab(nav, onSelectionMode = { hideBar = it })
                else -> CollectionsTab(nav)
            }
        }
        if (!hideBar) {
            FloatingPillBar(
                tabs = listOf(
                    PillTab("Pictures", Icons.Outlined.Image),
                    PillTab("Albums", Icons.Outlined.PhotoAlbum),
                    PillTab("Collections", Icons.Outlined.Collections),
                    PillTab("Menu", Icons.Rounded.Menu, badge = pendingOps > 0),
                ),
                selected = if (menu) 3 else tab,
                onSelect = { if (it == 3) menu = true else tab = it },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp),
            )
        }
        if (menu) MenuSheet(nav, onDismiss = { menu = false })
    }
}

private data class MenuEntry(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Samsung-style menu: a rounded card of round icon buttons above the tab bar. */
@Composable
private fun MenuSheet(nav: NavController, onDismiss: () -> Unit) {
    val entries = listOf(
        MenuEntry("Videos", Icons.Rounded.PlayCircleOutline) { nav.openGrid(GridArgs("Videos", MediaSource.Videos)) },
        MenuEntry("Favourites", Icons.Rounded.FavoriteBorder) { nav.openGrid(GridArgs("Favourites", MediaSource.Favorites)) },
        MenuEntry("Recent", Icons.Rounded.Schedule) { nav.openGrid(GridArgs("Recent", MediaSource.Recent)) },
        MenuEntry("Map", Icons.Rounded.Map) { nav.navigate(Routes.MAP) },
        MenuEntry("Archive", Icons.Rounded.Archive) { nav.openGrid(GridArgs("Archive", MediaSource.Archive)) },
        MenuEntry("Recycle bin", Icons.Rounded.DeleteOutline) { nav.navigate(Routes.TRASH) },
        MenuEntry("Nextcloud", Icons.Rounded.Cloud) { nav.navigate(Routes.NEXTCLOUD) },
        MenuEntry("Settings", Icons.Rounded.Settings) { nav.navigate(Routes.SETTINGS) },
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss)) {
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .shadow(18.dp, RoundedCornerShape(32.dp)).clip(RoundedCornerShape(32.dp)).background(G.colors.surface)
                    .clickable(interactionSource = null, indication = null) {}
                    .padding(vertical = 22.dp, horizontal = 10.dp),
            ) {
                entries.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { e ->
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onDismiss(); e.onClick() }.padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(Modifier.size(58.dp).clip(CircleShape).background(G.colors.background), contentAlignment = Alignment.Center) {
                                    Icon(e.icon, null, Modifier.size(26.dp), tint = G.colors.text)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(e.label, fontSize = 14.sp, color = G.colors.text, textAlign = TextAlign.Center, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionScreen(onAllow: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(G.colors.background).statusBarsPadding().navigationBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFFE9564B)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Image, null, Modifier.size(52.dp), tint = Color.White)
        }
        Spacer(Modifier.height(28.dp))
        Text("Gallery", fontSize = 30.sp, color = G.colors.text)
        Spacer(Modifier.height(12.dp))
        Text(
            "Allow access to your photos and videos to see them here. Your Nextcloud library can be added later.",
            fontSize = 16.sp, color = G.colors.textSecondary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        TextPill("Allow access", dark = true, onClick = onAllow)
        Spacer(Modifier.height(10.dp))
        Text("Not now", color = G.colors.accent, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onSkip).padding(12.dp))
    }
}
