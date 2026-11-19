package app.gallery.ui.actions

import android.app.Activity
import android.app.WallpaperManager
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.gallery.AppContainer
import app.gallery.backup.BackupPlanner
import app.gallery.data.db.UploadRecord
import app.gallery.data.model.MediaItem
import app.gallery.data.nextcloud.Cluster
import app.gallery.ui.LocalContainer
import app.gallery.ui.map.LocationPickerDialog
import app.gallery.ui.theme.G
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Which dialog is open. */
internal sealed interface Dialog {
    data class Delete(val items: List<MediaItem>) : Dialog
    data class Album(val items: List<MediaItem>) : Dialog
    data class NewAlbum(val items: List<MediaItem>) : Dialog
    data class MoveFolder(val items: List<MediaItem>) : Dialog
    data class More(val items: List<MediaItem>) : Dialog
    data class Date(val items: List<MediaItem>) : Dialog
    data class Time(val items: List<MediaItem>, val date: java.time.LocalDate) : Dialog
    data class Location(val items: List<MediaItem>) : Dialog
    data class Tag(val items: List<MediaItem>) : Dialog
    data class Busy(val text: String) : Dialog
    data class Info(val title: String, val text: String, val action: String? = null, val onAction: (() -> Unit)? = null) : Dialog
}

enum class DeleteScope { BOTH, PHONE, CLOUD }

