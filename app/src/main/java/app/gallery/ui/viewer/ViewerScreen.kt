package app.gallery.ui.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.gallery.data.model.MediaItem
import app.gallery.ui.LocalContainer
import app.gallery.ui.transitionSettled
import app.gallery.ui.NavArgs
import app.gallery.ui.ViewerArgs
import app.gallery.ui.ViewerFocus
import app.gallery.ui.actions.MediaActions
import app.gallery.ui.actions.rememberMediaActions
import app.gallery.ui.components.CircleBackButton
import app.gallery.ui.components.FloatingActionPill
import app.gallery.ui.components.PillAction
import app.gallery.ui.components.PillIcon
import app.gallery.ui.components.TopIconPill
import app.gallery.ui.sharedMediaIf
import app.gallery.ui.theme.G
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import kotlin.math.roundToInt

@Composable
fun ViewerScreen(nav: NavController, id: String?) {
    val args = NavArgs.get<ViewerArgs>(id) ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val c = LocalContainer.current
    val snapshot by c.media.snapshot.collectAsState()
    // Keep the list, but pick up changes (favourite, backup state) from the merged library.
    var removed by remember { mutableStateOf(emptySet<String>()) }
    val items = remember(snapshot, removed) {
        args.items.filter { it.key !in removed }.map { m -> snapshot.byKey[m.key] ?: m.remoteFileId?.let { snapshot.byRemoteId[it] } ?: m }
    }
    if (items.isEmpty()) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val actions = rememberMediaActions { gone -> if (gone.isNotEmpty()) removed = removed + gone }
    val start = remember { args.items.indexOfFirst { it.key == args.startKey }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = start) { items.size }
    val ui by c.settings.ui.collectAsState(app.gallery.data.settings.UiSettings())
    ViewerContent(
        items = items, pager = pager, actions = actions, autoplay = ui.videoAutoplay,
        onBack = { nav.popBackStack() },
    )
}

