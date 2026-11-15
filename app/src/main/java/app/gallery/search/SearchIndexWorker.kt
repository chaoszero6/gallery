package app.gallery.search

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.gallery.GalleryApp

/** Indexes photos for smart search in batches; re-enqueues itself until everything is indexed. */
class SearchIndexWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as GalleryApp).container
        val all = c.media.snapshot.value.items
        if (all.isEmpty()) return Result.success()
        val n = c.searchIndexer.indexBatch(all, BATCH) { isStopped }
        if (n > 0 && c.searchIndexer.pending(c.media.snapshot.value.items).isNotEmpty() && !isStopped) {
            schedule(applicationContext, c.settings.uiNow().searchIndexWhileCharging, replace = true)
        }
        return Result.success()
    }

    companion object {
        const val NAME = "search_index"
        private const val BATCH = 300

        fun schedule(context: Context, whileCharging: Boolean, replace: Boolean = false) {
            val c = Constraints.Builder().setRequiresBatteryNotLow(true).apply {
                if (whileCharging) setRequiresCharging(true).setRequiresDeviceIdle(false)
            }.build()
            val req = OneTimeWorkRequestBuilder<SearchIndexWorker>().setConstraints(c).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, if (replace) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, req)
        }
    }
}
