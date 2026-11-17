package app.gallery.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toIntRect
import app.gallery.data.model.DayIds
import app.gallery.data.model.MediaItem
import app.gallery.ui.ViewerFocus
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import app.gallery.ui.theme.G
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Grouping used by a zoom level of the grid. */
enum class Grouping { DAY, MONTH, YEAR }

data class GridLevel(val columns: Int, val grouping: Grouping)

/** Pinch levels, from closest (3 columns) to the year overview. */
val GridLevels = listOf(
    GridLevel(2, Grouping.DAY),
    GridLevel(3, Grouping.DAY),
    GridLevel(4, Grouping.DAY),
    GridLevel(5, Grouping.DAY),
    GridLevel(7, Grouping.MONTH),
    GridLevel(11, Grouping.YEAR),
)
const val DefaultGridLevel = 2

/** Selected keys of a grid. Shared by the grid and the screen's action bar. */
@Stable
class SelectionState {
    var keys by mutableStateOf<Set<String>>(emptySet())
        private set
    var active by mutableStateOf(false)
        private set

    fun start(key: String? = null) { active = true; keys = key?.let { setOf(it) } ?: emptySet() }
    fun clear() { active = false; keys = emptySet() }
    fun toggle(key: String) { keys = if (key in keys) keys - key else keys + key }
    fun set(newKeys: Set<String>) { keys = newKeys }
    fun isSelected(key: String) = key in keys
}

sealed interface GridEntry {
    val key: String
    data class Header(override val key: String, val label: String, val sub: String?, val itemKeys: List<String>) : GridEntry
    data class Cell(val item: MediaItem, val index: Int) : GridEntry { override val key get() = item.key }
}

private val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val dayYearFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

fun dayLabel(dayId: Int, today: LocalDate = LocalDate.now()): String {
    val d = DayIds.toLocalDate(dayId)
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.year == today.year -> d.format(dayFmt)
        else -> d.format(dayYearFmt)
    }
}

/**
 * Splits [items] into header + cell entries, starting a new header whenever the group changes.
 * Lists not sorted by day (Recent is sorted by date modified) can come back to a group that
 * already had a header; that header gets a numbered key, because lazy-grid keys must be unique.
 */
fun buildEntries(items: List<MediaItem>, grouping: Grouping): List<GridEntry> {
    val out = ArrayList<GridEntry>(items.size + items.size / 4)
    val seen = HashMap<Int, Int>()
    var curGroup = Int.MIN_VALUE
    var headerIndex = -1
    var keys = ArrayList<String>()
    val today = LocalDate.now()
    items.forEachIndexed { i, m ->
        val g = when (grouping) {
            Grouping.DAY -> m.dayId
            Grouping.MONTH -> DayIds.monthOf(m.dayId)
            Grouping.YEAR -> DayIds.yearOf(m.dayId)
        }
        if (g != curGroup) {
            if (headerIndex >= 0) out[headerIndex] = (out[headerIndex] as GridEntry.Header).copy(itemKeys = keys)
            keys = ArrayList()
            curGroup = g
            val label = when (grouping) {
                Grouping.DAY -> dayLabel(g, today)
                Grouping.MONTH -> DayIds.toLocalDate(g).format(monthFmt)
                Grouping.YEAR -> g.toString()
            }
            headerIndex = out.size
            val n = seen.merge(g, 1, Int::plus)!!
            out += GridEntry.Header(if (n == 1) "h-$grouping-$g" else "h-$grouping-$g-$n", label, null, emptyList())
        }
        keys += m.key
        out += GridEntry.Cell(m, i)
    }
    if (headerIndex >= 0) out[headerIndex] = (out[headerIndex] as GridEntry.Header).copy(itemKeys = keys)
    return out
}

