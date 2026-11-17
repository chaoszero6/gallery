package app.gallery.ui.search

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.gallery.data.model.MediaSource
import app.gallery.search.ClipModel
import app.gallery.search.SearchIndexWorker
import app.gallery.ui.components.BackgroundAccessDialog
import app.gallery.ui.components.rememberBackgroundAllowed
import app.gallery.search.SearchResults
import app.gallery.ui.GridArgs
import app.gallery.ui.LocalContainer
import app.gallery.ui.collections.ClusterRow
import app.gallery.ui.collections.personArgs
import app.gallery.ui.components.CircleBackButton
import app.gallery.ui.components.CoverImage
import app.gallery.ui.components.EmptyState
import app.gallery.ui.components.MediaGrid
import app.gallery.ui.components.SectionHeader
import app.gallery.ui.components.SelectionState
import app.gallery.ui.components.TopFade
import app.gallery.ui.openGrid
import app.gallery.ui.openViewer
import app.gallery.ui.theme.G
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(nav: NavController) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var busy by remember { mutableStateOf(false) }
    val recent by c.search.recent.collectAsState(emptyList())
    val suggestions by produceState(emptyList<String>()) { value = c.search.suggestions() }
    val indexed by c.db.search().observeCount(ClipModel.VERSION).collectAsState(0)
    val total by c.media.timeline.collectAsState()
    val status by c.searchIndexer.status.collectAsState()
    val backgroundAllowed = rememberBackgroundAllowed()
    var askBackground by remember { mutableStateOf(false) }
    fun startIndexing() = SearchIndexWorker.schedule(c.context, whileCharging = false, userRequested = true)
    if (askBackground) BackgroundAccessDialog(onDismiss = { askBackground = false }, onContinue = { askBackground = false; startIndexing() })
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LaunchedEffect(Unit) {
        // Load the text encoder while the user types, so the first search is quick.
        if (c.searchIndexer.clip.available) scope.launch(kotlinx.coroutines.Dispatchers.Default) { runCatching { c.searchIndexer.clip.embedText("photo") } }
        focus.requestFocus()
        // Make sure new photos get indexed (runs in the background, respecting the charging setting).
        SearchIndexWorker.schedule(c.context, c.settings.uiNow().searchIndexWhileCharging)
    }
    LaunchedEffect(submitted) {
        val q = submitted ?: return@LaunchedEffect
        busy = true
        results = runCatching { c.search.search(q) }.getOrNull()
        busy = false
        results?.let { r -> c.search.remember(q, r.items.firstOrNull()?.key) }
    }
    fun submit(q: String) { query = q; submitted = q.trim().takeIf { it.isNotEmpty() }; kb?.hide() }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { submit(it) }
    }

    Box(Modifier.fillMaxSize().background(G.colors.background)) {
        val r = results
        if (submitted != null && r != null) {
            ResultsView(nav, r, top)
        } else {
            LazyColumn(contentPadding = PaddingValues(top = top + 76.dp, bottom = 140.dp)) {
                if (suggestions.isNotEmpty()) item {
                    Text("You might be looking for", fontSize = 20.5.sp, color = G.colors.text, modifier = Modifier.padding(start = 22.dp, bottom = 16.dp))
                    FlowRow(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        suggestions.forEach { s ->
                            Text(
                                s, fontSize = 14.5.sp, color = G.colors.text,
                                modifier = Modifier.shadow(3.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(G.colors.surface)
                                    .clickable { submit(s) }.padding(horizontal = 18.dp, vertical = 11.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                if (recent.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent searches", fontSize = 13.5.sp, color = G.colors.textSecondary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("Clear all", color = G.colors.accent, fontSize = 13.5.sp, modifier = Modifier.clickable { scope.launch { c.search.clearRecent() } })
                        }
                    }
                    item {
                        Column(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(G.cardRadius)).background(G.colors.surface).padding(vertical = 6.dp)) {
                            recent.forEach { rs ->
                                Row(Modifier.fillMaxWidth().clickable { submit(rs.query) }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val thumb = rs.thumbKey?.let { c.media.item(it) }?.let { c.images.thumb(it) }
                                    CoverImage(thumb, Modifier.size(48.dp), radius = 24.dp)
                                    Text(rs.query, fontSize = 14.5.sp, color = G.colors.text, modifier = Modifier.weight(1f).padding(start = 16.dp))
                                    Icon(Icons.Rounded.Close, "Delete", Modifier.clickable { scope.launch { c.search.forget(rs.query) } }.padding(8.dp).size(18.dp), tint = G.colors.textSecondary)
                                }
                            }
                        }
                    }
                }
                item {
                    // Smart-search index status.
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 24.dp)) {
                        val n = total.size
                        if (!c.searchIndexer.clip.available) {
                            Text("Sentence search model not installed in this build; searching names, dates, labels and Nextcloud.", fontSize = 11.sp, color = G.colors.textSecondary)
                        } else if (n > 0 && indexed < n) {
                            Text("Smart search has looked at $indexed of $n items" + if (status.running) " — indexing…" else "", fontSize = 11.sp, color = G.colors.textSecondary)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator({ indexed.toFloat() / n }, Modifier.fillMaxWidth())
                            if (!status.running) Text(
                                "Index now", color = G.colors.accent, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp).clickable { if (backgroundAllowed) startIndexing() else askBackground = true },
                            )
                        }
                    }
                }
            }
        }

        TopFade(Modifier.align(Alignment.TopCenter), height = 90.dp)
        Row(Modifier.statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleBackButton { if (submitted != null) { submitted = null; results = null } else nav.popBackStack() }
            if (submitted != null) Text(
                if (busy) "Searching…" else "Search results", fontSize = 18.5.sp, color = G.colors.text, modifier = Modifier.padding(start = 16.dp),
            )
        }

        // Floating rounded search field near the thumb (One UI style), with a soft gradient edge.
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 12.dp)
                .shadow(10.dp, RoundedCornerShape(32.dp))
                .clip(RoundedCornerShape(32.dp))
                .background(G.colors.surface)
                .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFF8E7BFF), Color(0xFF6FC3FF), Color(0xFF7FE0C9))), RoundedCornerShape(32.dp))
                .fillMaxWidth().height(60.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Search, null, tint = G.colors.textSecondary)
            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                if (query.isEmpty()) Text("What are you looking for?", fontSize = 14.5.sp, color = Color(0xFF7867E8))
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = TextStyle(fontFamily = app.gallery.ui.theme.AppFont, fontSize = 14.5.sp, color = G.colors.text),
                    cursorBrush = SolidColor(G.colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear", Modifier.clip(CircleShape).clickable { query = ""; submitted = null; results = null }.padding(6.dp), tint = G.colors.textSecondary)
            else Icon(Icons.Rounded.Mic, "Voice search", Modifier.clip(CircleShape).clickable {
                runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }
            }.padding(6.dp), tint = G.colors.text)
        }
    }
}

