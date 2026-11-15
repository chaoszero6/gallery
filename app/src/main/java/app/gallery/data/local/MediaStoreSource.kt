package app.gallery.data.local

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import app.gallery.data.db.LocalMedia
import app.gallery.data.model.DayIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/** A phone item in the system recycle bin (Android 11+). */
data class TrashedItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val durationMs: Long,
    val expiresSec: Long,
    val width: Int,
    val height: Int,
)

/** Reads photos and videos from MediaStore and wraps the system edit/trash requests. */
class MediaStoreSource(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver
    private val filesUri: Uri = MediaStore.Files.getContentUri("external")

    suspend fun scan(): List<LocalMedia> = withContext(Dispatchers.IO) {
        val cols = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.DISPLAY_NAME)
            add(MediaStore.Files.FileColumns.MIME_TYPE)
            add(MediaStore.Files.FileColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.Files.FileColumns.DATE_MODIFIED)
            add(MediaStore.Files.FileColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.DURATION)
            add(MediaStore.MediaColumns.BUCKET_ID)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.ORIENTATION)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.MediaColumns.IS_FAVORITE)
        }.toTypedArray()
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val out = ArrayList<LocalMedia>(8192)
        resolver.query(filesUri, cols, sel, null, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(cols[0])
            val iName = c.getColumnIndexOrThrow(cols[1])
            val iMime = c.getColumnIndexOrThrow(cols[2])
            val iSize = c.getColumnIndexOrThrow(cols[3])
            val iTaken = c.getColumnIndexOrThrow(cols[4])
            val iMod = c.getColumnIndexOrThrow(cols[5])
            val iAdded = c.getColumnIndexOrThrow(cols[6])
            val iW = c.getColumnIndexOrThrow(cols[7])
            val iH = c.getColumnIndexOrThrow(cols[8])
            val iDur = c.getColumnIndexOrThrow(cols[9])
            val iBucket = c.getColumnIndexOrThrow(cols[10])
            val iBucketName = c.getColumnIndexOrThrow(cols[11])
            val iOrient = c.getColumnIndexOrThrow(cols[12])
            val iType = c.getColumnIndexOrThrow(cols[13])
            val iRel = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
            val iFav = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE) else -1
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val isVideo = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val mod = c.getLong(iMod)
                var taken = c.getLong(iTaken)
                if (taken <= 0) taken = (mod.takeIf { it > 0 } ?: c.getLong(iAdded)) * 1000
                var w = c.getInt(iW)
                var h = c.getInt(iH)
                val o = c.getInt(iOrient)
                if (o == 90 || o == 270) { val t = w; w = h; h = t }
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += LocalMedia(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id).toString(),
                    name = c.getString(iName) ?: "",
                    mime = c.getString(iMime) ?: if (isVideo) "video/mp4" else "image/jpeg",
                    size = c.getLong(iSize),
                    takenAtMs = taken,
                    takenSec = taken / 1000,
                    modifiedSec = mod,
                    dayId = DayIds.fromUtcMillis(taken),
                    width = w,
                    height = h,
                    durationMs = if (isVideo) c.getLong(iDur) else 0,
                    bucketId = c.getLong(iBucket),
                    bucketName = c.getString(iBucketName) ?: "",
                    relativePath = if (iRel >= 0) c.getString(iRel) ?: "" else "",
                    isVideo = isVideo,
                    isFavorite = iFav >= 0 && c.getInt(iFav) == 1,
                )
            }
        }
        out
    }

    /** Emits whenever MediaStore changes. Callers should debounce. */
    fun changes(): Flow<Unit> = callbackFlow {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        resolver.registerContentObserver(filesUri, true, obs)
        awaitClose { resolver.unregisterContentObserver(obs) }
    }

    suspend fun trashed(): List<TrashedItem> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 30) return@withContext emptyList()
        val cols = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATE_EXPIRES, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
        )
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3)")
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_EXPIRES))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        val out = ArrayList<TrashedItem>()
        resolver.query(filesUri, cols, args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val isVideo = c.getInt(2) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += TrashedItem(id, ContentUris.withAppendedId(base, id), c.getString(1) ?: "", isVideo, c.getLong(3), c.getLong(4), c.getInt(5), c.getInt(6))
            }
        }
        out
    }

    /** True when the user granted "Media management" so trash/delete need no confirmation dialog. */
    fun canManageMedia(): Boolean = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(context)

    /** System request to move items to (or restore from) the recycle bin. Android 11+. */
    fun trashRequest(uris: List<Uri>, trash: Boolean): PendingIntent? =
        if (Build.VERSION.SDK_INT >= 30 && uris.isNotEmpty()) MediaStore.createTrashRequest(resolver, uris, trash) else null

    fun deleteRequest(uris: List<Uri>): PendingIntent? =
        if (Build.VERSION.SDK_INT >= 30 && uris.isNotEmpty()) MediaStore.createDeleteRequest(resolver, uris) else null

    fun writeRequest(uris: List<Uri>): PendingIntent? =
        if (Build.VERSION.SDK_INT >= 30 && uris.isNotEmpty()) MediaStore.createWriteRequest(resolver, uris) else null

    /** Android 8-10: delete directly (only works for files this app owns or with legacy storage). */
    suspend fun deleteDirect(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        uris.sumOf { runCatching { resolver.delete(it, null, null) }.getOrDefault(0) }
    }
}
