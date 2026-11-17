package app.gallery.data.repo

import android.content.Context
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.gallery.backup.PendingOpWorker
import app.gallery.data.db.GalleryDb
import app.gallery.data.db.LocalFavorite
import app.gallery.data.db.PendingOp
import app.gallery.data.model.MediaItem
import app.gallery.data.nextcloud.DavClient
import app.gallery.data.nextcloud.HttpException
import app.gallery.data.nextcloud.MemoriesApi
import app.gallery.data.nextcloud.NcJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

@Serializable
data class OpPayload(
    val fileId: Long = 0,
    val flag: Boolean = false,
    val album: String? = null,
    val basename: String? = null,
    val raw: Map<String, String> = emptyMap(),
    val add: List<Long> = emptyList(),
    val remove: List<Long> = emptyList(),
)

/**
 * Changes to photos. The phone side is applied at once; the server side runs now when online,
 * otherwise it is queued and replayed by [PendingOpWorker].
 */
class ActionsRepository(
    private val context: Context,
    private val db: GalleryDb,
    private val api: MemoriesApi,
    private val dav: DavClient,
    private val nc: NextcloudRepository,
    private val media: MediaRepository,
) {
    object Op {
        const val FAVORITE = "favorite"
        const val ARCHIVE = "archive"
        const val DELETE = "delete"
        const val ALBUM_ADD = "album_add"
        const val ALBUM_REMOVE = "album_remove"
        const val EXIF = "exif"
        const val TAGS = "tags"
    }

    suspend fun setFavorite(items: List<MediaItem>, favorite: Boolean) {
        for (it in items) {
            it.localId?.let { id -> if (favorite) db.favorites().add(LocalFavorite(id)) else db.favorites().remove(id) }
            it.remoteFileId?.let { id ->
                db.remote().setFavorite(id, favorite)
                runOrQueue(Op.FAVORITE, OpPayload(fileId = id, flag = favorite))
            }
        }
    }

    suspend fun archive(items: List<MediaItem>, archive: Boolean) {
        val ids = items.mapNotNull { it.remoteFileId }
        if (archive) db.remote().delete(ids)
        ids.forEach { runOrQueue(Op.ARCHIVE, OpPayload(fileId = it, flag = archive)) }
        if (!archive) media.requestRemoteSync()
    }

    /** Deletes the server copies (they go to the Nextcloud trash bin). */
    suspend fun deleteRemote(items: List<MediaItem>) {
        val ids = items.mapNotNull { it.remoteFileId }
        db.remote().delete(ids)
        ids.forEach { runOrQueue(Op.DELETE, OpPayload(fileId = it)) }
    }

    suspend fun addToRemoteAlbum(items: List<MediaItem>, album: String) {
        items.mapNotNull { it.remoteFileId }.forEach { runOrQueue(Op.ALBUM_ADD, OpPayload(fileId = it, album = album)) }
    }

    suspend fun removeFromRemoteAlbum(items: List<MediaItem>, album: String) {
        items.filter { it.remoteFileId != null }.forEach {
            runOrQueue(Op.ALBUM_REMOVE, OpPayload(fileId = it.remoteFileId!!, album = album, basename = it.name))
        }
    }

    suspend fun setTags(item: MediaItem, add: List<Long>, remove: List<Long>) {
        val id = item.remoteFileId ?: return
        runOrQueue(Op.TAGS, OpPayload(fileId = id, add = add, remove = remove))
    }

    /**
     * Sets capture date/time. Server copies are edited with Memories (exiftool); phone-only copies
     * are edited in place with ExifInterface (the UI must have obtained write access first).
     */
    suspend fun setDateTime(items: List<MediaItem>, local: LocalDateTime) {
        val exifDate = local.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US))
        for (it in items) {
            it.remoteFileId?.let { id -> runOrQueue(Op.EXIF, OpPayload(fileId = id, raw = mapOf("AllDates" to exifDate))) }
            if (it.localUri != null && !it.isVideo) writeLocalExif(it) { e ->
                e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
                e.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDate)
                e.setAttribute(ExifInterface.TAG_DATETIME, exifDate)
            }
        }
        media.refreshLocal()
        if (items.any { it.isRemote }) media.requestRemoteSync()
    }

    suspend fun setLocation(items: List<MediaItem>, lat: Double, lon: Double) {
        val raw = mapOf(
            "GPSLatitude" to "%.6f".format(Locale.US, abs(lat)),
            "GPSLongitude" to "%.6f".format(Locale.US, abs(lon)),
            "GPSLatitudeRef" to if (lat >= 0) "N" else "S",
            "GPSLongitudeRef" to if (lon >= 0) "E" else "W",
            "GPSCoordinates" to "%.6f, %.6f".format(Locale.US, lat, lon),
        )
        for (it in items) {
            it.remoteFileId?.let { id ->
                // Keep the capture date: exiftool can reset it on files without a date tag.
                val extra = (if (media.serverReachable.value) runCatching { api.info(id, false) }.getOrNull() else null)
                    ?.takeIf { !it.hasDateTag && it.epoch != null }
                    ?.let { info ->
                        val d = java.time.Instant.ofEpochSecond(info.epoch!!).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                        mapOf("AllDates" to d.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)))
                    } ?: emptyMap()
                runOrQueue(Op.EXIF, OpPayload(fileId = id, raw = raw + extra))
            }
            if (it.localUri != null && !it.isVideo) writeLocalExif(it) { e -> e.setLatLong(lat, lon) }
        }
    }

    suspend fun setTitleDescription(item: MediaItem, title: String?, description: String?) {
        val id = item.remoteFileId ?: return
        val raw = buildMap {
            title?.let { put("Title", it) }
            description?.let { put("Description", it) }
        }
        if (raw.isNotEmpty()) runOrQueue(Op.EXIF, OpPayload(fileId = id, raw = raw))
    }

    private suspend fun writeLocalExif(item: MediaItem, edit: (ExifInterface) -> Unit) = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openFileDescriptor(item.localUri!!, "rw")?.use { pfd ->
                val e = ExifInterface(pfd.fileDescriptor)
                edit(e)
                e.saveAttributes()
            }
        }.onFailure { Log.w(TAG, "local exif write failed for ${item.name}", it) }
    }

    // --- execution --------------------------------------------------------------------------------

    /**
     * Runs a server change now, or queues it (retried when the network is back) if the server is
     * unreachable. When the last refresh already failed it is queued straight away, so the user
     * never waits for a connection timeout.
     */
    private suspend fun runOrQueue(type: String, p: OpPayload) {
        if (!media.serverReachable.value) {
            db.pendingOps().insert(PendingOp(type = type, payload = NcJson.encodeToString(OpPayload.serializer(), p)))
            schedulePending(context)
            return
        }
        try {
            execute(type, p)
        } catch (e: IOException) {
            if (e is HttpException && e.code in 400..499 && e.code != 408 && e.code != 429) {
                Log.w(TAG, "$type rejected by server: ${e.message}")
                throw e
            }
            db.pendingOps().insert(PendingOp(type = type, payload = NcJson.encodeToString(OpPayload.serializer(), p)))
            schedulePending(context)
        }
    }

    /** Runs one server operation. Throws on failure. */
    suspend fun execute(type: String, p: OpPayload) {
        when (type) {
            Op.FAVORITE -> dav.setFavorite(nc.pathOf(p.fileId), p.flag)
            Op.ARCHIVE -> api.archive(p.fileId, p.flag)
            Op.DELETE -> try { api.delete(p.fileId) } catch (e: HttpException) { if (e.code != 404) throw e }
            Op.ALBUM_ADD -> dav.addToAlbum(p.album!!, nc.pathOf(p.fileId))
            Op.ALBUM_REMOVE -> dav.removeFromAlbum(p.album!!, p.fileId, p.basename!!)
            Op.EXIF -> { api.setExif(p.fileId, p.raw); api.reindex(p.fileId) }
            Op.TAGS -> api.setTags(p.fileId, p.add, p.remove)
            else -> Log.w(TAG, "unknown op $type")
        }
    }

    companion object {
        private const val TAG = "Actions"

        fun schedulePending(context: Context) {
            val req = OneTimeWorkRequestBuilder<PendingOpWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("pending_ops", ExistingWorkPolicy.KEEP, req)
        }
    }
}