@Composable
private fun ResultsView(nav: NavController, r: SearchResults, top: androidx.compose.ui.unit.Dp) {
    val selection = remember { SelectionState() }
    var level by remember { mutableIntStateOf(2) }
    val state = rememberLazyGridState()
    if (r.items.isEmpty() && r.people.isEmpty() && r.places.isEmpty() && r.tags.isEmpty() && r.albums.isEmpty()) {
        Box(Modifier.fillMaxSize()) { EmptyState("No results", "Try another word, a place, a person or a date like \"2024\".", Modifier.align(Alignment.Center)) }
        return
    }
    MediaGrid(
        items = r.items, state = state, selection = selection, level = level, onLevelChange = { level = it },
        onOpen = { nav.openViewer(r.items, it, MediaSource.Items(r.query, r.items.map { m -> m.key })) },
        contentPadding = PaddingValues(top = top + 70.dp, bottom = 120.dp),
        showHeaders = false,
        header = {
            if (r.people.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column { SectionHeader("People"); ClusterRow(r.people) { nav.openGrid(personArgs(it)) } }
            }
            if (r.places.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column { SectionHeader("Locations"); ClusterRow(r.places) { nav.openGrid(GridArgs(it.name, MediaSource.Place(it.clusterId.toLongOrNull() ?: 0, it.name))) } }
            }
            if (r.tags.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column { SectionHeader("Tags"); ClusterRow(r.tags, label = { "#${it.name}" }) { nav.openGrid(GridArgs("#${it.name}", MediaSource.Tag(it.name))) } }
            }
            if (r.albums.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column { SectionHeader("Albums"); ClusterRow(r.albums) { nav.openGrid(GridArgs(it.name, MediaSource.RemoteAlbum(it.user ?: "", it.name))) } }
            }
            if (r.items.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "${r.items.size} pictures and videos" + if (r.usedSmartSearch) "" else "",
                    fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 10.dp),
                )
            }
        },
    )
}
