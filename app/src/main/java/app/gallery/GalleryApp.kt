package app.gallery

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.gallery.backup.TimelineSyncWorker
import coil3.SingletonImageLoader
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class GalleryApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val c = container
        c.media.start()
        c.nextcloud.start()
        c.scope.launch {
            c.backup.applySchedule()
            if (c.accounts.account.value != null) schedulePeriodicSync()
        }
        // Refresh from Nextcloud whenever the app comes to the foreground (in the background, so the
        // phone's photos never wait for it), if the last refresh failed or is older than 15 minutes.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) = c.media.onForeground()
        })
        // After every phone rescan, queue new items for auto-upload.
        c.scope.launch {
            c.media.snapshot.map { s -> s.items.count { it.isLocal } }.distinctUntilChanged().drop(1).collect {
                runCatching { c.backup.queueNewItems() }
            }
        }
    }

    fun schedulePeriodicSync() {
        val req = PeriodicWorkRequestBuilder<TimelineSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(TimelineSyncWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    override fun newImageLoader(context: android.content.Context) = container.imageLoader
}
