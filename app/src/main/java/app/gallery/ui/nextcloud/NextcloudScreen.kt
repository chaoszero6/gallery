package app.gallery.ui.nextcloud

import android.net.Uri
import android.text.format.DateUtils
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.gallery.backup.BackupRepository
import app.gallery.data.db.BucketRow
import app.gallery.data.db.UploadRecord
import app.gallery.data.model.MediaSource
import app.gallery.data.nextcloud.LoginFlow
import app.gallery.data.settings.BackupSettings
import app.gallery.ui.GridArgs
import app.gallery.ui.LocalContainer
import app.gallery.ui.actions.NameDialog
import app.gallery.ui.components.OneUiTopBar
import app.gallery.ui.components.OneUiSwitch
import app.gallery.ui.components.CoverImage
import app.gallery.ui.components.RoundedSection
import app.gallery.ui.components.SectionLabel
import app.gallery.ui.components.TextPill
import app.gallery.ui.openGrid
import app.gallery.ui.settings.RowDivider
import app.gallery.ui.settings.SettingRow
import app.gallery.ui.theme.G
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private fun fmtBytes(b: Long) = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.0f MB".format(b / (1024.0 * 1024))
    else -> "${b / 1024} KB"
}

private fun ago(ms: Long) = if (ms <= 0) "Never" else DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

@Composable
fun NextcloudScreen(nav: NavController) {
    val c = LocalContainer.current
    val account by c.accounts.account.collectAsState()
    Box(Modifier.fillMaxSize().background(G.colors.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OneUiTopBar("Nextcloud backup & sync") { nav.popBackStack() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 40.dp)) {
            Text(
                "Your only cloud: photos are backed up to and shown from your own Nextcloud server with Memories.",
                fontSize = 14.sp, color = G.colors.textSecondary, modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 4.dp, bottom = 12.dp),
            )
            if (account == null) SignIn() else Connected(nav)
        }
        }
    }
}

