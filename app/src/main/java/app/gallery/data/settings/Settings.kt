package app.gallery.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

data class BackupSettings(
    val autoUpload: Boolean = false,
    /** Only items taken after this instant are auto-uploaded; older ones are Sync's job. */
    val autoUploadSinceMs: Long = 0,
    val buckets: Set<Long> = emptySet(),
    val destRoot: String = "/Photos",
    val perBucketFolder: Boolean = true,
    val yearMonthFolders: Boolean = false,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val includeVideos: Boolean = true,
    val allowRoaming: Boolean = false,
    val dailySync: Boolean = false,
    val lastAutoUploadMs: Long = 0,
    val lastSyncMs: Long = 0,
    val lastSyncSummary: String = "",
)

data class UiSettings(
    val tipDismissed: Boolean = false,
    /** Index into GridLevels (2 = 4 columns by day). */
    val gridLevel: Int = 2,
    val videoAutoplay: Boolean = true,
    val showBackupBadges: Boolean = true,
    val searchIndexWhileCharging: Boolean = true,
    val searchIndexRemote: Boolean = true,
    val essentialAlbums: Set<String> = emptySet(),
    val hiddenAlbums: Set<String> = emptySet(),
)

class Settings(private val context: Context) {
    private object K {
        val tipDismissed = booleanPreferencesKey("tip_dismissed")
        val gridLevel = intPreferencesKey("grid_level")
        val videoAutoplay = booleanPreferencesKey("video_autoplay")
        val showBadges = booleanPreferencesKey("show_backup_badges")
        val searchCharging = booleanPreferencesKey("search_index_charging")
        val searchRemote = booleanPreferencesKey("search_index_remote")
        val essential = stringSetPreferencesKey("essential_albums")
        val hidden = stringSetPreferencesKey("hidden_albums")

        val autoUpload = booleanPreferencesKey("bk_auto")
        val autoSince = longPreferencesKey("bk_auto_since")
        val buckets = stringSetPreferencesKey("bk_buckets")
        val destRoot = stringPreferencesKey("bk_dest")
        val perBucket = booleanPreferencesKey("bk_per_bucket")
        val yearMonth = booleanPreferencesKey("bk_year_month")
        val wifiOnly = booleanPreferencesKey("bk_wifi")
        val charging = booleanPreferencesKey("bk_charging")
        val videos = booleanPreferencesKey("bk_videos")
        val roaming = booleanPreferencesKey("bk_roaming")
        val dailySync = booleanPreferencesKey("bk_daily")
        val lastAuto = longPreferencesKey("bk_last_auto")
        val lastSync = longPreferencesKey("bk_last_sync")
        val lastSyncSummary = stringPreferencesKey("bk_last_sync_summary")
        val bucketsInitialised = booleanPreferencesKey("bk_buckets_init")
    }

    val ui: Flow<UiSettings> = context.store.data.map { p ->
        UiSettings(
            tipDismissed = p[K.tipDismissed] ?: false,
            gridLevel = p[K.gridLevel] ?: 2,
            videoAutoplay = p[K.videoAutoplay] ?: true,
            showBackupBadges = p[K.showBadges] ?: true,
            searchIndexWhileCharging = p[K.searchCharging] ?: true,
            searchIndexRemote = p[K.searchRemote] ?: true,
            essentialAlbums = p[K.essential] ?: emptySet(),
            hiddenAlbums = p[K.hidden] ?: emptySet(),
        )
    }

    val backup: Flow<BackupSettings> = context.store.data.map { p -> p.toBackup() }

    private fun Preferences.toBackup() = BackupSettings(
        autoUpload = this[K.autoUpload] ?: false,
        autoUploadSinceMs = this[K.autoSince] ?: 0,
        buckets = this[K.buckets]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet(),
        destRoot = this[K.destRoot] ?: "/Photos",
        perBucketFolder = this[K.perBucket] ?: true,
        yearMonthFolders = this[K.yearMonth] ?: false,
        wifiOnly = this[K.wifiOnly] ?: true,
        chargingOnly = this[K.charging] ?: false,
        includeVideos = this[K.videos] ?: true,
        allowRoaming = this[K.roaming] ?: false,
        dailySync = this[K.dailySync] ?: false,
        lastAutoUploadMs = this[K.lastAuto] ?: 0,
        lastSyncMs = this[K.lastSync] ?: 0,
        lastSyncSummary = this[K.lastSyncSummary] ?: "",
    )

    suspend fun backupNow(): BackupSettings = backup.first()
    suspend fun uiNow(): UiSettings = ui.first()

    suspend fun bucketsInitialised(): Boolean = context.store.data.first()[K.bucketsInitialised] ?: false

    suspend fun setTipDismissed(v: Boolean) = context.store.edit { it[K.tipDismissed] = v }
    suspend fun setGridLevel(v: Int) = context.store.edit { it[K.gridLevel] = v }
    suspend fun setVideoAutoplay(v: Boolean) = context.store.edit { it[K.videoAutoplay] = v }
    suspend fun setShowBadges(v: Boolean) = context.store.edit { it[K.showBadges] = v }
    suspend fun setSearchIndexWhileCharging(v: Boolean) = context.store.edit { it[K.searchCharging] = v }
    suspend fun setSearchIndexRemote(v: Boolean) = context.store.edit { it[K.searchRemote] = v }
    suspend fun setEssentialAlbums(v: Set<String>) = context.store.edit { it[K.essential] = v }
    suspend fun setHiddenAlbums(v: Set<String>) = context.store.edit { it[K.hidden] = v }

    suspend fun setAutoUpload(v: Boolean) = context.store.edit {
        it[K.autoUpload] = v
        if (v) it[K.autoSince] = System.currentTimeMillis()
    }
    suspend fun setBuckets(v: Set<Long>) = context.store.edit {
        it[K.buckets] = v.map(Long::toString).toSet()
        it[K.bucketsInitialised] = true
    }
    suspend fun setDestRoot(v: String) = context.store.edit { it[K.destRoot] = "/" + v.trim().trim('/') }
    suspend fun setPerBucketFolder(v: Boolean) = context.store.edit { it[K.perBucket] = v }
    suspend fun setYearMonthFolders(v: Boolean) = context.store.edit { it[K.yearMonth] = v }
    suspend fun setWifiOnly(v: Boolean) = context.store.edit { it[K.wifiOnly] = v }
    suspend fun setChargingOnly(v: Boolean) = context.store.edit { it[K.charging] = v }
    suspend fun setIncludeVideos(v: Boolean) = context.store.edit { it[K.videos] = v }
    suspend fun setAllowRoaming(v: Boolean) = context.store.edit { it[K.roaming] = v }
    suspend fun setDailySync(v: Boolean) = context.store.edit { it[K.dailySync] = v }
    suspend fun setLastAutoUpload(v: Long) = context.store.edit { it[K.lastAuto] = v }
    suspend fun setLastSync(at: Long, summary: String) = context.store.edit {
        it[K.lastSync] = at
        it[K.lastSyncSummary] = summary
    }
}
