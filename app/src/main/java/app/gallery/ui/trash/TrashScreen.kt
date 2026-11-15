package app.gallery.ui.trash

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Restore
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.gallery.data.images.LocalThumb
import app.gallery.data.local.TrashedItem
import app.gallery.ui.LocalContainer
import app.gallery.ui.components.CircleBackButton
import app.gallery.ui.components.CoverImage
import app.gallery.ui.components.EmptyState
import app.gallery.ui.components.FloatingActionPill
import app.gallery.ui.components.PillAction
import app.gallery.ui.components.SelectMark
import app.gallery.ui.components.TopFade
import app.gallery.ui.theme.G
import kotlinx.coroutines.launch

/** Phone recycle bin (Android's own trash, emptied after 30 days) plus a pointer to Nextcloud's. */
@Composable
fun TrashScreen(nav: NavController) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<TrashedItem>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    val account by c.accounts.account.collectAsState()
    LaunchedEffect(reload) { items = c.mediaStore.trashed(); selected = emptySet() }
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) scope.launch { c.media.refreshLocal(); reload++ }
    }
    fun uris(): List<Uri> = items.filter { it.id in selected }.map { it.uri }
    val now = System.currentTimeMillis() / 1000
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize().background(G.colors.background)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(top = top + 70.dp, bottom = 140.dp),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalArrangement = Arrangement.spacedBy(1.5.dp),
        ) {
            item(span = { GridItemSpan(4) }) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                    Text("Recycle bin", fontSize = 30.sp, color = G.colors.text)
                    Text("Items are deleted forever after 30 days.", fontSize = 15.sp, color = G.colors.textSecondary)
                    if (account != null) Text(
                        "Items deleted from Nextcloud are in its trash bin. Open in browser",
                        fontSize = 15.sp, color = G.colors.accent,
                        modifier = Modifier.padding(top = 6.dp).clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(account!!.server + "/apps/files/trashbin")))
                        },
                    )
                }
            }
            items(items, key = { it.id }) { t ->
                val sel = t.id in selected
                Box(
                    Modifier.aspectRatio(1f).combinedClickable(
                        onClick = { if (selected.isNotEmpty()) selected = if (sel) selected - t.id else selected + t.id },
                        onLongClick = { selected = selected + t.id },
                    ),
                ) {
                    CoverImage(LocalThumb(t.uri, 256), Modifier.fillMaxSize(), radius = 0.dp)
                    val days = ((t.expiresSec - now) / 86400).coerceAtLeast(0)
                    Text("$days days", fontSize = 11.sp, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(5.dp))
                    if (selected.isNotEmpty()) SelectMark(sel, Modifier.align(Alignment.TopStart).padding(6.dp))
                }
            }
        }
        if (items.isEmpty()) EmptyState("No items", "Deleted pictures and videos stay here for 30 days.", Modifier.align(Alignment.Center))
        TopFade(Modifier.align(Alignment.TopCenter), height = 90.dp)
        CircleBackButton(Modifier.statusBarsPadding().padding(12.dp)) { nav.popBackStack() }
        if (items.isNotEmpty()) {
            FloatingActionPill(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)) {
                val label = if (selected.isEmpty()) "all" else "${selected.size}"
                PillAction(Icons.Rounded.Restore, "Restore $label") {
                    if (selected.isEmpty()) selected = items.map { it.id }.toSet()
                    c.mediaStore.trashRequest(uris(), trash = false)?.let { launcher.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
                }
                PillAction(Icons.Rounded.DeleteForever, "Delete $label", tint = G.colors.danger) {
                    if (selected.isEmpty()) selected = items.map { it.id }.toSet()
                    c.mediaStore.deleteRequest(uris())?.let { launcher.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
                }
            }
        }
    }
}
