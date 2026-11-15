package app.gallery.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.gallery.data.model.DayIds
import app.gallery.data.model.MediaItem
import app.gallery.data.model.MediaSource
import app.gallery.data.nextcloud.Cluster
import app.gallery.ui.GridArgs
import app.gallery.ui.LocalContainer
import app.gallery.ui.Routes
import app.gallery.ui.components.BottomFade
import app.gallery.ui.components.CoverImage
import app.gallery.ui.components.PillIcon
import app.gallery.ui.components.SectionHeader
import app.gallery.ui.components.TextPill
import app.gallery.ui.components.TopFade
import app.gallery.ui.components.TopIconPill
import app.gallery.ui.map.ClusterMap
import app.gallery.ui.openClusters
import app.gallery.ui.openGrid
import app.gallery.ui.theme.G
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** A group of "On this day" photos from one past year. */
data class Story(val yearsAgo: Int, val items: List<MediaItem>) {
    val title get() = if (yearsAgo == 1) "1 year ago" else "$yearsAgo years ago"
}

@Composable
fun CollectionsTab(nav: NavController) {
    val c = LocalContainer.current
    val account by c.accounts.account.collectAsState()
    val config by c.nextcloud.config.collectAsState()
    val clusters by c.nextcloud.clusters.collectAsState()
    val timeline by c.media.timeline.collectAsState()
    val stories by produceState(emptyList<Story>(), timeline.size, account) {
        val range = config?.onThisDayRange ?: 3
        val perYear = config?.onThisDayPerYear ?: 10
        val all = c.media.onThisDay(range)
        val thisYear = LocalDate.now().year
        value = all.groupBy { thisYear - DayIds.yearOf(it.dayId) }.filterKeys { it >= 1 }
            .map { (y, list) -> Story(y, list.take(maxOf(perYear, 30))) }.sortedBy { it.yearsAgo }
    }
    val mapStart by produceState<Pair<Double, Double>?>(null, account) {
        value = if (account != null) runCatching { c.api.mapInit() }.getOrNull() else null
    }
    val people = (clusters["recognize"].orEmpty() + clusters["facerecognition"].orEmpty()).filter { it.name.isNotBlank() }.sortedByDescending { it.count }
    val places = clusters["places"].orEmpty().sortedByDescending { it.count }
    val tags = clusters["tags"].orEmpty().sortedByDescending { it.count }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33F4B8A8), G.colors.background, G.colors.background)))) {
        LazyColumn(contentPadding = PaddingValues(top = top + 64.dp, bottom = bottom + 110.dp)) {
            // Stories (On this day)
            item(key = "stories") {
                SectionHeader("Stories")
                if (stories.isEmpty()) {
                    InfoCard("No stories yet", "Photos taken on this day in earlier years will appear here.")
                } else {
                    val hero = stories.first()
                    StoryHero(hero) { nav.openGrid(GridArgs("On this day · ${hero.title}", MediaSource.Items(hero.title, hero.items.map { it.key }))) }
                    if (stories.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                        items(stories.drop(1), key = { it.yearsAgo }) { s ->
                            StoryCard(s, 170.dp) { nav.openGrid(GridArgs("On this day · ${s.title}", MediaSource.Items(s.title, s.items.map { it.key }))) }
                        }
                    }
                }
            }
            if (account == null) item(key = "connect") {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.padding(horizontal = 14.dp).clip(RoundedCornerShape(G.cardRadius)).background(G.colors.surface).padding(20.dp)) {
                    Text("People, places and map", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text)
                    Spacer(Modifier.height(6.dp))
                    Text("Connect your Nextcloud with Memories to browse faces, places, tags and a map of your photos.", color = G.colors.textSecondary, fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextPill("Connect", dark = true) { nav.navigate(Routes.NEXTCLOUD) } }
                }
            }
            if (people.isNotEmpty()) item(key = "people") {
                SectionHeader("People", Modifier.padding(top = 10.dp), onClick = { nav.openClusters("people") })
                ClusterRow(people.take(20)) { p -> nav.openGrid(personArgs(p)) }
            }
            if (account != null && config?.places != false) item(key = "locations") {
                SectionHeader("Locations", Modifier.padding(top = 10.dp), onClick = { nav.navigate(Routes.MAP) })
                Box(
                    Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(200.dp).clip(RoundedCornerShape(G.cardRadius)).background(G.colors.card),
                ) {
                    if (mapStart != null) ClusterMap(c.api, config, Modifier.fillMaxSize(), interactive = false, initial = mapStart, initialZoom = 10.0, onMapClick = { nav.navigate(Routes.MAP) }, onClusterClick = { _, _ -> nav.navigate(Routes.MAP) })
                    Box(Modifier.fillMaxSize().clickable { nav.navigate(Routes.MAP) })
                }
                if (places.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    ClusterRow(places.take(20)) { p -> nav.openGrid(GridArgs(p.name, MediaSource.Place(p.clusterId.toLongOrNull() ?: 0, p.name))) }
                }
            }
            if (tags.isNotEmpty()) item(key = "tags") {
                SectionHeader("Tags", Modifier.padding(top = 10.dp), onClick = { nav.openClusters("tags") })
                ClusterRow(tags.take(20), label = { "#${it.name}" }) { t -> nav.openGrid(GridArgs("#${t.name}", MediaSource.Tag(t.name))) }
            }
        }
        TopFade(Modifier.align(Alignment.TopCenter), height = 86.dp)
        BottomFade(Modifier.align(Alignment.BottomCenter))
        TopIconPill(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 10.dp, end = 12.dp)) {
            PillIcon(Icons.Rounded.Search, "Search") { nav.navigate(Routes.SEARCH) }
        }
    }
}

