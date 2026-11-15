package app.gallery.ui.pictures

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.gallery.data.model.MediaItem
import app.gallery.data.model.MediaSource
import app.gallery.data.settings.BackupSettings
import app.gallery.data.settings.UiSettings
import app.gallery.ui.LocalContainer
import app.gallery.ui.Routes
import app.gallery.ui.actions.rememberMediaActions
import app.gallery.ui.components.BottomFade
import app.gallery.ui.components.DefaultGridLevel
import app.gallery.ui.components.EmptyState
import app.gallery.ui.components.MediaGrid
import app.gallery.ui.components.PillIcon
import app.gallery.ui.components.SelectionBottomBar
import app.gallery.ui.components.SelectionState
import app.gallery.ui.components.SelectionTopBar
import app.gallery.ui.components.TipCard
import app.gallery.ui.components.TopFade
import app.gallery.ui.components.TopIconPill
import app.gallery.ui.openViewer
import app.gallery.ui.theme.G
import kotlinx.coroutines.launch

@Composable
fun PicturesTab(nav: NavController, onSelectionMode: (Boolean) -> Unit) {
    val c = LocalContainer.current
    val items by c.media.timeline.collectAsState()
    val loaded by c.media.localLoaded.collectAsState()
    val syncing by c.media.syncing.collectAsState()
    val account by c.accounts.account.collectAsState()
    val uiLoaded by c.settings.ui.collectAsState(null)
    val ui = uiLoaded ?: UiSettings(tipDismissed = true)
    val backup by c.settings.backup.collectAsState(BackupSettings())
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val selection = remember { SelectionState() }
    val actions = rememberMediaActions { selection.clear() }
    var level by remember(ui.gridLevel) { mutableStateOf(ui.gridLevel.takeIf { it in 0..5 } ?: DefaultGridLevel) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(selection.active) { onSelectionMode(selection.active) }
    val showTip = !ui.tipDismissed && account == null
    BackHandler(selection.active) { selection.clear() }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val showBadges = ui.showBackupBadges && account != null && (backup.autoUpload || backup.lastSyncMs > 0)
    val buckets = backup.buckets

    // Wait for settings (a few ms) so the tip card is not inserted above an already-placed grid.
    if (uiLoaded == null) return
    Box(Modifier.fillMaxSize()) {
        if (loaded && items.isEmpty() && account == null) {
            EmptyState("No pictures or videos", "Photos you take and save will show up here.", Modifier.align(Alignment.Center))
        }
        MediaGrid(
            items = items,
            state = gridState,
            selection = selection,
            level = level,
            onLevelChange = { level = it; scope.launch { c.settings.setGridLevel(it) } },
            onOpen = { nav.openViewer(items, it, MediaSource.Timeline) },
            contentPadding = PaddingValues(top = top + 72.dp, bottom = bottom + 110.dp),
            notBackedUp = { m: MediaItem -> showBadges && m.isLocalOnly && m.bucketId != null && m.bucketId in buckets },
            header = if (showTip) {
                {
                    item(key = "tip", span = { GridItemSpan(maxLineSpan) }) {
                        TipCard(
                            title = "Keep your memories safe",
                            body = "Connect your Nextcloud to back up this phone and see your whole Memories library in one place.",
                            button = "Get started",
                            onButton = { nav.navigate(Routes.NEXTCLOUD) },
                            onDismiss = { scope.launch { c.settings.setTipDismissed(true) } },
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
            } else null,
        )
        TopFade(Modifier.align(Alignment.TopCenter))
        BottomFade(Modifier.align(Alignment.BottomCenter))

        if (selection.active) {
            val selected = selection.keys.mapNotNull { k -> c.media.item(k) }
            SelectionTopBar(selection, items.map { it.key }, Modifier.statusBarsPadding().padding(top = 10.dp))
            SelectionBottomBar(selected, actions, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp))
        } else {
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 10.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(syncing, enter = fadeIn(), exit = fadeOut()) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp, color = G.colors.textSecondary)
                }
                TopIconPill {
                    PillIcon(if (account != null) Icons.Outlined.CloudSync else Icons.Outlined.Cloud, "Nextcloud backup") { nav.navigate(Routes.NEXTCLOUD) }
                    PillIcon(Icons.Rounded.Search, "Search") { nav.navigate(Routes.SEARCH) }
                    Box {
                        PillIcon(Icons.Rounded.MoreVert, "More options") { menu = true }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Select") }, { menu = false; selection.start() })
                            if (account != null) DropdownMenuItem({ Text("Refresh from Nextcloud") }, { menu = false; scope.launch { c.media.syncRemote() } })
                            DropdownMenuItem({ Text("Settings") }, { menu = false; nav.navigate(Routes.SETTINGS) })
                        }
                    }
                }
            }
        }
    }
}
