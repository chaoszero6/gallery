package app.gallery.ui.clusters

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.gallery.data.model.MediaSource
import app.gallery.ui.GridArgs
import app.gallery.ui.LocalContainer
import app.gallery.ui.collections.personArgs
import app.gallery.ui.components.CircleBackButton
import app.gallery.ui.components.CoverImage
import app.gallery.ui.components.EmptyState
import app.gallery.ui.components.TopFade
import app.gallery.ui.openGrid
import app.gallery.ui.theme.G

/** All people, places or tags from Memories. */
@Composable
fun ClustersScreen(nav: NavController, backend: String) {
    val c = LocalContainer.current
    val clusters by c.nextcloud.clusters.collectAsState()
    LaunchedEffect(backend) {
        if (backend == "people") { c.nextcloud.clusters("recognize", true); c.nextcloud.clusters("facerecognition", true) }
        else c.nextcloud.clusters(backend, true)
    }
    val list = when (backend) {
        "people" -> (clusters["recognize"].orEmpty() + clusters["facerecognition"].orEmpty()).sortedWith(compareBy({ it.name.isBlank() }, { -it.count }))
        else -> clusters[backend].orEmpty().sortedByDescending { it.count }
    }
    val title = when (backend) { "people" -> "People"; "places" -> "Locations"; "tags" -> "Tags"; else -> backend }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize().background(G.colors.background)) {
        if (list.isEmpty()) EmptyState("Nothing here yet", "Memories hasn't found any $title on your server.", Modifier.align(Alignment.Center))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = top + 70.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(3) }) { Text(title, fontSize = 25.5.sp, color = G.colors.text, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp)) }
            items(list, key = { it.backend + it.clusterId }) { cl ->
                Column(Modifier.clickable {
                    nav.openGrid(
                        when (backend) {
                            "people" -> personArgs(cl)
                            "places" -> GridArgs(cl.name, MediaSource.Place(cl.clusterId.toLongOrNull() ?: 0, cl.name))
                            else -> GridArgs("#${cl.name}", MediaSource.Tag(cl.name))
                        },
                    )
                }) {
                    CoverImage(c.nextcloud.clusterPreviewUrl(cl), Modifier.fillMaxWidth().aspectRatio(1f), radius = if (backend == "people") 100.dp else 18.dp)
                    Text(
                        (if (backend == "tags") "#" else "") + cl.name.ifBlank { "Add name" }, fontSize = 12.sp, color = G.colors.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    Text("${cl.count}", fontSize = 10.sp, color = G.colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        TopFade(Modifier.align(Alignment.TopCenter), height = 90.dp)
        CircleBackButton(Modifier.statusBarsPadding().padding(12.dp)) { nav.popBackStack() }
    }
}