/**
 * The Pictures-style grid: date headers, pinch to change density, long-press + drag to select,
 * floating date bubble and a draggable scroll thumb.
 */
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    state: LazyGridState,
    selection: SelectionState,
    level: Int,
    onLevelChange: (Int) -> Unit,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    notBackedUp: (MediaItem) -> Boolean = { false },
    showHeaders: Boolean = true,
    header: (LazyGridScope.() -> Unit)? = null,
) {
    val lv = GridLevels[level.coerceIn(GridLevels.indices)]
    val entries = remember(items, lv.grouping, showHeaders) {
        if (showHeaders) buildEntries(items, lv.grouping) else items.mapIndexed { i, m -> GridEntry.Cell(m, i) }
    }
    val headerCount = if (header != null) 1 else 0
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentEntries by rememberUpdatedState(entries)
    val currentLevel by rememberUpdatedState(level)
    val compact = lv.columns >= 7

    // Keep the same photo at the top when the column count changes.
    var anchorKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(level) {
        val k = anchorKey ?: return@LaunchedEffect
        val idx = entries.indexOfFirst { it.key == k }
        if (idx >= 0) state.scrollToItem(idx + headerCount)
        anchorKey = null
    }

    // Coming back from the viewer: make sure the photo it ended on is on screen, so the
    // close animation lands on its thumbnail (as Samsung Gallery does).
    val focusKey = ViewerFocus.key
    LaunchedEffect(focusKey) {
        val k = focusKey ?: return@LaunchedEffect
        // Wait for the first layout (the restored scroll position) before deciding anything.
        snapshotFlow { state.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        val idx = currentEntries.indexOfFirst { it.key == k }.takeIf { it >= 0 } ?: return@LaunchedEffect
        val target = idx + headerCount
        val info = state.layoutInfo
        val vis = info.visibleItemsInfo.firstOrNull { it.index == target }
        val fullyVisible = vis != null && vis.offset.y >= info.viewportStartOffset && vis.offset.y + vis.size.height <= info.viewportEndOffset
        if (!fullyVisible) {
            state.scrollToItem(target)
            state.scrollBy(-(info.viewportEndOffset - info.viewportStartOffset) / 3f)
        }
    }

    var autoScroll by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(autoScroll) {
        if (autoScroll != 0f) while (isActive) { state.scrollBy(autoScroll); delay(10) }
    }

    BoxWithConstraints(modifier) {
        val cellDp = maxWidth / lv.columns
        val density = LocalDensity.current
        val edge = with(density) { 80.dp.toPx() }
        val viewportH = with(density) { maxHeight.toPx() }

        fun indexAt(pos: Offset): Int? = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            info.size.toIntRect().contains(pos.round() - info.offset)
        }?.index?.minus(headerCount)

        LazyVerticalGrid(
            columns = GridCells.Fixed(lv.columns),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 1.5.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 1.5.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Pinch: watch two-finger gestures before the grid scrolls.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoom = 1f
                        var changed = false
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.count { it.pressed } >= 2) {
                                zoom *= ev.calculateZoom()
                                ev.changes.forEach { it.consume() }
                                if (!changed && (zoom > 1.25f || zoom < 0.8f)) {
                                    val next = (currentLevel + if (zoom > 1f) -1 else 1).coerceIn(GridLevels.indices)
                                    if (next != currentLevel) {
                                        val first = state.firstVisibleItemIndex - headerCount
                                        anchorKey = currentEntries.getOrNull(first.coerceAtLeast(0))
                                            ?.let { e -> if (e is GridEntry.Header) currentEntries.getOrNull(first + 1)?.key ?: e.key else e.key }
                                        onLevelChange(next)
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    }
                                    changed = true
                                }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    // Long-press to start selecting, then drag across photos to select a range.
                    var anchor = -1
                    var base: Set<String> = emptySet()
                    detectDragGesturesAfterLongPress(
                        onDragStart = { pos ->
                            val i = indexAt(pos) ?: return@detectDragGesturesAfterLongPress
                            val e = currentEntries.getOrNull(i) as? GridEntry.Cell ?: return@detectDragGesturesAfterLongPress
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (!selection.active) selection.start(e.key) else selection.toggle(e.key)
                            base = selection.keys
                            anchor = i
                        },
                        onDrag = { change, _ ->
                            if (anchor < 0) return@detectDragGesturesAfterLongPress
                            val y = change.position.y
                            autoScroll = when {
                                y < edge -> -12f
                                y > viewportH - edge -> 12f
                                else -> 0f
                            }
                            val i = indexAt(change.position) ?: return@detectDragGesturesAfterLongPress
                            val range = if (i >= anchor) anchor..i else i..anchor
                            val add = range.mapNotNull { (currentEntries.getOrNull(it) as? GridEntry.Cell)?.key }
                            selection.set(base + add)
                        },
                        onDragEnd = { anchor = -1; autoScroll = 0f },
                        onDragCancel = { anchor = -1; autoScroll = 0f },
                    )
                },
        ) {
            header?.invoke(this)
            for (e in entries) when (e) {
                is GridEntry.Header -> item(key = e.key, span = { GridItemSpan(maxLineSpan) }, contentType = "h") {
                    DateHeader(e, selection, compact = lv.grouping != Grouping.DAY)
                }
                is GridEntry.Cell -> item(key = e.item.key, contentType = "c") {
                    val m = e.item
                    val sel = selection.isSelected(m.key)
                    MediaThumb(
                        item = m,
                        sizeDp = cellDp,
                        selecting = selection.active,
                        selected = sel,
                        notBackedUp = notBackedUp(m),
                        compact = compact,
                        onExpand = { onOpen(m) },
                        sharedKey = m.key,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable {
                                if (selection.active) selection.toggle(m.key) else onOpen(m)
                            },
                    )
                }
            }
        }

        // Floating date bubble while scrolling.
        val scrolling by remember { derivedStateOf { state.isScrollInProgress } }
        val bubble by remember {
            derivedStateOf {
                val i = (state.firstVisibleItemIndex - headerCount).coerceAtLeast(0)
                var label: String? = null
                for (j in minOf(i, currentEntries.size - 1) downTo 0) {
                    val e = currentEntries[j]
                    if (e is GridEntry.Header) { label = e.label; break }
                }
                label
            }
        }
        AnimatedVisibility(
            visible = scrolling && bubble != null && state.firstVisibleItemIndex > headerCount + 1 && showHeaders,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = contentPadding.calculateTopPadding() + 6.dp),
        ) {
            Box(
                Modifier.shadow(6.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(G.colors.pill)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) { Text(bubble ?: "", fontSize = 13.5.sp, color = G.colors.text) }
        }

        // Scroll thumb on the right edge, draggable for fast scrolling.
        val total = entries.size + headerCount
        var dragging by remember { mutableStateOf(false) }
        val visible = (scrolling || dragging) && total > 60
        val topPad = contentPadding.calculateTopPadding()
        val bottomPad = contentPadding.calculateBottomPadding()
        val trackH = maxHeight - topPad - bottomPad
        val frac by remember { derivedStateOf { if (total <= 1) 0f else state.firstVisibleItemIndex.toFloat() / (total - 1) } }
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd).padding(top = topPad).height(trackH).width(28.dp)) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth().pointerInput(total) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        val f = (change.position.y / size.height).coerceIn(0f, 1f)
                        scope.launch { state.scrollToItem((f * (total - 1)).toInt()) }
                    }
                },
            ) {
                val thumbH = 44.dp
                val y = (trackH - thumbH) * frac
                Box(
                    Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y.roundToPx()) }.padding(end = 4.dp)
                        .size(width = if (dragging) 6.dp else 4.dp, height = thumbH).clip(RoundedCornerShape(3.dp))
                        .background(G.colors.textSecondary.copy(alpha = 0.7f)),
                )
            }
        }
    }
}