/** Actions on photos, with all their confirmation dialogs. Create with [rememberMediaActions]. */
@Stable
class MediaActions internal constructor(
    private val c: AppContainer,
    private val context: Context,
    private val scope: CoroutineScope,
    private val onChanged: (removed: Set<String>) -> Unit,
) {
    internal var dialog by mutableStateOf<Dialog?>(null)
    internal var afterIntent: (suspend (Boolean) -> Unit)? = null
    internal lateinit var launchSender: (IntentSenderRequest) -> Unit

    private fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()
    private val signedIn get() = c.accounts.account.value != null

    fun share(items: List<MediaItem>) = scope.launch {
        if (items.isEmpty()) return@launch
        dialog = Dialog.Busy("Preparing…")
        val uris = runCatching { items.map { shareUri(it) } }.getOrElse { dialog = null; toast("Could not prepare: ${it.message}"); return@launch }
        dialog = null
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
            type = items[0].mime.ifBlank { "image/*" }
            putExtra(Intent.EXTRA_STREAM, uris[0])
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (items.all { it.isVideo }) "video/*" else if (items.none { it.isVideo }) "image/*" else "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null))
    }

    /** Phone copy if there is one, else download the original to the cache and share that. */
    private suspend fun shareUri(m: MediaItem): Uri = m.localUri ?: withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, m.name.ifBlank { "${m.remoteFileId}" })
        if (!f.exists() || f.length() != m.size) {
            c.http.newCall(Request.Builder().url(c.api.streamUrl(m.remoteFileId!!)).build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                f.outputStream().use { r.body.byteStream().copyTo(it) }
            }
        }
        FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }

    fun delete(items: List<MediaItem>) { if (items.isNotEmpty()) dialog = Dialog.Delete(items) }

    internal fun doDelete(items: List<MediaItem>, scopeSel: DeleteScope) = scope.launch {
        dialog = null
        val phone = if (scopeSel == DeleteScope.CLOUD) emptyList() else items.filter { it.isLocal }
        val cloud = if (scopeSel == DeleteScope.PHONE) emptyList() else items.filter { it.isRemote }
        suspend fun cloudPart() {
            if (cloud.isNotEmpty()) runCatching { c.actions.deleteRemote(cloud) }.onFailure { toast("Nextcloud: ${it.message}") }
        }
        if (phone.isEmpty()) {
            cloudPart(); onChanged(items.map { it.key }.toSet()); return@launch
        }
        val uris = phone.mapNotNull { it.localUri }
        val req = c.mediaStore.trashRequest(uris, true)
        if (req != null) {
            afterIntent = { ok ->
                if (ok) {
                    c.media.refreshLocal(); cloudPart()
                    onChanged(items.map { it.key }.toSet())
                }
            }
            launchSender(IntentSenderRequest.Builder(req.intentSender).build())
        } else {
            c.mediaStore.deleteDirect(uris)
            c.media.refreshLocal(); cloudPart()
            onChanged(items.map { it.key }.toSet())
        }
    }

    fun setFavorite(items: List<MediaItem>, fav: Boolean) = scope.launch {
        runCatching { c.actions.setFavorite(items, fav) }.onFailure { toast("Could not update favourite: ${it.message}") }
        onChanged(emptySet())
    }

    fun addToAlbum(items: List<MediaItem>) {
        if (!signedIn) { dialog = Dialog.MoveFolder(items); return }
        dialog = Dialog.Album(items)
    }

    internal fun doAddToAlbum(items: List<MediaItem>, album: String) = scope.launch {
        dialog = null
        val remote = items.filter { it.isRemote }
        val localOnly = items.filter { it.isLocalOnly }
        runCatching { c.actions.addToRemoteAlbum(remote, album) }.onFailure { toast("Could not add: ${it.message}") }
        if (localOnly.isNotEmpty()) {
            dialog = Dialog.Info(
                "Not on Nextcloud yet",
                "${localOnly.size} item(s) are only on this phone. Back them up to Nextcloud first, then add them to \"$album\".",
                "Back up now",
            ) { backUp(localOnly) }
        } else toast("Added to $album")
        c.nextcloud.clusters("albums", refresh = true)
    }

    internal fun doCreateAlbum(items: List<MediaItem>, name: String) = scope.launch {
        dialog = Dialog.Busy("Creating album…")
        runCatching { c.nextcloud.createAlbum(name) }
            .onSuccess { if (items.isNotEmpty()) doAddToAlbum(items, name) else { dialog = null; toast("Album created") } }
            .onFailure { dialog = null; toast("Could not create album: ${it.message}") }
    }

    fun createAlbum() { dialog = Dialog.NewAlbum(emptyList()) }

    /** Moves phone items to another folder (Android "album"). Needs the system write consent. */
    internal fun doMoveToFolder(items: List<MediaItem>, relativePath: String) = scope.launch {
        dialog = null
        val uris = items.filter { it.isLocal }.mapNotNull { it.localUri }
        if (uris.isEmpty() || Build.VERSION.SDK_INT < 30) { toast("Moving needs Android 11 or newer"); return@launch }
        val apply: suspend () -> Unit = {
            withContext(Dispatchers.IO) {
                uris.forEach { u ->
                    runCatching {
                        context.contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }, null, null)
                    }
                }
            }
            c.media.refreshLocal()
            toast("Moved to ${relativePath.trimEnd('/').substringAfterLast('/')}")
            onChanged(emptySet())
        }
        c.mediaStore.writeRequest(uris)?.let { pi ->
            afterIntent = { ok -> if (ok) apply() }
            launchSender(IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }

    fun more(items: List<MediaItem>) { if (items.isNotEmpty()) dialog = Dialog.More(items) }
    fun editDate(items: List<MediaItem>) { dialog = Dialog.Date(items) }
    fun editLocation(items: List<MediaItem>) { dialog = Dialog.Location(items) }

    /** Phone edits need write consent first; server edits go straight through. */
    private fun withWriteAccess(items: List<MediaItem>, block: suspend () -> Unit) = scope.launch {
        val uris = items.filter { it.isLocal && !it.isVideo }.mapNotNull { it.localUri }
        val pi = c.mediaStore.writeRequest(uris)
        if (pi == null) { block(); return@launch }
        afterIntent = { block() } // even if refused, server copies can still be edited
        launchSender(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    internal fun doSetDate(items: List<MediaItem>, dt: LocalDateTime) = withWriteAccess(items) {
        dialog = Dialog.Busy("Saving…")
        runCatching { c.actions.setDateTime(items, dt) }.onFailure { toast("Could not change date: ${it.message}") }
        dialog = null
        onChanged(emptySet())
    }

    internal fun doSetLocation(items: List<MediaItem>, lat: Double, lon: Double) = withWriteAccess(items) {
        dialog = Dialog.Busy("Saving…")
        runCatching { c.actions.setLocation(items, lat, lon) }.onFailure { toast("Could not change location: ${it.message}") }
        dialog = null
        onChanged(emptySet())
    }

    fun archive(items: List<MediaItem>, archive: Boolean = true) = scope.launch {
        val remote = items.filter { it.isRemote }
        if (remote.isEmpty()) { toast("Archive is for photos on Nextcloud"); return@launch }
        runCatching { c.actions.archive(remote, archive) }
            .onSuccess { toast(if (archive) "Moved to Archive" else "Removed from Archive"); onChanged(remote.map { it.key }.toSet()) }
            .onFailure { toast("Could not archive: ${it.message}") }
    }

    /** Uploads phone-only items now (same engine as backup). */
    fun backUp(items: List<MediaItem>) = scope.launch {
        dialog = null
        val s = c.settings.backupNow()
        val cfg = BackupPlanner.Config(s.destRoot, s.perBucketFolder, s.yearMonthFolders)
        val taken = HashSet<String>()
        val recs = items.filter { it.isLocalOnly }.map { m ->
            val lf = BackupPlanner.LocalFile(m.localId!!, m.name, m.size, m.takenAtMs / 1000, m.bucketName ?: "")
            val path = BackupPlanner.freePath(BackupPlanner.folderFor(lf, cfg), m.name, taken).also { taken += it.lowercase() }
            UploadRecord(m.localId, UploadRecord.QUEUED, path, null, m.size)
        }
        c.db.uploads().upsertAll(recs)
        c.backup.startUploads(userRequested = true)
        toast("Backing up ${recs.size} item(s)")
    }

    /** Saves remote-only items to the phone (Pictures/Nextcloud). */
    fun download(items: List<MediaItem>) = scope.launch {
        val remote = items.filter { it.isRemoteOnly }
        if (remote.isEmpty()) return@launch
        dialog = Dialog.Busy("Downloading…")
        var ok = 0
        withContext(Dispatchers.IO) {
            for (m in remote) runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, m.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, m.mime)
                    put(MediaStore.MediaColumns.DATE_TAKEN, m.takenAtMs)
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, (if (m.isVideo) "Movies" else "Pictures") + "/Nextcloud")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val col = if (m.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = context.contentResolver.insert(col, values) ?: error("insert failed")
                c.http.newCall(Request.Builder().url(c.api.streamUrl(m.remoteFileId!!)).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    context.contentResolver.openOutputStream(uri)!!.use { r.body.byteStream().copyTo(it) }
                }
                if (Build.VERSION.SDK_INT >= 29) context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                ok++
            }
        }
        dialog = null
        c.media.refreshLocal()
        toast("Saved $ok item(s) to Pictures/Nextcloud")
    }

    fun setWallpaper(item: MediaItem) = scope.launch {
        runCatching {
            val uri = shareUri(item)
            context.startActivity(WallpaperManager.getInstance(context).getCropAndSetWallpaperIntent(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { toast("Could not set wallpaper") }
    }

    fun copyToClipboard(item: MediaItem) = scope.launch {
        runCatching {
            val uri = shareUri(item)
            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newUri(context.contentResolver, item.name, uri))
            toast("Copied to clipboard")
        }
    }

    fun edit(item: MediaItem) = scope.launch {
        val uri = item.localUri ?: run { toast("Download this photo to edit it"); return@launch }
        runCatching {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_EDIT).setDataAndType(uri, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION), null))
        }.onFailure { toast("No editor app found") }
    }

    fun tag(items: List<MediaItem>) {
        if (items.none { it.isRemote }) { toast("Tags are stored on Nextcloud"); return }
        dialog = Dialog.Tag(items)
    }

    internal fun doTag(items: List<MediaItem>, name: String, existing: Cluster?) = scope.launch {
        dialog = null
        runCatching {
            val id = existing?.numericId ?: c.dav.createTag(name) ?: c.nextcloud.clusters("tags", true).firstOrNull { it.name == name }?.numericId
                ?: error("tag not found")
            items.filter { it.isRemote }.forEach { c.actions.setTags(it, listOf(id), emptyList()) }
            c.nextcloud.clusters("tags", refresh = true)
        }.onSuccess { toast("Tagged #$name") }.onFailure { toast("Could not tag: ${it.message}") }
    }

    internal fun showInfo(title: String, text: String) { dialog = Dialog.Info(title, text) }
    internal val container get() = c
}

