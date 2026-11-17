package app.gallery.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import app.gallery.data.model.MediaItem
import app.gallery.data.model.MediaSource
import app.gallery.data.nextcloud.MapClusterDto
import app.gallery.ui.LocalContainer
import app.gallery.ui.components.CircleBackButton
import app.gallery.ui.components.EmptyState
import app.gallery.ui.components.MediaGrid
import app.gallery.ui.components.PillIcon
import app.gallery.ui.components.SelectionState
import app.gallery.ui.components.TextPill
import app.gallery.ui.components.TopIconPill
import app.gallery.ui.openViewer
import app.gallery.ui.theme.G
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap

/** Full-screen map of the Memories library with a photo sheet for the tapped cluster. */
@Composable
fun MapScreen(nav: NavController) {
    val c = LocalContainer.current
    val account by c.accounts.account.collectAsStateCompat()
    if (account == null) {
        Box(Modifier.fillMaxSize().background(G.colors.background)) {
            EmptyState("Map needs Nextcloud", "Connect your Nextcloud server with Memories to see your photos on a map.", Modifier.align(Alignment.Center))
            CircleBackButton(Modifier.statusBarsPadding().padding(12.dp)) { nav.popBackStack() }
        }
        return
    }
    val config by c.nextcloud.config.collectAsStateCompat()
    val start by produceState<Pair<Double, Double>?>(null, true) { value = runCatching { c.api.mapInit() }.getOrNull() }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(start) { ready = true }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var selected by remember { mutableStateOf<Pair<MapClusterDto, Int>?>(null) }
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var area by remember { mutableStateOf<MediaSource.MapArea?>(null) }

    LaunchedEffect(selected) {
        val (cl, z) = selected ?: run { items = null; return@LaunchedEffect }
        items = null
        val b = clusterBounds(cl, z, config?.mapDensity ?: 1)
        val src = MediaSource.MapArea(b.minLat, b.maxLat, b.minLon, b.maxLon)
        area = src
        items = runCatching { c.media.load(src) }.getOrDefault(emptyList())
    }

    Box(Modifier.fillMaxSize().background(G.colors.background)) {
        if (ready) {
            ClusterMap(
                api = c.api, config = config, modifier = Modifier.fillMaxSize(),
                initial = start, initialZoom = 11.0, selectedId = selected?.first?.id,
                onClusterClick = { cl, z -> selected = cl to z },
                onMapClick = { selected = null },
                onReady = { map = it },
            )
        }
        CircleBackButton(Modifier.statusBarsPadding().padding(12.dp)) { nav.popBackStack() }
        Column(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 14.dp, bottom = if (selected != null) 0.dp else 24.dp)) {
            if (selected == null) TopIconPill(Modifier.padding(bottom = 8.dp)) {
                PillIcon(Icons.Rounded.Add, "Zoom in") { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
                PillIcon(Icons.Rounded.Remove, "Zoom out") { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
            }
        }
        // Photo sheet for the selected cluster (drag to resize, drag down to close).
        val screenH = LocalConfiguration.current.screenHeightDp.dp
        var sheetFrac by remember { mutableFloatStateOf(0.45f) }
        AnimatedVisibility(
            selected != null, enter = slideInVertically { it }, exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth().height(screenH * sheetFrac).padding(horizontal = 8.dp)
                    .shadow(16.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(G.colors.surface),
            ) {
                Column(
                    Modifier.fillMaxWidth().pointerInput(Unit) {
                        detectVerticalDragGestures { _, dy ->
                            val f = sheetFrac - dy / screenH.toPx()
                            if (f < 0.2f) selected = null else sheetFrac = f.coerceIn(0.25f, 0.92f)
                        }
                    }.padding(top = 10.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(G.colors.textSecondary.copy(alpha = 0.4f)))
                    Text(
                        items?.let { "${it.size} items" } ?: "${selected?.first?.count ?: 0} items",
                        fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                val list = items
                if (list == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) }
                else {
                    val sel = remember { SelectionState() }
                    var level by remember { mutableStateOf(2) }
                    MediaGrid(
                        items = list, state = rememberLazyGridState(), selection = sel, level = level, onLevelChange = { level = it },
                        onOpen = { nav.openViewer(list, it, area) },
                        contentPadding = PaddingValues(bottom = 24.dp),
                    )
                }
            }
        }
    }
}

/** Pick a location by moving the map under a centre pin. */
@Composable
fun LocationPickerDialog(onDismiss: () -> Unit, onPick: (Double, Double) -> Unit) {
    val c = LocalContainer.current
    val config by c.nextcloud.config.collectAsStateCompat()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val start by produceState<Pair<Double, Double>?>(null, true) {
        value = if (c.accounts.account.value != null) runCatching { c.api.mapInit() }.getOrNull() else null
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(G.colors.background)) {
            ClusterMap(
                api = c.api, config = config, modifier = Modifier.fillMaxSize(),
                initial = start, initialZoom = if (start != null) 12.0 else 1.5, onReady = { map = it },
            )
            Text("📍", fontSize = 34.sp, modifier = Modifier.align(Alignment.Center).padding(bottom = 44.dp))
            Text(
                "Move the map to place the pin", color = G.colors.text, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 18.dp)
                    .clip(RoundedCornerShape(20.dp)).background(G.colors.pill).padding(horizontal = 16.dp, vertical = 8.dp),
            )
            androidx.compose.foundation.layout.Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)) {
                TextPill("Cancel", onClick = onDismiss)
                androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
                TextPill("Done") {
                    val t = map?.cameraPosition?.target ?: return@TextPill
                    onPick(t.latitude, t.longitude)
                }
            }
        }
    }
}

@Composable
fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateCompat(): androidx.compose.runtime.State<T> = collectAsState()