@Composable
private fun DateHeader(h: GridEntry.Header, selection: SelectionState, compact: Boolean) {
    val allSelected = selection.active && h.itemKeys.isNotEmpty() && h.itemKeys.all { selection.isSelected(it) }
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (selection.active) it.clickable { selection.set(if (allSelected) selection.keys - h.itemKeys.toSet() else selection.keys + h.itemKeys) } else it }
            .padding(start = 14.dp, end = 14.dp, top = if (compact) 18.dp else 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selection.active) {
            SelectMark(allSelected, dark = true)
            HGap(12)
        }
        Text(h.label, fontSize = if (compact) 13.sp else 14.sp, fontWeight = FontWeight.Bold, color = G.colors.text)
        if (h.sub != null) {
            HGap(10)
            Text(h.sub, fontSize = 13.5.sp, color = G.colors.textSecondary, maxLines = 1)
        }
    }
}

/** Fade at the top edge so content scrolling under the floating bars stays legible. */
@Composable
fun TopFade(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 110.dp) {
    Box(
        modifier.fillMaxWidth().height(height).background(
            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(G.colors.background, G.colors.background.copy(alpha = 0.85f), Color.Transparent)),
        ),
    )
}

@Composable
fun BottomFade(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 120.dp) {
    Box(
        modifier.fillMaxWidth().height(height).background(
            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, G.colors.background.copy(alpha = 0.8f), G.colors.background)),
        ),
    )
}