@Composable
fun rememberMediaActions(onChanged: (removed: Set<String>) -> Unit = {}): MediaActions {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val a = remember { MediaActions(c, context, scope, onChanged) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val cb = a.afterIntent
        a.afterIntent = null
        scope.launch { cb?.invoke(r.resultCode == Activity.RESULT_OK) }
    }
    a.launchSender = { launcher.launch(it) }
    MediaActionDialogs(a)
    return a
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaActionDialogs(a: MediaActions) {
    val c = a.container
    val clusters by c.nextcloud.clusters.collectAsState()
    when (val d = a.dialog) {
        null -> {}
        is Dialog.Busy -> AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
            Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(28.dp)); Text("   ${d.text}") }
        })
        is Dialog.Info -> AlertDialog(
            onDismissRequest = { a.dialog = null },
            title = { Text(d.title) }, text = { Text(d.text) },
            confirmButton = { if (d.action != null) TextButton({ a.dialog = null; d.onAction?.invoke() }) { Text(d.action) } else TextButton({ a.dialog = null }) { Text("OK") } },
            dismissButton = { if (d.action != null) TextButton({ a.dialog = null }) { Text("Cancel") } },
        )
        is Dialog.Delete -> DeleteDialog(d.items, onCancel = { a.dialog = null }) { a.doDelete(d.items, it) }
        is Dialog.Album -> AlbumPicker(a, d.items)
        is Dialog.NewAlbum -> NameDialog("Create Nextcloud album", "Album name", onCancel = { a.dialog = null }) { a.doCreateAlbum(d.items, it) }
        is Dialog.MoveFolder -> FolderPicker(a, d.items)
        is Dialog.More -> MoreSheet(a, d.items)
        is Dialog.Date -> {
            val first = d.items.first()
            val st = rememberDatePickerState(initialSelectedDateMillis = first.takenAtMs + ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(first.takenAtMs)).totalSeconds * 1000L)
            DatePickerDialog(
                onDismissRequest = { a.dialog = null },
                confirmButton = {
                    TextButton({
                        val date = Instant.ofEpochMilli(st.selectedDateMillis ?: first.takenAtMs).atZone(ZoneOffset.UTC).toLocalDate()
                        a.dialog = Dialog.Time(d.items, date)
                    }) { Text("Next") }
                },
                dismissButton = { TextButton({ a.dialog = null }) { Text("Cancel") } },
            ) { DatePicker(st, title = { Text("Edit date and time", Modifier.padding(start = 24.dp, top = 16.dp)) }) }
        }
        is Dialog.Time -> {
            val t = Instant.ofEpochMilli(d.items.first().takenAtMs).atZone(ZoneId.systemDefault()).toLocalTime()
            val st = rememberTimePickerState(t.hour, t.minute)
            AlertDialog(
                onDismissRequest = { a.dialog = null },
                confirmButton = { TextButton({ a.doSetDate(d.items, LocalDateTime.of(d.date, LocalTime.of(st.hour, st.minute))) }) { Text("Save") } },
                dismissButton = { TextButton({ a.dialog = null }) { Text("Cancel") } },
                text = { TimePicker(st) },
            )
        }
        is Dialog.Location -> LocationPickerDialog(
            onDismiss = { a.dialog = null },
            onPick = { lat, lon -> a.doSetLocation(d.items, lat, lon) },
        )
        is Dialog.Tag -> TagDialog(a, d.items, clusters["tags"].orEmpty())
    }
}