@Composable
fun ViewerContent(
    items: List<MediaItem>,
    pager: androidx.compose.foundation.pager.PagerState,
    actions: MediaActions?,
    autoplay: Boolean,
    onBack: () -> Unit,
) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(true) }
    var video by remember { mutableStateOf<VideoState?>(null) }
    val settled = transitionSettled()
    var details by remember { mutableStateOf(false) }
    var slideshow by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val zoom = remember { mutableStateMapOf<Int, Float>() }
    val dragY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val account by c.accounts.account.collectAsState()
    val current = items.getOrNull(pager.currentPage.coerceIn(items.indices)) ?: return
    val bg by animateColorAsState(if (chrome && !details) G.colors.background else if (details) G.colors.background else Color.Black, label = "bg")

    BackHandler(details) { details = false }
    BackHandler(slideshow) { slideshow = false; chrome = true }

    LaunchedEffect(pager.currentPage) { ViewerFocus.key = items.getOrNull(pager.currentPage)?.key }
    DisposableEffect(Unit) { onDispose { ViewerFocus.key = null } }

    LaunchedEffect(slideshow) {
        while (slideshow) {
            delay(3500)
            if (pager.currentPage < items.size - 1) pager.animateScrollToPage(pager.currentPage + 1) else slideshow = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
        val h = maxHeight
        val pagerH by animateDpAsState(if (details) h * 0.42f else h, label = "pagerH")
        val dismissPx = with(density) { 160.dp.toPx() }
        val detailsPx = with(density) { 90.dp.toPx() }

        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { items[it].key },
            modifier = Modifier
                .fillMaxWidth()
                .height(pagerH)
                .offset { IntOffset(0, dragY.value.roundToInt()) }
                .pointerInput(details) {
                    // Vertical swipes on an unzoomed photo: down closes the viewer, up shows details.
                    detectVerticalDragGestures(
                        onDragEnd = {
                            val y = dragY.value
                            scope.launch {
                                when {
                                    !details && y > dismissPx -> onBack()
                                    !details && y < -detailsPx -> { details = true; dragY.snapTo(0f) }
                                    details && y > detailsPx -> { details = false; dragY.snapTo(0f) }
                                    else -> dragY.animateTo(0f)
                                }
                            }
                        },
                        onDragCancel = { scope.launch { dragY.animateTo(0f) } },
                    ) { change, dy ->
                        if ((zoom[pager.currentPage] ?: 0f) > 0.01f) return@detectVerticalDragGestures
                        change.consume()
                        scope.launch { dragY.snapTo(dragY.value + dy) }
                    }
                },
        ) { page ->
            val m = items[page]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (m.isVideo) {
                    val isCurrent = pager.currentPage == page && pager.isScrollInProgress.not()
                    var started by remember(m.key) { mutableStateOf(autoplay) }
                    // The preview stays underneath as the shared element and as the "shutter" until the
                    // first video frame is drawn, so opening and closing animate like a photo.
                    val playingHere = isCurrent && started && settled
                    // Kept composed (just hidden) under the playing video so it is loaded and ready to fly back on close.
                    SharedPreview(m, 1024, shared = page == pager.currentPage, visible = !(playingHere && video?.revealed == true))
                    if (playingHere) {
                        VideoPlayer(m, playing = true, showControls = chrome, modifier = Modifier.fillMaxSize(), onState = { video = it }, onTap = { chrome = !chrome })
                    } else {
                        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { chrome = !chrome })
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)).background(Color(0x66000000)).clickable { started = true }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlayArrow, "Play", Modifier.size(40.dp), tint = Color.White)
                        }
                    }
                } else {
                    PhotoPage(m, shared = page == pager.currentPage, onTap = { if (slideshow) { slideshow = false; chrome = true } else chrome = !chrome }, onZoom = { zoom[page] = it })
                }
            }
        }

        // Details panel under the shrunken photo.
        AnimatedVisibility(details, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().height(h * 0.58f).background(G.colors.background).verticalScroll(rememberScrollState())) {
                if (actions != null) DetailsPanel(current, actions)
            }
        }

        // Top chrome.
        AnimatedVisibility(chrome && !slideshow, enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleBackButton { if (details) details = false else onBack() }
                Box(Modifier.weight(1f))
                TopIconPill {
                    if (current.isRemoteOnly) PillIcon(Icons.Rounded.Cloud, "On Nextcloud", tint = G.colors.textSecondary) {}
                    PillIcon(Icons.Rounded.Slideshow, "Slideshow") { slideshow = true; chrome = false; details = false }
                    if (actions != null) Box {
                        PillIcon(Icons.Rounded.MoreVert, "More options") { menu = true }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Details") }, { menu = false; details = true })
                            DropdownMenuItem({ Text("Add to album") }, { menu = false; actions.addToAlbum(listOf(current)) })
                            if (current.isRemote && account != null) DropdownMenuItem({ Text("Move to Archive") }, { menu = false; actions.archive(listOf(current)) })
                            if (current.isLocalOnly && account != null) DropdownMenuItem({ Text("Back up to Nextcloud") }, { menu = false; actions.backUp(listOf(current)) })
                            if (current.isRemoteOnly) DropdownMenuItem({ Text("Download to phone") }, { menu = false; actions.download(listOf(current)) })
                            DropdownMenuItem({ Text("Edit date and time") }, { menu = false; actions.editDate(listOf(current)) })
                            DropdownMenuItem({ Text("Edit location") }, { menu = false; actions.editLocation(listOf(current)) })
                            DropdownMenuItem({ Text("Copy to clipboard") }, { menu = false; actions.copyToClipboard(current) })
                            if (!current.isVideo) DropdownMenuItem({ Text("Set as wallpaper") }, { menu = false; actions.setWallpaper(current) })
                        }
                    }
                }
            }
        }

        // Filmstrip + bottom actions.
        AnimatedVisibility(chrome && !details && !slideshow, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 10.dp)) {
                val v = video
                if (current.isVideo && v != null) VideoSeekBar(v)
                else Filmstrip(items, pager.currentPage) { i -> scope.launch { pager.scrollToPage(i) } }
                if (actions != null) FloatingActionPill(Modifier.padding(top = 10.dp)) {
                    PillAction(if (current.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (current.isFavorite) Color(0xFFFF4D5E) else null) {
                        actions.setFavorite(listOf(current), !current.isFavorite)
                    }
                    PillAction(Icons.Rounded.Edit, null) { actions.edit(current) }
                    PillAction(Icons.Outlined.Info, null) { details = true }
                    PillAction(Icons.Rounded.Share, null) { actions.share(listOf(current)) }
                    PillAction(Icons.Rounded.DeleteOutline, null) { actions.delete(listOf(current)) }
                }
            }
        }

        if (current.liveId != null && current.remoteFileId != null && chrome && !details) {
            LiveBadge(current, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 76.dp, top = 22.dp))
        }
    }
}

/** Viewer preview that starts from the grid's cached thumbnail, so the open transition is never blank. */
@Composable
private fun previewRequest(m: MediaItem, size: Int): ImageRequest {
    val c = LocalContainer.current
    val context = LocalContext.current
    return remember(m.key, m.remoteEtag, size) {
        ImageRequest.Builder(context).data(c.images.preview(m, size))
            .placeholderMemoryCacheKey(c.images.thumbCacheKey(m))
            .build()
    }
}

