package app.gallery.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.gallery.data.images.LocalThumb
import app.gallery.data.model.MediaItem
import app.gallery.data.model.MediaSource
import app.gallery.data.nextcloud.Cluster
import app.gallery.ui.GridArgs
import app.gallery.ui.LocalContainer
import app.gallery.ui.Routes
import app.gallery.ui.actions.rememberMediaActions
import app.gallery.ui.components.BottomFade
import app.gallery.ui.components.CircleBackButton
import app.gallery.ui.components.CoverImage
import app.gallery.ui.components.PillIcon
import app.gallery.ui.components.SectionHeader
import app.gallery.ui.components.TopFade
import app.gallery.ui.components.TopIconPill
import app.gallery.ui.openGrid
import app.gallery.ui.components.OneHandBehavior
import app.gallery.ui.components.OneHandHeader
import app.gallery.ui.theme.G
import app.gallery.ui.theme.Sym
import kotlinx.coroutines.launch

/** One album card's data. */
data class AlbumCardData(
    val key: String,
    val title: String,
    val count: Int,
    val cover: Any?,
    val stackCovers: List<Any?> = emptyList(),
    val cloud: Boolean = false,
    val shared: Boolean = false,
    val args: GridArgs,
)

private val ESSENTIAL = listOf("Camera", "Screenshots", "Download", "Downloads")

@Composable
fun rememberAlbumSections(): List<Pair<String, List<AlbumCardData>>> {
    val c = LocalContainer.current
    val items by c.media.timeline.collectAsState()
    val buckets by c.db.local().observeBuckets().collectAsState(emptyList())
    val clusters by c.nextcloud.clusters.collectAsState()
    val account by c.accounts.account.collectAsState()
    val uid = account?.uid
    return remember(items, buckets, clusters, account) {
        val favs = items.filter { it.isFavorite }
        val recent = items.take(3)
        fun thumbOf(m: MediaItem?) = m?.let { c.images.thumb(it, 512) }
        val essential = buildList {
            add(AlbumCardData("recent", "Recent", items.size, thumbOf(recent.firstOrNull()), recent.map { thumbOf(it) }, args = GridArgs("Recent", MediaSource.Recent)))
            add(AlbumCardData("favs", "Favourites", favs.size, thumbOf(favs.firstOrNull()), favs.take(3).map { thumbOf(it) }, args = GridArgs("Favourites", MediaSource.Favorites)))
            ESSENTIAL.forEach { name ->
                buckets.firstOrNull { it.bucketName.equals(name, true) }?.let { b ->
                    add(AlbumCardData("b${b.bucketId}", b.bucketName, b.count, LocalThumb(android.net.Uri.parse(b.coverUri), 512), args = GridArgs(b.bucketName, MediaSource.LocalBucket(b.bucketId, b.bucketName))))
                }
            }
        }
        val essentialIds = essential.map { it.key }.toSet()
        val phone = buckets.filter { "b${it.bucketId}" !in essentialIds }.map { b ->
            AlbumCardData("b${b.bucketId}", b.bucketName.ifBlank { "Internal storage" }, b.count, LocalThumb(android.net.Uri.parse(b.coverUri), 512),
                args = GridArgs(b.bucketName, MediaSource.LocalBucket(b.bucketId, b.bucketName)))
        }
        val nc = clusters["albums"].orEmpty()
        fun card(a: Cluster) = AlbumCardData(
            "nc-${a.clusterId}", a.name, a.count, c.nextcloud.clusterPreviewUrl(a), cloud = true, shared = a.user != uid,
            args = GridArgs(a.name, MediaSource.RemoteAlbum(a.user ?: uid ?: "", a.name), subtitle = if (a.user != uid) "Shared by ${a.user}" else null, albumName = if (a.user == uid) a.name else null),
        )
        buildList {
            add("Essential albums" to essential)
            if (phone.isNotEmpty()) add("Albums" to phone)
            val mine = nc.filter { it.user == uid }.map(::card)
            val shared = nc.filter { it.user != uid }.map(::card)
            if (mine.isNotEmpty()) add("Nextcloud albums" to mine)
            if (shared.isNotEmpty()) add("Shared albums" to shared)
        }
    }
}

