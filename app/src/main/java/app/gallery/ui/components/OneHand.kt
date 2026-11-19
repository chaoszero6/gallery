package app.gallery.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gallery.ui.theme.G
import kotlinx.coroutines.flow.first

/*
 * One UI's one-handed view: a large title fills the top of the screen so the content starts within
 * thumb reach. Screens open with it collapsed; pulling down at the top reveals it, and letting go
 * part-way snaps it fully open or shut.
 */

/** Height of the large-title area: about a third of the screen, like One UI's expanded app bar. */
@Composable
fun oneHandHeight(): Dp = (LocalConfiguration.current.screenHeightDp * 0.36f).dp

@Composable
fun OneHandHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    // Collapsed, the header's last few dp sit in the space kept clear above the content (under the
    // status bar and top icons); fade the title out there so it never peeks through.
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val fadeStart = with(density) { 60.dp.toPx() }
    val fadeRange = with(density) { 100.dp.toPx() }
    var alpha by remember { mutableFloatStateOf(1f) }
    Box(
        modifier.fillMaxWidth().height(oneHandHeight()).onGloballyPositioned { c ->
            val bottom = c.positionInWindow().y + c.size.height
            alpha = ((bottom - statusTop - fadeStart) / fadeRange).coerceIn(0f, 1f)
        },
    ) {
        Column(Modifier.align(Alignment.BottomStart).graphicsLayer { this.alpha = alpha }.padding(start = 24.dp, end = 24.dp, bottom = 26.dp)) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = G.colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * For a lazy grid whose item 0 is the [OneHandHeader]: start collapsed (once there is content
 * below it), and snap open/shut when a scroll ends with the header part-way on screen.
 */
@Composable
fun OneHandBehavior(state: LazyGridState, hasContent: Boolean) {
    var collapsedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(hasContent) {
        if (!hasContent || collapsedOnce) return@LaunchedEffect
        if (state.firstVisibleItemIndex == 0 && state.layoutInfo.totalItemsCount > 1) state.scrollToItem(1)
        collapsedOnce = true
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (scrolling || state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset == 0) return@collect
            val h = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.size?.height ?: return@collect
            if (state.firstVisibleItemScrollOffset < h / 2) state.animateScrollToItem(0) else state.animateScrollToItem(1)
        }
    }
}

/** [OneHandBehavior] for a lazy column whose item 0 is the header. */
@Composable
fun OneHandBehavior(state: LazyListState, hasContent: Boolean) {
    var collapsedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(hasContent) {
        if (!hasContent || collapsedOnce) return@LaunchedEffect
        if (state.firstVisibleItemIndex == 0 && state.layoutInfo.totalItemsCount > 1) state.scrollToItem(1)
        collapsedOnce = true
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (scrolling || state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset == 0) return@collect
            val h = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.size ?: return@collect
            if (state.firstVisibleItemScrollOffset < h / 2) state.animateScrollToItem(0) else state.animateScrollToItem(1)
        }
    }
}

/**
 * For a scrolling column that starts with a [OneHandHeader]: returns a scroll state that starts
 * collapsed and snaps; [collapsed] reports whether the small app-bar title should show.
 */
@Composable
fun rememberOneHandScroll(): ScrollState {
    val px = with(LocalDensity.current) { oneHandHeight().roundToPx() }
    val state = remember { ScrollState(px) }
    LaunchedEffect(state) {
        snapshotFlow { state.maxValue }.first { it >= px } // content laid out: keep it collapsed
        if (state.value < px) state.scrollTo(px)
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (scrolling || state.value <= 0 || state.value >= px) return@collect
            state.animateScrollTo(if (state.value < px / 2) 0 else px)
        }
    }
    return state
}

/** True when the large title is (mostly) scrolled away, so the app bar should show the small title. */
@Composable
fun ScrollState.oneHandCollapsed(): Boolean {
    val px = with(LocalDensity.current) { oneHandHeight().roundToPx() }
    return value >= px * 0.7f
}