@Composable
private fun SignIn() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    RoundedSection {
        Column(Modifier.padding(20.dp)) {
            Text("Connect your server", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                server, { server = it }, label = { Text("Server address") }, placeholder = { Text("cloud.example.com") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
            )
            if (manual) {
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("App password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text("Create one in Nextcloud: Settings › Security › Devices & sessions.", fontSize = 11.sp, color = G.colors.textSecondary, modifier = Modifier.padding(top = 6.dp))
            }
            error?.let { Text(it, color = G.colors.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
            waiting?.let {
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  $it", fontSize = 13.sp, color = G.colors.text)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (waiting != null) TextButton({ job?.cancel(); waiting = null }) { Text("Cancel") }
                else TextButton({ manual = !manual }) { Text(if (manual) "Use browser sign-in" else "Use app password") }
                Spacer(Modifier.size(8.dp))
                TextPill(if (manual) "Sign in" else "Sign in with browser", dark = true) {
                    if (server.isBlank() || waiting != null) return@TextPill
                    error = null
                    job = scope.launch {
                        try {
                            if (manual) {
                                waiting = "Checking…"
                                c.nextcloud.loginWithAppPassword(server, user, pass)
                            } else {
                                waiting = "Opening your server…"
                                val started = c.nextcloud.startLogin(server)
                                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(started.loginUrl))
                                waiting = "Approve access in your browser…"
                                c.nextcloud.completeLogin(started)
                            }
                            c.media.requestRemoteSync() // a big library loads in the background
                            c.backup.ensureDefaultBuckets()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: e.javaClass.simpleName
                        } finally {
                            waiting = null
                        }
                    }
                }
            }
        }
    }
    Text(
        "Requires the Memories app (version ${LoginFlow.MIN_MEMORIES_MAJOR} or newer) on your Nextcloud.",
        fontSize = 11.sp, color = G.colors.textSecondary, modifier = Modifier.padding(start = 30.dp, end = 30.dp, top = 10.dp),
    )
}

@Composable
private fun Connected(nav: NavController) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val account by c.accounts.account.collectAsState()
    val s by c.settings.backup.collectAsState(BackupSettings())
    val progress by c.backup.progress.collectAsState()
    val sync by c.backup.sync.collectAsState()
    val records by c.backup.records.collectAsState(emptyList())
    val syncing by c.media.syncing.collectAsState()
    val lastErr by c.media.lastError.collectAsState()
    var confirmDisconnect by remember { mutableStateOf(false) }
    var editDest by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var showSyncFolders by remember { mutableStateOf(false) }
    val buckets by produceState(emptyList<BucketRow>(), showFolders, showSyncFolders) { value = c.db.local().buckets() }
    val counts by c.db.local().observeBuckets().collectAsState(emptyList())

    fun update(block: suspend () -> Unit) = scope.launch { block(); c.backup.applySchedule() }

    val a = account ?: return
    RoundedSection {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
            Text(a.uid, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text)
            Text(a.server.removePrefix("https://"), fontSize = 13.sp, color = G.colors.textSecondary)
            Text("Memories ${a.memoriesVersion}", fontSize = 11.sp, color = G.colors.textSecondary)
            if (lastErr != null) {
                Text("Can't reach the server right now ($lastErr)", fontSize = 11.sp, color = G.colors.danger)
                Text(
                    "Photos on this phone and Nextcloud photos already loaded still work. Changes are saved and sent when the server is back.",
                    fontSize = 11.sp, color = G.colors.textSecondary,
                )
            }
        }
        RowDivider()
        SettingRow("Refresh library", if (syncing) "Refreshing…" else "Reload the Memories timeline") { scope.launch { c.media.syncRemote(force = true); c.nextcloud.refreshClusters() } }
        RowDivider()
        SettingRow("Disconnect", "Removes the account from this phone. Nothing is deleted on the server.") { confirmDisconnect = true }
    }

    // Live upload status.
    val queued = records.count { it.status == UploadRecord.QUEUED || it.status == UploadRecord.UPLOADING }
    val failed = records.count { it.status == UploadRecord.FAILED }
    val done = records.count { it.status == UploadRecord.DONE }
    SectionLabel("Status")
    RoundedSection {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
            if (progress.running) {
                Text("Uploading ${progress.done + 1} of ${progress.total}", fontSize = 13.5.sp, color = G.colors.text)
                progress.current?.let { Text(it, fontSize = 11.sp, color = G.colors.textSecondary) }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator({ if (progress.bytesTotal > 0) progress.bytesSent.toFloat() / progress.bytesTotal else 0f }, Modifier.fillMaxWidth())
                Text("${fmtBytes(progress.bytesSent)} of ${fmtBytes(progress.bytesTotal)}", fontSize = 11.sp, color = G.colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
            } else {
                Text(if (queued > 0) "$queued waiting to upload" else "Up to date", fontSize = 13.5.sp, color = G.colors.text)
                progress.error?.let { Text(it, fontSize = 12.sp, color = G.colors.danger) }
            }
            Text("Last auto-upload: ${ago(s.lastAutoUploadMs)}", fontSize = 11.sp, color = G.colors.textSecondary, modifier = Modifier.padding(top = 6.dp))
            Text("Last sync: ${ago(s.lastSyncMs)}${if (s.lastSyncSummary.isNotBlank()) " · ${s.lastSyncSummary}" else ""}", fontSize = 11.sp, color = G.colors.textSecondary)
            Text("$done backed up by this app", fontSize = 11.sp, color = G.colors.textSecondary)
        }
        if (queued > 0 && !progress.running) { RowDivider(); SettingRow("Upload now", "Waiting items start when the network and charging rules allow") { scope.launch { c.backup.startUploads() } } }
        if (failed > 0) { RowDivider(); SettingRow("Retry $failed failed", records.firstOrNull { it.status == UploadRecord.FAILED }?.error) { scope.launch { c.backup.retryFailed() } } }
    }

    // Sync: compare the phone with the server and upload what's missing.
    SectionLabel("Sync")
    RoundedSection {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
            Text("Check this phone against Nextcloud", fontSize = 14.5.sp, color = G.colors.text)
            Text(
                "Compares the phone folders you choose with their folders on your server, then uploads the pictures that are missing. Nothing is deleted or overwritten.",
                fontSize = 12.sp, color = G.colors.textSecondary,
            )
        }
        val syncChosen = s.syncBuckets ?: s.buckets
        val syncCounts = counts.filter { it.bucketId in syncChosen }
        fun toggleSync(id: Long) = update { c.settings.setSyncBuckets(if (id in syncChosen) syncChosen - id else syncChosen + id) }
        SettingRow(
            "Folders to check",
            if (syncCounts.isEmpty()) "None selected" else syncCounts.joinToString(", ") { it.bucketName.ifBlank { "Internal storage" } } + " · ${syncCounts.sumOf { it.count }} items",
        ) { showSyncFolders = !showSyncFolders }
        if (showSyncFolders) buckets.forEach { b ->
            val on = b.bucketId in syncChosen
            Row(
                Modifier.fillMaxWidth().clickable { toggleSync(b.bucketId) }.padding(start = 38.dp, end = 22.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(b.bucketName.ifBlank { "Internal storage" }, fontSize = 13.5.sp, color = G.colors.text, modifier = Modifier.weight(1f))
                Text("${counts.firstOrNull { it.bucketId == b.bucketId }?.count ?: 0}  ", fontSize = 12.sp, color = G.colors.textSecondary)
                Checkbox(on, { toggleSync(b.bucketId) })
            }
        }
        RowDivider()
        SettingRow(
            "Also check the rest of Nextcloud",
            "Finds pictures already uploaded to other folders (for example by the Nextcloud app). Slower with a big library",
            checked = s.syncWholeLibrary,
        ) { update { c.settings.setSyncWholeLibrary(!s.syncWholeLibrary) } }
        Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
            when (val st = sync) {
                is BackupRepository.SyncState.Comparing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("  ${st.step}…", color = G.colors.text)
                }
                is BackupRepository.SyncState.Failed -> Text("Couldn't compare: ${st.message}", color = G.colors.danger, fontSize = 12.sp)
                is BackupRepository.SyncState.Ready -> {
                    val p = st.plan
                    Text("${p.present.size} on Nextcloud · ${p.missing.size} missing" + if (p.missing.isNotEmpty()) " (${fmtBytes(p.missingBytes)})" else "", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text)
                    if (p.missing.isEmpty()) Text("Everything in the chosen folders is on Nextcloud.", fontSize = 12.sp, color = G.colors.textSecondary)
                    else {
                        val missingItems = p.missing.mapNotNull { m -> c.media.item("L:${m.local.id}") }
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(missingItems.take(30), key = { it.key }) { m ->
                                CoverImage(c.images.thumb(m), Modifier.size(56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)), radius = 8.dp)
                            }
                        }
                        Text(
                            "See all missing", color = G.colors.accent, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp).clickable { nav.openGrid(GridArgs("Missing on Nextcloud", MediaSource.Items("missing", missingItems.map { it.key }))) },
                        )
                        Text("Uploads go to ${s.destRoot}" + if (s.perBucketFolder) "/<album>" else "", fontSize = 11.sp, color = G.colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                BackupRepository.SyncState.Idle -> {}
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val ready = (sync as? BackupRepository.SyncState.Ready)?.plan
                if (ready != null && ready.missing.isNotEmpty()) {
                    TextPill("Upload ${ready.missing.size}", dark = true) { scope.launch { c.backup.uploadMissing(ready) } }
                    Spacer(Modifier.size(10.dp))
                }
                TextPill(if (ready != null) "Check again" else "Sync now", dark = ready == null) {
                    if (sync !is BackupRepository.SyncState.Comparing && syncChosen.isNotEmpty()) scope.launch { c.backup.compare() }
                }
            }
        }
        RowDivider()
        SettingRow("Sync every day", "On Wi-Fi with enough battery, uploads anything missing", checked = s.dailySync) { update { c.settings.setDailySync(!s.dailySync) } }
    }

    // Backup settings.
    SectionLabel("Backup")
    RoundedSection {
        SettingRow("Auto-upload new pictures and videos", if (s.autoUpload) "On: new items in your backup folders are uploaded" else "Off", checked = s.autoUpload) {
            update { c.settings.setAutoUpload(!s.autoUpload); if (!s.autoUpload) c.backup.queueNewItems() }
        }
        RowDivider()
        val chosen = counts.filter { it.bucketId in s.buckets }
        SettingRow("Folders to back up", if (chosen.isEmpty()) "None selected" else chosen.joinToString(", ") { it.bucketName } + " · ${chosen.sumOf { it.count }} items") { showFolders = !showFolders }
        if (showFolders) buckets.forEach { b ->
            val on = b.bucketId in s.buckets
            Row(
                Modifier.fillMaxWidth().clickable { update { c.settings.setBuckets(if (on) s.buckets - b.bucketId else s.buckets + b.bucketId) } }
                    .padding(start = 38.dp, end = 22.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(b.bucketName.ifBlank { "Internal storage" }, fontSize = 13.5.sp, color = G.colors.text, modifier = Modifier.weight(1f))
                Text("${counts.firstOrNull { it.bucketId == b.bucketId }?.count ?: 0}  ", fontSize = 12.sp, color = G.colors.textSecondary)
                OneUiSwitch(on, { update { c.settings.setBuckets(if (on) s.buckets - b.bucketId else s.buckets + b.bucketId) } })
            }
        }
        RowDivider()
        SettingRow("Upload to", s.destRoot) { editDest = true }
        RowDivider()
        SettingRow("Keep album folders", "e.g. ${s.destRoot}/Camera", checked = s.perBucketFolder) { update { c.settings.setPerBucketFolder(!s.perBucketFolder) } }
        RowDivider()
        SettingRow("Sort by year and month", "e.g. …/2026/11", checked = s.yearMonthFolders) { update { c.settings.setYearMonthFolders(!s.yearMonthFolders) } }
        RowDivider()
        SettingRow("Include videos", checked = s.includeVideos) { update { c.settings.setIncludeVideos(!s.includeVideos) } }
        RowDivider()
        SettingRow("Upload on Wi-Fi only", checked = s.wifiOnly) { update { c.settings.setWifiOnly(!s.wifiOnly) } }
        if (!s.wifiOnly) { RowDivider(); SettingRow("Allow while roaming", checked = s.allowRoaming) { update { c.settings.setAllowRoaming(!s.allowRoaming) } } }
        RowDivider()
        SettingRow("Only while charging", checked = s.chargingOnly) { update { c.settings.setChargingOnly(!s.chargingOnly) } }
    }

    if (editDest) NameDialog("Upload to", "Folder on Nextcloud", s.destRoot, onCancel = { editDest = false }) {
        editDest = false; update { c.settings.setDestRoot(it) }
    }
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text("Disconnect Nextcloud?") },
        text = { Text("Backup stops and Nextcloud pictures disappear from this phone. Nothing is deleted on your server.") },
        confirmButton = { TextButton({ confirmDisconnect = false; scope.launch { c.backup.cancelUploads(); c.nextcloud.signOut(); c.backup.applySchedule() } }) { Text("Disconnect", color = G.colors.danger) } },
        dismissButton = { TextButton({ confirmDisconnect = false }) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { c.nextcloud.refreshConfig() }
}
