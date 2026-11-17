package app.gallery.search

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat

/**
 * Builds the smart-search index in one long run, as a foreground job with a progress
 * notification, so it keeps going after the app is closed. Finished items are saved as it goes:
 * if Android stops the job, the next run carries on where this one left off.
 */
class SearchIndexWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = info(0, 0)

    override suspend fun doWork(): Result = coroutineScope {
        val c = (applicationContext as GalleryApp).container
        // When Android starts this job with the app closed, the photo list is still loading from
        // the database; reading it straight away saw nothing and ended the job.
        val all = withTimeoutOrNull(60_000) { c.media.snapshot.first { it.items.isNotEmpty() } }?.items
            ?: return@coroutineScope Result.success()
        if (c.searchIndexer.pending(all).isEmpty()) return@coroutineScope Result.success()

        // Allowed only while the app is visible (Android 12+); otherwise this runs as a normal job,
        // which Android may pause and resume later.
        runCatching { setForeground(info(0, 0)) }
        val notifier = launch {
            while (true) {
                delay(2_000)
                val s = c.searchIndexer.status.value
                if (s.running) runCatching { setForeground(info(s.done, s.total)) }
            }
        }
        c.searchIndexer.indexAll(all) { isStopped }
        notifier.cancel()
        Result.success()
    }

    private fun info(done: Int, total: Int): ForegroundInfo {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Smart search indexing", NotificationManager.IMPORTANCE_MIN))
            }
        }
        val fmt = NumberFormat.getIntegerInstance()
        val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_search)
            .setContentTitle("Preparing smart search")
            .setContentText(if (total > 0) "${fmt.format(done)} of ${fmt.format(total)} photos and videos" else "Starting…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    companion object {
        const val NAME = "search_index"
        private const val CHANNEL = "search_index"
        private const val NOTIF_ID = 7302

        /**
         * Starts indexing. KEEP leaves a queued or running job alone. [userRequested] (the user
         * tapped "Index now"; callers only offer it when nothing is running) replaces a job that is
         * only waiting for its charging constraint, so it starts now.
         */
        fun schedule(context: Context, whileCharging: Boolean, userRequested: Boolean = false) {
            val c = Constraints.Builder().setRequiresBatteryNotLow(true).apply {
                if (whileCharging) setRequiresCharging(true)
            }.build()
            val req = OneTimeWorkRequestBuilder<SearchIndexWorker>().setConstraints(c).build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NAME, if (userRequested) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
        }
    }
}
