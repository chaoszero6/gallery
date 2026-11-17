package app.gallery.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.gallery.BuildConfig
import app.gallery.data.settings.UiSettings
import app.gallery.search.ClipModel
import app.gallery.search.SearchIndexWorker
import app.gallery.ui.LocalContainer
import app.gallery.ui.components.BackgroundAccess
import app.gallery.ui.components.BackgroundAccessDialog
import app.gallery.ui.components.rememberBackgroundAllowed
import app.gallery.ui.Routes
import app.gallery.ui.components.CircleBackButton
import app.gallery.ui.components.RoundedSection
import app.gallery.ui.components.SectionLabel
import app.gallery.ui.theme.G
import kotlinx.coroutines.launch

@Composable
fun SettingRow(title: String, subtitle: String? = null, badge: String? = null, checked: Boolean? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 17.sp, color = if (enabled) G.colors.text else G.colors.textSecondary)
                if (badge != null) Text(
                    badge, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp).background(Color(0xFFFF6A3D), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = if (checked == true) G.colors.accent else G.colors.textSecondary)
        }
        if (checked != null) Switch(
            checked, { onClick() }, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = G.colors.accent),
        )
    }
}

@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(horizontal = 22.dp), color = G.colors.divider)

@Composable
fun SettingsScreen(nav: NavController) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by c.settings.ui.collectAsState(UiSettings())
    val account by c.accounts.account.collectAsState()
    val indexed by c.db.search().observeCount(ClipModel.VERSION).collectAsState(0)
    val total by c.media.timeline.collectAsState()
    val status by c.searchIndexer.status.collectAsState()
    val backgroundAllowed = rememberBackgroundAllowed()
    var askBackground by remember { mutableStateOf(false) }
    fun startIndexing() = SearchIndexWorker.schedule(context, whileCharging = false, userRequested = true)
    if (askBackground) BackgroundAccessDialog(onDismiss = { askBackground = false }, onContinue = { askBackground = false; startIndexing() })

    Box(Modifier.fillMaxSize().background(G.colors.background)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(top = 70.dp, bottom = 40.dp)) {
            Text("Gallery settings", fontSize = 30.sp, color = G.colors.text, modifier = Modifier.padding(start = 26.dp, bottom = 16.dp))
            RoundedSection {
                SettingRow(
                    "Nextcloud backup & sync",
                    if (account != null) "Connected to ${account!!.server.removePrefix("https://")}" else "Not connected",
                    badge = if (account == null) "New" else null,
                ) { nav.navigate(Routes.NEXTCLOUD) }
            }
            SectionLabel("Viewing")
            RoundedSection {
                SettingRow("Auto play videos", "Start videos when you open them", checked = ui.videoAutoplay) { scope.launch { c.settings.setVideoAutoplay(!ui.videoAutoplay) } }
                RowDivider()
                SettingRow("Show backup badges", "Mark photos that are only on this phone or only on Nextcloud", checked = ui.showBackupBadges) {
                    scope.launch { c.settings.setShowBadges(!ui.showBackupBadges) }
                }
            }
            SectionLabel("Smart search")
            RoundedSection {
                SettingRow(
                    "Search index",
                    when {
                        !c.searchIndexer.clip.available -> "On-device model missing from this build; labels and text search still work"
                        status.running -> "Indexing ${status.done} of ${status.total}… (${status.msPerItem} ms per item)"
                        else -> "$indexed of ${total.size} items indexed on this phone"
                    },
                ) {
                    when {
                        status.running -> {}
                        !backgroundAllowed -> askBackground = true
                        else -> startIndexing()
                    }
                }
                RowDivider()
                SettingRow("Only index while charging", "Building the index uses the processor for a while", checked = ui.searchIndexWhileCharging) {
                    scope.launch { c.settings.setSearchIndexWhileCharging(!ui.searchIndexWhileCharging) }
                }
                RowDivider()
                SettingRow("Include Nextcloud photos", "Downloads a small preview of each one to index it on this phone", checked = ui.searchIndexRemote) {
                    scope.launch { c.settings.setSearchIndexRemote(!ui.searchIndexRemote) }
                }
                if (c.searchIndexer.clip.available) {
                    RowDivider()
                    ModelSpeedRow(total)
                }
            }
            SectionLabel("Permissions")
            RoundedSection {
                SettingRow(
                    "Background activity",
                    if (backgroundAllowed) "Allowed: backup and search indexing keep running when Gallery is closed"
                    else "Battery optimisation pauses backup and search indexing when Gallery is closed. Tap to allow",
                ) { BackgroundAccess.request(context) }
                RowDivider()
                if (Build.VERSION.SDK_INT >= 31) {
                    SettingRow(
                        "Media management",
                        if (c.mediaStore.canManageMedia()) "Allowed: delete and move without asking each time" else "Allow to delete and move pictures without a confirmation each time",
                    ) {
                        context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}")))
                    }
                    RowDivider()
                }
                SettingRow("App permissions") {
                    context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }
            }
            SectionLabel("About")
            RoundedSection {
                SettingRow("Gallery ${BuildConfig.VERSION_NAME}", "Maps © OpenStreetMap contributors. Smart search runs on this phone.") {}
            }
            Spacer(Modifier.height(20.dp))
        }
        CircleBackButton(Modifier.statusBarsPadding().padding(12.dp)) { nav.popBackStack() }
    }
}

/** Shows where the search model runs (NPU or CPU) and measures it on one of the user's photos. */
@Composable
private fun ModelSpeedRow(items: List<app.gallery.data.model.MediaItem>) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = c.searchIndexer.clip
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val where = if (clip.npuSupported) "Runs on the phone's NPU" else "Runs on the processor (CPU)"
    SettingRow("Search model speed", if (busy) "Measuring…" else result ?: "$where. Tap to measure") {
        if (busy) return@SettingRow
        val uri = items.firstOrNull { it.localUri != null && !it.isVideo }?.localUri
        if (uri == null || Build.VERSION.SDK_INT < 29) { result = "No photo on this phone to measure with"; return@SettingRow }
        busy = true
        scope.launch {
            result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    val bmp = context.contentResolver.loadThumbnail(uri, android.util.Size(512, 512), null)
                    val b = try { clip.benchmark(bmp) } finally { bmp.recycle() }
                    if (b.npuMs != null) {
                        String.format(java.util.Locale.ROOT, "NPU %.0f ms · CPU %.0f ms per photo (%.1f× faster, match %.3f)", b.npuMs, b.cpuMs, b.cpuMs / b.npuMs, b.cosine ?: 0f)
                    } else {
                        String.format(java.util.Locale.ROOT, "CPU %.0f ms per photo", b.cpuMs) + (b.npuError?.let { " · NPU unavailable: $it" } ?: "")
                    }
                }.getOrElse { "Could not measure: ${it.message}" }
            }
            busy = false
        }
    }
}