@Composable
private fun DeleteDialog(items: List<MediaItem>, onCancel: () -> Unit, onConfirm: (DeleteScope) -> Unit) {
    val anyLocal = items.any { it.isLocal }
    val anyRemote = items.any { it.isRemote }
    var sel by remember { mutableStateOf(DeleteScope.BOTH) }
    val n = items.size
    val noun = if (n == 1) (if (items[0].isVideo) "video" else "picture") else "$n items"
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                when {
                    anyLocal && !anyRemote -> "Move $noun to the Recycle bin?"
                    !anyLocal -> "Delete $noun from Nextcloud?"
                    else -> "Delete $noun?"
                },
                fontSize = 16.sp,
            )
        },
        text = {
            Column {
                if (anyLocal && anyRemote) {
                    Text("Some of these are on both your phone and Nextcloud.", color = G.colors.textSecondary)
                    listOf(
                        DeleteScope.BOTH to "Delete from phone and Nextcloud",
                        DeleteScope.PHONE to "Delete from phone only",
                        DeleteScope.CLOUD to "Delete from Nextcloud only",
                    ).forEach { (s, label) ->
                        Row(Modifier.fillMaxWidth().clickable { sel = s }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(sel == s, { sel = s }); Text(label)
                        }
                    }
                } else if (!anyLocal) {
                    Text("Deleted items go to the Nextcloud trash bin, where you can restore them from the web.", color = G.colors.textSecondary)
                } else {
                    Text("Items in the Recycle bin are deleted after 30 days.", color = G.colors.textSecondary)
                }
            }
        },
        confirmButton = { TextButton({ onConfirm(if (anyLocal && anyRemote) sel else DeleteScope.BOTH) }) { Text(if (anyLocal && !anyRemote) "Move to Recycle bin" else "Delete", color = G.colors.danger, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun AlbumPicker(a: MediaActions, items: List<MediaItem>) {
    val clusters by a.container.nextcloud.clusters.collectAsState()
    val account by a.container.accounts.account.collectAsState()
    val albums = clusters["albums"].orEmpty().filter { !it.shared || it.user == account?.uid }
    AlertDialog(
        onDismissRequest = { a.dialog = null },
        title = { Text("Add to album") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { a.dialog = Dialog.NewAlbum(items) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Add, null); Text("   New Nextcloud album", fontWeight = FontWeight.Medium)
                    }
                }
                items(albums, key = { it.clusterId }) { al ->
                    Row(Modifier.fillMaxWidth().clickable { a.doAddToAlbum(items, al.name) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Cloud, null, tint = G.colors.textSecondary); Text("   ${al.name}"); Text("  ${al.count}", color = G.colors.textSecondary)
                    }
                }
                if (items.any { it.isLocal }) item {
                    Row(Modifier.fillMaxWidth().clickable { a.dialog = Dialog.MoveFolder(items) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, null, tint = G.colors.textSecondary); Text("   Move to phone album…")
                    }
                }
            }
        },
        confirmButton = { TextButton({ a.dialog = null }) { Text("Cancel") } },
    )
}