@Composable
fun AlbumsTab(nav: NavController, @Suppress("UNUSED_PARAMETER") onSelectionMode: (Boolean) -> Unit) {
    val c = LocalContainer.current
    val sections = rememberAlbumSections()
    val account by c.accounts.account.collectAsState()
    val actions = rememberMediaActions()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(initialFirstVisibleItemIndex = 1)
    OneHandBehavior(gridState, hasContent = sections.isNotEmpty())
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = top + 58.dp, bottom = bottom + 110.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "one-hand", span = { GridItemSpan(2) }) {
                OneHandHeader("Albums", "%,d albums".format(sections.sumOf { it.second.size }))
            }
            sections.forEachIndexed { i, (title, cards) ->
                item(key = "h-$title", span = { GridItemSpan(2) }) {
                    if (i == 0) SectionHeader(title, Modifier.padding(start = 0.dp), action = "View all", size = 14.sp, onClick = { nav.navigate(Routes.ALBUMS_ALL) })
                    else SectionHeader(title, size = 14.sp)
                }
                items(cards, key = { it.key }) { a -> AlbumCard(a) { nav.openGrid(a.args) } }
            }
        }
        TopFade(Modifier.align(Alignment.TopCenter), height = 90.dp)
        BottomFade(Modifier.align(Alignment.BottomCenter))
        TopIconPill(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 10.dp, end = 12.dp), plain = true) {
            if (account != null) PillIcon(Sym.Add, "Create album") { actions.createAlbum() }
            PillIcon(Sym.Search, "Search") { nav.navigate(Routes.SEARCH) }
            Box {
                PillIcon(Sym.MoreVert, "More options") { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("View all") }, { menu = false; nav.navigate(Routes.ALBUMS_ALL) })
                    if (account != null) DropdownMenuItem({ Text("Refresh Nextcloud albums") }, { menu = false; scope.launch { c.nextcloud.clusters("albums", true) } })
                }
            }
        }
    }
}

/** Samsung-style album card: full-bleed cover with title and count over a bottom gradient. */
@Composable
fun AlbumCard(a: AlbumCardData, small: Boolean = false, onClick: () -> Unit) {
    val radius = if (small) 18.dp else G.cardRadius
    Box(
        Modifier.fillMaxWidth().aspectRatio(if (small) 0.86f else 0.83f).clip(RoundedCornerShape(radius)).background(G.colors.card).clickable(onClick = onClick),
    ) {
        if (a.stackCovers.isNotEmpty() || a.key == "recent" || a.key == "favs") {
            // Recent / Favourites: three fanned thumbnails on a plain card.
            Box(Modifier.fillMaxSize().padding(bottom = if (small) 36.dp else 50.dp), contentAlignment = Alignment.Center) {
                val s = if (small) 64.dp else 96.dp
                a.stackCovers.reversed().forEachIndexed { i, m ->
                    val n = a.stackCovers.size - 1 - i
                    CoverImage(
                        m, Modifier.offset(x = (n * 9).dp, y = (-n * 9).dp).size(s).shadow(4.dp, RoundedCornerShape(12.dp)).rotate(0f),
                        radius = 12.dp,
                    )
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = if (small) 8.dp else 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(a.title, fontSize = if (small) 10.sp else 13.sp, color = G.colors.text, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(a.count.toString(), fontSize = if (small) 8.5.sp else 10.sp, color = G.colors.textSecondary)
            }
        } else {
            CoverImage(a.cover, Modifier.fillMaxSize(), radius = 0.dp)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0x99000000))))
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = if (small) 8.dp else 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(a.title, fontSize = if (small) 10.sp else 13.sp, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.count.toString(), fontSize = if (small) 8.5.sp else 10.sp, color = Color.White.copy(alpha = 0.85f))
            }
            if (a.cloud) {
                Icon(if (a.shared) Icons.Rounded.People else Icons.Rounded.Cloud, null, Modifier.align(Alignment.TopEnd).padding(10.dp).size(18.dp), tint = Color.White)
            }
        }
    }
}

@Composable
fun AllAlbumsScreen(nav: NavController) {
    val sections = rememberAlbumSections()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize().background(G.colors.background)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = top + 70.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sections.forEach { (title, cards) ->
                item(key = "h-$title", span = { GridItemSpan(3) }) { SectionHeader(title) }
                items(cards, key = { it.key }) { a -> AlbumCard(a, small = true) { nav.openGrid(a.args) } }
            }
        }
        TopFade(Modifier.align(Alignment.TopCenter), height = 90.dp)
        CircleBackButton(Modifier.statusBarsPadding().padding(12.dp)) { nav.popBackStack() }
    }
}
