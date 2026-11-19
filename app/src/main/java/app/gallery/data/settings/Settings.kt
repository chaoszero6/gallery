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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

/** Phone-specific state, not choices: left out of settings backups. */
private val NOT_EXPORTED = setOf("bk_auto_since", "bk_last_auto", "bk_last_sync", "bk_last_sync_summary", "bk_buckets_init")

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
    /** Phone folders Sync compares; null = the backup folders. */
    val syncBuckets: Set<Long>? = null,
    /** Sync also matches against every photo in the Memories timeline, not just the upload folders. */
    val syncWholeLibrary: Boolean = false,
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
        val syncBuckets = stringSetPreferencesKey("bk_sync_buckets")
        val syncWhole = booleanPreferencesKey("bk_sync_whole")
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

    /** [ui] kept hot, so screens that come back into view get the settings on their first frame. */
    val uiState: StateFlow<UiSettings?> = ui.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, null)

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
        syncBuckets = this[K.syncBuckets]?.mapNotNull { it.toLongOrNull() }?.toSet(),
        syncWholeLibrary = this[K.syncWhole] ?: false,
    )

    /**
     * Every setting as JSON, for backup. Values that describe this phone's history rather than the
     * user's choices (last sync times and the like) are left out. The Nextcloud sign-in is not in
     * this store at all (see AccountStore), so it is never exported.
     */
    suspend fun exportAll(): kotlinx.serialization.json.JsonObject {
        val p = context.store.data.first()
        val ui = UiSettings()
        val bk = BackupSettings()
        // Every known setting with its current value, defaults included: an unchanged setting is not
        // stored at all, and a restore must still put it back to that value.
        val values = linkedMapOf<String, Any>()
        fun <T : Any> add(key: Preferences.Key<T>, default: T) { values[key.name] = p[key] ?: default }
        add(K.tipDismissed, ui.tipDismissed); add(K.gridLevel, ui.gridLevel); add(K.videoAutoplay, ui.videoAutoplay)
        add(K.showBadges, ui.showBackupBadges); add(K.searchCharging, ui.searchIndexWhileCharging); add(K.searchRemote, ui.searchIndexRemote)
        add(K.essential, ui.essentialAlbums); add(K.hidden, ui.hiddenAlbums)
        add(K.autoUpload, bk.autoUpload); add(K.buckets, bk.buckets.map(Long::toString).toSet()); add(K.destRoot, bk.destRoot)
        add(K.perBucket, bk.perBucketFolder); add(K.yearMonth, bk.yearMonthFolders); add(K.wifiOnly, bk.wifiOnly)
        add(K.charging, bk.chargingOnly); add(K.videos, bk.includeVideos); add(K.roaming, bk.allowRoaming)
        add(K.dailySync, bk.dailySync); add(K.syncWhole, bk.syncWholeLibrary)
        p[K.syncBuckets]?.let { values[K.syncBuckets.name] = it } // unset means "same as the backup folders"
        for ((k, v) in p.asMap()) if (k.name !in values) values[k.name] = v // anything newer
        return kotlinx.serialization.json.buildJsonObject {
            for ((name, v) in values) {
                if (name in NOT_EXPORTED) continue
                val (t, value) = when (v) {
                    is Boolean -> "bool" to kotlinx.serialization.json.JsonPrimitive(v)
                    is Int -> "int" to kotlinx.serialization.json.JsonPrimitive(v)
                    is Long -> "long" to kotlinx.serialization.json.JsonPrimitive(v)
                    is Float -> "float" to kotlinx.serialization.json.JsonPrimitive(v)
                    is Double -> "double" to kotlinx.serialization.json.JsonPrimitive(v)
                    is String -> "string" to kotlinx.serialization.json.JsonPrimitive(v)
                    is Set<*> -> "set" to kotlinx.serialization.json.JsonArray(v.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) })
                    else -> continue
                }
                put(name, kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive(t)); put("value", value) })
            }
        }
    }

    /**
     * Writes back settings from [exportAll]. Returns how many were applied. Auto-upload, if on,
     * counts from now, so restoring never queues the phone's whole history (Sync is for that).
     */
    suspend fun importAll(o: kotlinx.serialization.json.JsonObject): Int {
        var n = 0
        context.store.edit { p ->
            for ((name, e) in o) {
                if (name in NOT_EXPORTED) continue
                val obj = e as? kotlinx.serialization.json.JsonObject ?: continue
                val t = (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
                val v = obj["value"] ?: continue
                val prim = v as? kotlinx.serialization.json.JsonPrimitive
                when (t) {
                    "bool" -> prim?.content?.toBooleanStrictOrNull()?.let { p[booleanPreferencesKey(name)] = it }
                    "int" -> prim?.content?.toIntOrNull()?.let { p[intPreferencesKey(name)] = it }
                    "long" -> prim?.content?.toLongOrNull()?.let { p[longPreferencesKey(name)] = it }
                    "float" -> prim?.content?.toFloatOrNull()?.let { p[androidx.datastore.preferences.core.floatPreferencesKey(name)] = it }
                    "double" -> prim?.content?.toDoubleOrNull()?.let { p[androidx.datastore.preferences.core.doublePreferencesKey(name)] = it }
                    "string" -> prim?.content?.let { p[stringPreferencesKey(name)] = it }
                    "set" -> (v as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }?.toSet()?.let { p[stringSetPreferencesKey(name)] = it }
                    else -> null
                } ?: continue
                n++
            }
            if (p[K.autoUpload] == true) p[K.autoSince] = System.currentTimeMillis()
            if (p[K.buckets] != null) p[K.bucketsInitialised] = true
        }
        return n
    }

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
    suspend fun setSyncBuckets(v: Set<Long>) = context.store.edit { it[K.syncBuckets] = v.map(Long::toString).toSet() }
    suspend fun setSyncWholeLibrary(v: Boolean) = context.store.edit { it[K.syncWhole] = v }
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
