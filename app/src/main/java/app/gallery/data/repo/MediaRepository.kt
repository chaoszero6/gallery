package app.gallery.data.repo

import android.net.Uri
import android.util.Log
import app.gallery.data.db.GalleryDb
import app.gallery.data.db.LocalMedia
import app.gallery.data.db.RemoteDay
import app.gallery.data.db.RemoteMedia
import app.gallery.data.local.MediaStoreSource
import app.gallery.data.model.DayIds
import app.gallery.data.model.MediaItem
import app.gallery.data.model.MediaSource
import app.gallery.data.nextcloud.AccountStore
import app.gallery.data.nextcloud.DayFilters
import app.gallery.data.nextcloud.MemoriesApi
import app.gallery.data.nextcloud.PhotoDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/** Merged view of the phone's media and the Memories timeline. */
class MediaRepository(
    private val db: GalleryDb,
    private val store: MediaStoreSource,
    private val api: MemoriesApi,
    private val accounts: AccountStore,
    private val scope: CoroutineScope,
) {
    data class Snapshot(val items: List<MediaItem>, val byKey: Map<String, MediaItem>, val byRemoteId: Map<Long, MediaItem>) {
        companion object { val Empty = Snapshot(emptyList(), emptyMap(), emptyMap()) }
    }

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    private val _localLoaded = MutableStateFlow(false)
    val localLoaded: StateFlow<Boolean> = _localLoaded.asStateFlow()

    private val localMutex = Mutex()
    private val remoteMutex = Mutex()

    /** Everything, newest first. */
    val snapshot: StateFlow<Snapshot> = combine(
        db.local().observeAll(),
        db.remote().observeAll(),
        db.favorites().observe(),
        accounts.account,
    ) { locals, remotes, favs, account ->
        merge(locals, if (account == null) emptyList() else remotes, favs.toHashSet())
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, Snapshot.Empty)

    val timeline: StateFlow<List<MediaItem>> = snapshot.map { it.items }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun item(key: String): MediaItem? = snapshot.value.byKey[key]

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch { refreshLocal() }
        scope.launch {
            store.changes().debounce(1500).collect { refreshLocal() }
        }
    }

    suspend fun refreshLocal() = localMutex.withLock {
        runCatching {
            val items = store.scan()
            db.local().replaceAll(items)
            relink()
        }.onFailure { Log.w(TAG, "local scan failed", it) }
        _localLoaded.value = true
    }

    /** Recomputes which server items are the same photo as a phone item. */
    suspend fun relink() {
        val locals = db.local().all()
        val remotes = db.remote().all()
        if (remotes.isEmpty()) return
        val uploaded = db.uploads().all().mapNotNull { r -> r.remoteFileId?.let { r.localId to it } }.toMap()
        val links = Matcher.match(
            locals.map { Matcher.Local(it.id, it.takenSec, it.size, it.name) },
            remotes.map { Matcher.Remote(it.fileId, it.epoch, it.size, it.name) },
            uploaded,
        )
        for (r in remotes) {
            val want = links[r.fileId]
            if (r.linkedLocalId != want) db.remote().link(r.fileId, want)
        }
    }

    /**
     * Brings the cached Memories timeline up to date. Only days whose photo count changed
     * (or that were never loaded) are fetched again.
     */
    suspend fun syncRemote(force: Boolean = false): Result<Unit> = remoteMutex.withLock {
        if (accounts.account.value == null) return Result.success(Unit)
        _syncing.value = true
        val res = runCatching {
            val serverDays = api.days()
            val known = db.days().all().associateBy { it.dayId }
            val serverIds = serverDays.map { it.dayid }.toHashSet()
            val gone = known.keys.filter { it !in serverIds }
            if (gone.isNotEmpty()) {
                gone.chunked(500).forEach { db.remote().deleteDays(it); db.days().delete(it) }
            }
            val stale = serverDays.filter { d -> force || known[d.dayid]?.let { it.count != d.count || it.loadedCount != d.count } ?: true }
            // Batch day requests so each response stays a reasonable size.
            val batches = ArrayList<List<Int>>()
            var cur = ArrayList<Int>()
            var curCount = 0
            for (d in stale) {
                if (cur.isNotEmpty() && (curCount + d.count > 400 || cur.size >= 30)) { batches += cur; cur = ArrayList(); curCount = 0 }
                cur += d.dayid; curCount += d.count
            }
            if (cur.isNotEmpty()) batches += cur
            val countByDay = serverDays.associate { it.dayid to it.count }
            for (b in batches) {
                val photos = api.day(b)
                val grouped = photos.groupBy { it.dayid_real ?: it.dayid }
                for (dayId in b) {
                    val list = grouped[dayId].orEmpty().map { it.toEntity() }
                    db.remote().replaceDay(dayId, list)
                }
                db.days().upsert(b.map { RemoteDay(it, countByDay[it] ?: 0, grouped[it]?.size ?: 0) })
            }
            relink()
        }
        res.exceptionOrNull()?.let { Log.w(TAG, "remote sync failed", it); _lastError.value = it.message }
        if (res.isSuccess) _lastError.value = null
        _syncing.value = false
        res
    }

    suspend fun clearRemote() {
        db.remote().clear()
        db.days().clear()
    }

    /** Loads a grid's content. Phone-backed sources come from the merged snapshot; the rest from the server. */
    suspend fun load(source: MediaSource): List<MediaItem> {
        val snap = snapshot.value
        return when (source) {
            MediaSource.Timeline -> snap.items
            MediaSource.Favorites -> snap.items.filter { it.isFavorite }
            MediaSource.Videos -> snap.items.filter { it.isVideo }
            MediaSource.Recent -> {
                val cutoff = System.currentTimeMillis() - 30L * 86_400_000
                val locals = db.local().all().filter { it.modifiedSec * 1000 >= cutoff }.sortedByDescending { it.modifiedSec }
                locals.mapNotNull { snap.byKey["L:${it.id}"] }
            }
            is MediaSource.LocalBucket -> snap.items.filter { it.bucketId == source.bucketId }
            is MediaSource.Items -> source.keys.mapNotNull { snap.byKey[it] }
            is MediaSource.RemoteAlbum -> remote(listOf("albums" to "${source.owner}/${source.name}"))
            is MediaSource.Person -> remote(listOf(source.backend to "${source.user}/${source.id}"))
            is MediaSource.Place -> remote(listOf("places" to source.osmId.toString()))
            is MediaSource.Tag -> remote(listOf("tags" to source.name))
            is MediaSource.MapArea -> remote(listOf("mapbounds" to listOf(source.minLat, source.maxLat, source.minLon, source.maxLon).joinToString(",")))
            MediaSource.Archive -> remote(listOf("archive" to "1"))
        }
    }

    @Volatile private var localBucket: Map<Long, Long> = emptyMap()

    /** Photos from a filtered Memories query, linked to phone copies where known. */
    suspend fun remote(filters: DayFilters): List<MediaItem> {
        val days = api.days(filters)
        val out = ArrayList<MediaItem>()
        for (chunk in days.map { it.dayid }.chunked(30)) {
            api.day(chunk, filters).mapTo(out) { toItem(it) }
        }
        return out.sortedWith(MediaItem.TimelineOrder)
    }

    suspend fun onThisDay(range: Int): List<MediaItem> {
        val ids = DayIds.onThisDay(LocalDate.now(), range)
        val idSet = ids.toHashSet()
        val snap = snapshot.value
        val local = snap.items.filter { it.isLocal && it.dayId in idSet }
        val remote = if (accounts.account.value != null) runCatching { api.daysByIds(ids).map { toItem(it) } }.getOrDefault(emptyList()) else emptyList()
        val seen = HashSet<String>()
        return (local + remote).filter { seen.add(it.key) }.sortedWith(MediaItem.TimelineOrder)
    }

    fun toItem(p: PhotoDto): MediaItem {
        snapshot.value.byRemoteId[p.fileid]?.let { return it }
        return remoteItem(p.toEntity(), null, false)
    }

    // --- merge --------------------------------------------------------------------------------

    private fun merge(locals: List<LocalMedia>, remotes: List<RemoteMedia>, favs: Set<Long>): Snapshot {
        localBucket = locals.associate { it.id to it.bucketId }
        val linked = HashMap<Long, RemoteMedia>(remotes.size)
        for (r in remotes) r.linkedLocalId?.let { linked[it] = r }
        val out = ArrayList<MediaItem>(locals.size + remotes.size)
        for (l in locals) out += localItem(l, linked[l.id], favs)
        for (r in remotes) if (r.linkedLocalId == null || r.linkedLocalId !in localBucket) out += remoteItem(r, null, false)
        out.sortWith(MediaItem.TimelineOrder)
        val byKey = HashMap<String, MediaItem>(out.size * 2)
        val byRemote = HashMap<Long, MediaItem>(remotes.size * 2)
        for (m in out) {
            byKey[m.key] = m
            m.remoteFileId?.let { byRemote[it] = m; byKey["R:$it"] = m }
        }
        return Snapshot(out, byKey, byRemote)
    }

    private fun localItem(l: LocalMedia, r: RemoteMedia?, favs: Set<Long>) = MediaItem(
        key = "L:${l.id}",
        localId = l.id,
        localUri = Uri.parse(l.uri),
        remoteFileId = r?.fileId,
        remoteEtag = r?.etag,
        remotePath = r?.path,
        name = l.name,
        mime = l.mime,
        size = l.size,
        takenAtMs = l.takenAtMs,
        dayId = l.dayId,
        width = l.width,
        height = l.height,
        isVideo = l.isVideo,
        durationMs = l.durationMs,
        isFavorite = l.isFavorite || l.id in favs || (r?.isFavorite == true),
        liveId = r?.liveId,
        bucketId = l.bucketId,
        bucketName = l.bucketName,
        relativePath = l.relativePath,
    )

    private fun remoteItem(r: RemoteMedia, l: LocalMedia?, fav: Boolean) = MediaItem(
        key = "R:${r.fileId}",
        localId = l?.id,
        localUri = l?.let { Uri.parse(it.uri) },
        remoteFileId = r.fileId,
        remoteEtag = r.etag,
        remotePath = r.path,
        name = r.name,
        mime = r.mime,
        size = r.size,
        takenAtMs = r.epoch * 1000,
        dayId = r.dayId,
        width = r.width,
        height = r.height,
        isVideo = r.isVideo,
        durationMs = r.durationSec * 1000L,
        isFavorite = r.isFavorite || fav,
        liveId = r.liveId,
        bucketId = null,
        bucketName = null,
        relativePath = r.path?.substringBeforeLast('/'),
    )

    companion object {
        private const val TAG = "MediaRepository"

        fun PhotoDto.toEntity() = RemoteMedia(
            fileId = fileid,
            dayId = dayid_real ?: dayid,
            epoch = epoch,
            size = size,
            name = basename,
            mime = mimetype,
            width = w,
            height = h,
            etag = etag,
            isVideo = isvideo == 1,
            durationSec = video_duration,
            liveId = liveid?.takeIf { it.isNotBlank() },
            isFavorite = isfavorite == 1,
        )
    }
}
