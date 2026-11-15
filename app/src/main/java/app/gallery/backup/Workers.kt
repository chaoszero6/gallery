package app.gallery.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.gallery.GalleryApp
import app.gallery.MainActivity
import app.gallery.R
import app.gallery.data.db.UploadRecord
import app.gallery.data.nextcloud.HttpException
import app.gallery.data.nextcloud.NcJson
import app.gallery.data.repo.OpPayload
import java.io.FileNotFoundException
import java.io.IOException

private const val CHANNEL = "backup"

internal fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= 26) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nextcloud backup", NotificationManager.IMPORTANCE_LOW))
        }
    }
}

private fun openAppIntent(context: Context) = PendingIntent.getActivity(
    context, 0, Intent(context, MainActivity::class.java).putExtra("open", "nextcloud"),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

/** Uploads every queued UploadRecord, one at a time, as a foreground job with progress. */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app get() = (applicationContext as GalleryApp).container

    override suspend fun getForegroundInfo(): ForegroundInfo = info("Backing up to Nextcloud", 0, 0)

    private fun info(text: String, done: Int, total: Int): ForegroundInfo {
        ensureChannel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_upload)
            .setContentTitle("Nextcloud backup")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .setContentIntent(openAppIntent(applicationContext))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    override suspend fun doWork(): Result {
        val c = app
        if (c.accounts.account.value == null) return Result.success()
        val dao = c.db.uploads()
        val queue = dao.pending()
        if (queue.isEmpty()) return Result.success()
        runCatching { setForeground(info("Preparing…", 0, queue.size)) }
        val totalBytes = queue.sumOf { it.size }
        var bytesDone = 0L
        var done = 0
        var retry = false
        for (rec0 in queue) {
            if (isStopped) break
            val local = c.db.local().byId(rec0.localId)
            if (local == null) { // deleted from the phone meanwhile
                dao.upsert(rec0.copy(status = UploadRecord.FAILED, error = "File no longer on phone"))
                continue
            }
            var rec = rec0.copy(status = UploadRecord.UPLOADING, attempts = rec0.attempts + 1, size = local.size, updatedAt = System.currentTimeMillis())
            dao.upsert(rec)
            c.backup.setProgress(BackupRepository.Progress(true, local.name, done, queue.size, bytesDone, totalBytes))
            runCatching { setForeground(info("${done + 1} of ${queue.size}: ${local.name}", done, queue.size)) }
            try {
                val id = c.uploadEngine.upload(rec, applicationContext.contentResolver.byteSource(android.net.Uri.parse(local.uri)), local.mime, local.modifiedSec) { sent, _ ->
                    c.backup.setProgress(BackupRepository.Progress(true, local.name, done, queue.size, bytesDone + sent, totalBytes))
                }
                rec = rec.copy(status = UploadRecord.DONE, remoteFileId = id, error = null, updatedAt = System.currentTimeMillis())
                dao.upsert(rec)
                id?.let { c.api.reindex(it) }
                done++
                bytesDone += local.size
            } catch (e: HttpException) {
                Log.w(TAG, "upload ${local.name} failed", e)
                if (e.code == 507) {
                    dao.upsert(rec.copy(status = UploadRecord.QUEUED, error = "Nextcloud storage is full"))
                    c.backup.setProgress(BackupRepository.Progress(false, null, done, queue.size, bytesDone, totalBytes, "Nextcloud storage is full"))
                    notifyError("Backup stopped: your Nextcloud storage is full.")
                    return Result.failure()
                }
                val fatal = e.code in 400..499 && e.code != 408 && e.code != 423 && e.code != 429
                dao.upsert(rec.copy(status = if (fatal || rec.attempts >= 5) UploadRecord.FAILED else UploadRecord.QUEUED, error = e.message))
                if (!fatal) retry = true
            } catch (e: FileNotFoundException) {
                dao.upsert(rec.copy(status = UploadRecord.FAILED, error = "File no longer on phone"))
            } catch (e: IOException) {
                Log.w(TAG, "upload ${local.name} interrupted", e)
                dao.upsert(rec.copy(status = if (rec.attempts >= 5) UploadRecord.FAILED else UploadRecord.QUEUED, error = e.message))
                retry = true
                break // network trouble: let WorkManager back off
            }
        }
        c.settings.setLastAutoUpload(System.currentTimeMillis())
        c.backup.setProgress(BackupRepository.Progress(false, null, done, queue.size, bytesDone, totalBytes))
        c.media.relink()
        return if (retry) Result.retry() else Result.success()
    }

    private fun notifyError(text: String) {
        ensureChannel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_upload).setContentTitle("Nextcloud backup").setContentText(text)
            .setContentIntent(openAppIntent(applicationContext)).setAutoCancel(true).build()
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n) }
    }

    companion object {
        const val NAME = "uploads"
        private const val NOTIF_ID = 4101
        private const val TAG = "UploadWorker"
    }
}

/** Daily sync: compare the phone with the server and upload what is missing. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as GalleryApp).container
        val plan = c.backup.compare() ?: return Result.retry()
        if (plan.missing.isNotEmpty()) c.backup.uploadMissing(plan)
        else c.settings.setLastSync(System.currentTimeMillis(), "${plan.present.size} on server · nothing missing")
        return Result.success()
    }

    companion object { const val NAME = "daily_sync" }
}

/**
 * Fires when MediaStore gets new photos (even if the app is closed), queues them for
 * auto-upload, then re-arms itself (content-URI triggers are one-shot).
 */
class NewMediaTriggerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as GalleryApp).container
        runCatching {
            c.media.refreshLocal()
            c.backup.queueNewItems()
        }.onFailure { Log.w("NewMediaTrigger", "failed", it) }
        if (c.settings.backupNow().autoUpload) schedule(applicationContext)
        return Result.success()
    }

    companion object {
        const val NAME = "new_media_trigger"

        fun schedule(context: Context) {
            if (Build.VERSION.SDK_INT < 24) return
            val c = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(java.time.Duration.ofSeconds(5))
                .build()
            val req = OneTimeWorkRequestBuilder<NewMediaTriggerWorker>().setConstraints(c).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}

/** Replays server changes that could not be sent at the time (offline). */
class PendingOpWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as GalleryApp).container
        val dao = c.db.pendingOps()
        for (op in dao.all()) {
            try {
                c.actions.execute(op.type, NcJson.decodeFromString(OpPayload.serializer(), op.payload))
                dao.delete(op.id)
            } catch (e: HttpException) {
                if (e.code in 400..499 && e.code != 408 && e.code != 429) dao.delete(op.id) // will never succeed
                else { dao.update(op.copy(attempts = op.attempts + 1, lastError = e.message)); return Result.retry() }
            } catch (e: IOException) {
                dao.update(op.copy(attempts = op.attempts + 1, lastError = e.message))
                return Result.retry()
            } catch (e: Exception) {
                Log.w("PendingOpWorker", "dropping ${op.type}", e)
                dao.delete(op.id)
            }
        }
        return Result.success()
    }
}

/** Periodic refresh of the cached Memories timeline. */
class TimelineSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as GalleryApp).container
        return if (c.media.syncRemote().isSuccess) Result.success() else Result.retry()
    }

    companion object { const val NAME = "timeline_sync" }
}