fun personArgs(p: Cluster) = GridArgs(p.name.ifBlank { "Unnamed person" }, MediaSource.Person(p.backend, p.user ?: "", p.clusterId, p.name))

@Composable
private fun StoryHero(s: Story, onClick: () -> Unit) {
    val images = LocalContainer.current.images
    val cover = s.items.firstOrNull()
    Box(
        Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(260.dp).clip(RoundedCornerShape(G.cardRadius)).clickable(onClick = onClick),
    ) {
        CoverImage(cover?.let { images.preview(it, 1024) }, Modifier.fillMaxSize(), radius = 0.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xAA000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
            Text("On this day", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(s.title + (cover?.let { " · " + DayIds.toLocalDate(it.dayId).format(DateTimeFormatter.ofPattern("d MMM yyyy")) } ?: ""), color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp)
        }
        Text(
            "${s.items.size}", color = Color.White, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x55000000)).padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun StoryCard(s: Story, w: Dp, onClick: () -> Unit) {
    val images = LocalContainer.current.images
    Box(Modifier.width(w).height(w * 1.15f).clip(RoundedCornerShape(G.cardRadius)).clickable(onClick = onClick)) {
        CoverImage(s.items.firstOrNull()?.let { images.preview(it, 512) }, Modifier.fillMaxSize(), radius = 0.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0x99000000))))
        Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            s.items.firstOrNull()?.let { Text(DayIds.toLocalDate(it.dayId).year.toString(), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp) }
        }
    }
}

@Composable
fun ClusterRow(list: List<Cluster>, label: (Cluster) -> String = { it.name }, onClick: (Cluster) -> Unit) {
    val nc = LocalContainer.current.nextcloud
    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.backend + it.clusterId }) { cl ->
            Box(Modifier.size(width = 96.dp, height = 112.dp).clip(RoundedCornerShape(18.dp)).clickable { onClick(cl) }) {
                CoverImage(nc.clusterPreviewUrl(cl), Modifier.fillMaxSize(), radius = 0.dp)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0x99000000))))
                Text(
                    label(cl), color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Column(Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(RoundedCornerShape(G.cardRadius)).background(G.colors.surface).padding(22.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text)
        Spacer(Modifier.height(4.dp))
        Text(body, fontSize = 15.sp, color = G.colors.textSecondary)
    }
}