@Composable
private fun FolderPicker(a: MediaActions, items: List<MediaItem>) {
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        folders = a.container.db.local().all().map { it.relativePath }.filter { it.isNotBlank() }.distinct().sorted()
    }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { a.dialog = null },
        title = { Text("Move to album") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(newName, { newName = it }, label = { Text("New album name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (newName.isNotBlank()) TextButton({ a.doMoveToFolder(items, "Pictures/${newName.trim()}/") }) { Text("Create and move") }
                folders.forEach { f ->
                    Text(
                        f.trimEnd('/'), Modifier.fillMaxWidth().clickable { a.doMoveToFolder(items, f) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton({ a.dialog = null }) { Text("Cancel") } },
    )
}

@Composable
fun NameDialog(title: String, label: String, initial: String = "", onCancel: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton({ if (name.isNotBlank()) onOk(name.trim()) }, enabled = name.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun TagDialog(a: MediaActions, items: List<MediaItem>, tags: List<Cluster>) {
    var q by remember { mutableStateOf("") }
    val matches = tags.filter { q.isBlank() || it.name.contains(q, true) }.take(30)
    AlertDialog(
        onDismissRequest = { a.dialog = null },
        title = { Text("Add tag") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(q, { q = it }, label = { Text("Tag") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (q.isNotBlank() && tags.none { it.name.equals(q.trim(), true) }) {
                    TextButton({ a.doTag(items, q.trim(), null) }) { Text("Create #${q.trim()}") }
                }
                matches.forEach { t -> Text("# ${t.name}", Modifier.fillMaxWidth().clickable { a.doTag(items, t.name, t) }.padding(vertical = 11.dp)) }
            }
        },
        confirmButton = { TextButton({ a.dialog = null }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet(a: MediaActions, items: List<MediaItem>) {
    val c = a.container
    val account by c.accounts.account.collectAsState()
    val config by c.nextcloud.config.collectAsState()
    val signedIn = account != null
    val cfg = config
    val single = items.singleOrNull()
    val allFav = items.all { it.isFavorite }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { a.dialog = null }, sheetState = sheet, containerColor = G.colors.surface) {
        Column(Modifier.padding(bottom = 24.dp)) {
            @Composable
            fun row(label: String, enabled: Boolean = true, onClick: () -> Unit) {
                Text(
                    label, fontSize = 14.5.sp, color = if (enabled) G.colors.text else G.colors.textSecondary,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() }.padding(horizontal = 28.dp, vertical = 15.dp),
                )
            }
            row(if (allFav) "Remove from favourites" else "Add to favourites") { a.dialog = null; a.setFavorite(items, !allFav) }
            if (signedIn && cfg?.albums != false) row("Add to Nextcloud album") { a.dialog = Dialog.Album(items) }
            if (items.any { it.isLocal }) row("Move to album") { a.dialog = Dialog.MoveFolder(items) }
            if (signedIn && cfg?.tags == true) row("Add tag") { a.tag(items) }
            row("Edit date and time") { a.editDate(items) }
            row("Edit location") { a.editLocation(items) }
            if (signedIn && items.any { it.isRemote }) row("Move to Archive") { a.dialog = null; a.archive(items) }
            if (signedIn && items.any { it.isLocalOnly }) row("Back up to Nextcloud") { a.backUp(items) }
            if (items.any { it.isRemoteOnly }) row("Download to phone") { a.dialog = null; a.download(items) }
            if (single != null) {
                row("Copy to clipboard") { a.dialog = null; a.copyToClipboard(single) }
                if (!single.isVideo) row("Set as wallpaper") { a.dialog = null; a.setWallpaper(single) }
            }
        }
    }
}
