package app.gallery.ui.grid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.navigation.NavController
import app.gallery.data.model.MediaItem
import app.gallery.data.model.MediaSource
import app.gallery.ui.GridArgs
import app.gallery.ui.LocalContainer
import app.gallery.ui.NavArgs
import app.gallery.ui.actions.NameDialog
import app.gallery.ui.actions.rememberMediaActions
import app.gallery.ui.components.CircleBackButton
import app.gallery.ui.components.DefaultGridLevel
import app.gallery.ui.components.EmptyState
import app.gallery.ui.components.MediaGrid
import app.gallery.ui.components.PillIcon
import app.gallery.ui.components.SelectionBottomBar
import app.gallery.ui.components.SelectionState
import app.gallery.ui.components.SelectionTopBar
import app.gallery.ui.components.TopFade
import app.gallery.ui.components.TopIconPill
import app.gallery.ui.openViewer
import app.gallery.ui.theme.G
import kotlinx.coroutines.launch

private fun MediaSource.followsTimeline() = when (this) {
    MediaSource.Timeline, MediaSource.Favorites, MediaSource.Videos, MediaSource.Recent, is MediaSource.LocalBucket, is MediaSource.Items -> true
    else -> false
}

@Composable
fun GridScreen(nav: NavController, id: String?) {
    val args = NavArgs.get<GridArgs>(id) ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val c = LocalContainer.current
    val context = LocalContext.current
    val snapshot by c.media.snapshot.collectAsState()
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf(args.title) }
    val scope = rememberCoroutineScope()
    val selection = remember { SelectionState() }
    val actions = rememberMediaActions { selection.clear(); reload++ }
    var level by remember { mutableIntStateOf(DefaultGridLevel) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    val key = if (args.source.followsTimeline()) snapshot else Unit
    LaunchedEffect(key, reload) {
        runCatching { c.media.load(args.source) }
            .onSuccess { items = it; error = null }
            .onFailure { error = it.message ?: "Could not load" ; if (items == null) items = emptyList() }
    }
    BackHandler(selection.active) { selection.clear() }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val list = items

    Box(Modifier.fillMaxSize().background(G.colors.background)) {
        when {
            list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp))
            list.isEmpty() -> EmptyState(if (error != null) "Couldn't load" else "No items", error ?: args.subtitle, Modifier.align(Alignment.Center))
            else -> MediaGrid(
                items = list, state = gridState, selection = selection, level = level, onLevelChange = { level = it },
                onOpen = { nav.openViewer(list, it, args.source) },
                contentPadding = PaddingValues(top = top + 66.dp, bottom = bottom + 100.dp),
                header = {
                    item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp)) {
                            Text(title, fontSize = 30.sp, color = G.colors.text, maxLines = 2)
                            Text(
                                listOfNotNull("${list.size} items", args.subtitle).joinToString(" · "),
                                fontSize = 15.sp, color = G.colors.textSecondary,
                            )
                        }
                    }
                },
            )
        }
        TopFade(Modifier.align(Alignment.TopCenter), height = 90.dp)
        if (selection.active && list != null) {
            SelectionTopBar(selection, list.map { it.key }, Modifier.statusBarsPadding().padding(top = 10.dp))
            val selected = list.filter { selection.isSelected(it.key) }
            SelectionBottomBar(selected, actions, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp))
        } else {
            CircleBackButton(Modifier.statusBarsPadding().padding(12.dp)) { nav.popBackStack() }
            TopIconPill(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 12.dp, end = 12.dp)) {
                Box {
                    PillIcon(Icons.Rounded.MoreVert, "More options") { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Select") }, { menu = false; selection.start() })
                        if (args.source == MediaSource.Archive && list != null) {
                            DropdownMenuItem({ Text("Unarchive all") }, { menu = false; actions.archive(list, archive = false) })
                        }
                        val album = args.albumName
                        if (album != null) {
                            DropdownMenuItem({ Text("Rename album") }, { menu = false; rename = true })
                            DropdownMenuItem({ Text("Delete album") }, {
                                menu = false
                                scope.launch {
                                    runCatching { c.nextcloud.deleteAlbum(album) }
                                        .onSuccess { Toast.makeText(context, "Album deleted (photos are kept)", Toast.LENGTH_SHORT).show(); nav.popBackStack() }
                                        .onFailure { Toast.makeText(context, "Could not delete: ${it.message}", Toast.LENGTH_SHORT).show() }
                                }
                            })
                            if (selection.keys.isEmpty()) DropdownMenuItem({ Text("Remove items from album…") }, { menu = false; selection.start() })
                        }
                    }
                }
            }
        }
        if (selection.active && args.albumName != null && list != null && selection.keys.isNotEmpty()) {
            // In one of your Nextcloud albums: remove the selection from the album (photos are kept).
            app.gallery.ui.components.TextPill(
                "Remove from album",
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 86.dp),
            ) {
                val sel = list.filter { selection.isSelected(it.key) }
                scope.launch {
                    runCatching { c.actions.removeFromRemoteAlbum(sel, args.albumName) }
                        .onFailure { Toast.makeText(context, "Could not remove: ${it.message}", Toast.LENGTH_SHORT).show() }
                    selection.clear(); reload++
                }
            }
        }
        if (rename) NameDialog("Rename album", "Album name", title, onCancel = { rename = false }) { new ->
            rename = false
            scope.launch {
                runCatching { c.nextcloud.renameAlbum(args.albumName!!, new) }
                    .onSuccess { title = new }
                    .onFailure { Toast.makeText(context, "Could not rename: ${it.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