@Composable
private fun PhotoPage(m: MediaItem, shared: Boolean, onTap: () -> Unit, onZoom: (Float) -> Unit) {
    val c = LocalContainer.current
    val zs = rememberZoomableState()
    LaunchedEffect(zs) { snapshotFlow { zs.zoomFraction ?: 0f }.collect(onZoom) }
    // While the open/close animation runs only the flying preview may be visible; showing the
    // full image in place at the same time looked like two photos moving.
    val settled = transitionSettled()
    Box(Modifier.fillMaxSize()) {
        // Low-res preview underneath so the image appears at once; full image loads on top.
        SharedPreview(m, 512, shared = shared, visible = true)
        ZoomableAsyncImage(
            model = c.images.full(m),
            contentDescription = m.name,
            state = rememberZoomableImageState(zs),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().alpha(if (settled || !shared) 1f else 0f),
            onClick = { onTap() },
        )
    }
}

/**
 * The image that flies between the grid and the viewer. Its box has the photo's own aspect ratio,
 * so the animation morphs the square grid tile into the photo's outline instead of into the whole
 * screen (which blew the cropped tile up to full screen on close).
 */
@Composable
private fun SharedPreview(m: MediaItem, size: Int, shared: Boolean, visible: Boolean) {
    var aspect by remember(m.key) {
        mutableFloatStateOf(if (m.width > 0 && m.height > 0) m.width.toFloat() / m.height else 0f)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            previewRequest(m, size), m.name, contentScale = ContentScale.Fit,
            onState = { st ->
                // Decoded thumbnails are already rotated upright, so trust them over MediaStore sizes.
                val sz = st.painter?.intrinsicSize
                if (sz != null && sz.isSpecified && sz.width > 0f && sz.height > 0f) aspect = sz.width / sz.height
            },
            modifier = (if (aspect > 0f) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize())
                .sharedMediaIf(shared, m.key)
                .alpha(if (visible) 1f else 0f),
        )
    }
}

@Composable
private fun LiveBadge(m: MediaItem, modifier: Modifier) {
    val c = LocalContainer.current
    var playing by remember(m.key) { mutableStateOf(false) }
    Text(
        "LIVE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = G.colors.text,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(G.colors.pill).clickable { playing = !playing }.padding(horizontal = 10.dp, vertical = 5.dp),
    )
    if (playing) {
        Box(Modifier.fillMaxSize().clickable { playing = false }) {
            VideoPlayer(m, playing = true, showControls = false, loop = true, overrideUrl = c.api.livePhotoUrl(m.remoteFileId!!, m.liveId!!), modifier = Modifier.fillMaxSize())
        }
    }
}

/** Thumbnail strip above the action bar; the current photo is shown larger. */
@Composable
private fun Filmstrip(items: List<MediaItem>, current: Int, onPick: (Int) -> Unit) {
    val c = LocalContainer.current
    val state = rememberLazyListState()
    LaunchedEffect(current) {
        val vis = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(9)
        state.animateScrollToItem((current - vis / 2).coerceAtLeast(0))
    }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(58.dp),
    ) {
        itemsIndexed(items, key = { _, m -> m.key }) { i, m ->
            val sel = i == current
            AsyncImage(
                c.images.thumb(m, 256), null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(if (sel) 46.dp else 34.dp).height(if (sel) 56.dp else 46.dp).clip(RoundedCornerShape(4.dp))
                    .let { if (sel) it.border(2.dp, Color.White, RoundedCornerShape(4.dp)) else it }
                    .clickable { onPick(i) },
            )
        }
    }
}

/** Viewer for a photo or video opened from another app. */
@Composable
fun ExternalViewer(uri: Uri, type: String?, onClose: () -> Unit) {
    val item = remember(uri) {
        MediaItem(
            key = "ext:$uri", localId = null, localUri = uri, remoteFileId = null, remoteEtag = null, remotePath = null,
            name = uri.lastPathSegment ?: "", mime = type ?: "image/*", size = 0, takenAtMs = System.currentTimeMillis(), dayId = 0,
            width = 0, height = 0, isVideo = type?.startsWith("video") == true, durationMs = 0, isFavorite = false, liveId = null,
            bucketId = null, bucketName = null, relativePath = null,
        )
    }
    val pager = rememberPagerState { 1 }
    ViewerContent(listOf(item), pager, actions = null, autoplay = true, onBack = onClose)
}
