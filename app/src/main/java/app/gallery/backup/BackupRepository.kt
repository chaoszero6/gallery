package app.gallery.backup

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.gallery.data.db.BucketRow
import app.gallery.data.db.GalleryDb
import app.gallery.data.db.LocalMedia
import app.gallery.data.db.UploadRecord
import app.gallery.data.nextcloud.AccountStore
import app.gallery.data.nextcloud.DavClient
import app.gallery.data.repo.MediaRepository
import app.gallery.data.settings.BackupSettings
import app.gallery.data.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/** Nextcloud backup: auto-upload of new items and Sync (compare, then upload what is missing). */
class BackupRepository(
    private val context: Context,
    private val db: GalleryDb,
    private val settings: Settings,
    private val dav: DavClient,
    private val accounts: AccountStore,
    private val media: MediaRepository,
) {
    data class Progress(
        val running: Boolean = false,
        val current: String? = null,
        val done: Int = 0,
        val total: Int = 0,
        val bytesSent: Long = 0,
        val bytesTotal: Long = 0,
        val error: String? = null,
    )

    sealed interface SyncState {
        data object Idle : SyncState
        data class Comparing(val step: String) : SyncState
        data class Ready(val plan: BackupPlanner.Plan, val at: Long) : SyncState
        data class Failed(val message: String) : SyncState
    }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    val records = db.uploads().observeAll()

    internal fun setProgress(p: Progress) { _progress.value = p }

    suspend fun buckets(): List<BucketRow> = db.local().buckets()

    /** Default: back up the camera folder only. */
    suspend fun ensureDefaultBuckets() {
        if (settings.bucketsInitialised()) return
        val cam = db.local().buckets().filter { it.bucketName.equals("Camera", true) }.map { it.bucketId }.toSet()
        settings.setBuckets(cam)
    }

    private fun cfg(s: BackupSettings) = BackupPlanner.Config(s.destRoot, s.perBucketFolder, s.yearMonthFolders)

    private suspend fun candidates(s: BackupSettings): List<LocalMedia> =
        if (s.buckets.isEmpty()) emptyList()
        else db.local().inBuckets(s.buckets.toList()).filter { s.includeVideos || !it.isVideo }

    /**
     * Compares the chosen phone folders with their upload folders on the server and keeps the
     * result for the report screen. Only those server folders are listed; matching against the
     * whole Memories timeline (photos uploaded elsewhere, e.g. by the Nextcloud app) is opt-in.
     */
    suspend fun compare(): BackupPlanner.Plan? {
        if (accounts.account.value == null) { _sync.value = SyncState.Failed("Not connected to Nextcloud"); return null }
        return try {
            val s = settings.backupNow()
            val chosen = s.syncBuckets ?: s.buckets
            if (chosen.isEmpty()) { _sync.value = SyncState.Failed("Choose at least one folder to check"); return null }
            _sync.value = SyncState.Comparing("Reading phone")
            media.refreshLocal()
            val locals = db.local().inBuckets(chosen.toList()).filter { s.includeVideos || !it.isVideo }
            val cfg = cfg(s)
            val dest = ArrayList<BackupPlanner.DestFile>()
            for (folder in locals.map { BackupPlanner.bucketFolder(it.bucketName, cfg) }.distinct()) {
                _sync.value = SyncState.Comparing("Listing $folder on Nextcloud")
                dav.listRecursive(folder).mapTo(dest) { BackupPlanner.DestFile(it.path, it.size, it.fileId) }
            }
            val index = if (s.syncWholeLibrary) {
                _sync.value = SyncState.Comparing("Checking the rest of Nextcloud")
                media.syncRemote() // only fetches days that changed since the last refresh
                db.remote().all().map { BackupPlanner.IndexedFile(it.fileId, it.epoch, it.size, it.name) }
            } else emptyList()
            val uploaded = db.uploads().all().filter { it.status == UploadRecord.DONE && it.remoteFileId != null }
                .associate { it.localId to it.remoteFileId!! }
            val plan = BackupPlanner.plan(
                locals.map { BackupPlanner.LocalFile(it.id, it.name, it.size, it.takenSec, it.bucketName) },
                dest, index, uploaded, cfg,
            )
            // Remember links found by name/time so the grid shows them as backed up.
            for (p in plan.present) {
                val fid = p.remoteFileId ?: continue
                if (db.uploads().byId(p.local.id)?.status != UploadRecord.DONE) {
                    db.uploads().upsert(UploadRecord(p.local.id, UploadRecord.DONE, "", fid, p.local.size))
                }
            }
            _sync.value = SyncState.Ready(plan, System.currentTimeMillis())
            plan
        } catch (e: Exception) {
            Log.w(TAG, "compare failed", e)
            _sync.value = SyncState.Failed(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /** Queues the missing items from a plan and starts uploading. */
    suspend fun uploadMissing(plan: BackupPlanner.Plan) {
        val now = System.currentTimeMillis()
        db.uploads().upsertAll(plan.missing.map {
            UploadRecord(it.local.id, UploadRecord.QUEUED, it.targetPath, null, it.local.size, updatedAt = now)
        })
        settings.setLastSync(now, "${plan.present.size} on server · ${plan.missing.size} uploaded")
        startUploads()
    }

    /**
     * Auto-upload: queue phone items in the chosen folders that appeared after auto-upload was
     * switched on and have no upload record yet.
     */
    suspend fun queueNewItems(): Int {
        val s = settings.backupNow()
        if (!s.autoUpload || accounts.account.value == null) return 0
        val known = db.uploads().all().associateBy { it.localId }
        val fresh = candidates(s).filter { it.modifiedSec * 1000 >= s.autoUploadSinceMs || it.takenAtMs >= s.autoUploadSinceMs }
            .filter { it.id !in known }
            .filter { db.remote().byLocal(it.id) == null } // already on the server by another route
        if (fresh.isEmpty()) return 0
        val taken = HashSet<String>()
        val cfg = cfg(s)
        db.uploads().upsertAll(fresh.map { l ->
            val lf = BackupPlanner.LocalFile(l.id, l.name, l.size, l.takenSec, l.bucketName)
            val folder = BackupPlanner.folderFor(lf, cfg)
            val path = BackupPlanner.freePath(folder, l.name, taken).also { taken += it.lowercase() }
            UploadRecord(l.id, UploadRecord.QUEUED, path, null, l.size)
        })
        startUploads()
        return fresh.size
    }

    suspend fun retryFailed() {
        db.uploads().retryFailed()
        startUploads()
    }

    suspend fun startUploads() {
        val s = settings.backupNow()
        val c = Constraints.Builder()
            .setRequiredNetworkType(
                when {
                    s.wifiOnly -> NetworkType.UNMETERED
                    s.allowRoaming -> NetworkType.CONNECTED
                    else -> NetworkType.NOT_ROAMING
                },
            )
            .setRequiresCharging(s.chargingOnly)
            .build()
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(c)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UploadWorker.NAME, ExistingWorkPolicy.KEEP, req)
    }

    fun cancelUploads() {
        WorkManager.getInstance(context).cancelUniqueWork(UploadWorker.NAME)
    }

    /** Applies the schedule settings: the new-media trigger and the optional daily sync. */
    suspend fun applySchedule() {
        val s = settings.backupNow()
        val wm = WorkManager.getInstance(context)
        if (s.autoUpload && accounts.account.value != null) NewMediaTriggerWorker.schedule(context)
        else wm.cancelUniqueWork(NewMediaTriggerWorker.NAME)
        if (s.dailySync && accounts.account.value != null) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(SyncWorker.NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else wm.cancelUniqueWork(SyncWorker.NAME)
    }

    companion object { private const val TAG = "Backup" }
}
