package app.gallery.data.model

import android.net.Uri
import androidx.compose.runtime.Immutable

/**
 * One photo or video as the UI sees it. It can exist on the phone, on Nextcloud, or both.
 */
@Immutable
data class MediaItem(
    /** Stable key: "L:<mediastore id>" when a local copy exists, otherwise "R:<nextcloud file id>". */
    val key: String,
    val localId: Long?,
    val localUri: Uri?,
    val remoteFileId: Long?,
    val remoteEtag: String?,
    val remotePath: String?,
    val name: String,
    val mime: String,
    val size: Long,
    /** Capture instant, UTC epoch milliseconds. */
    val takenAtMs: Long,
    /** Capture date as days since epoch, using local wall-clock time (same as Memories' dayid). */
    val dayId: Int,
    val width: Int,
    val height: Int,
    val isVideo: Boolean,
    val durationMs: Long,
    val isFavorite: Boolean,
    val liveId: String?,
    val bucketId: Long?,
    val bucketName: String?,
    val relativePath: String?,
) {
    val isLocal: Boolean get() = localId != null
    val isRemote: Boolean get() = remoteFileId != null
    val isRemoteOnly: Boolean get() = localId == null && remoteFileId != null
    val isLocalOnly: Boolean get() = localId != null && remoteFileId == null
    val isGif: Boolean get() = mime == "image/gif"
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1f

    companion object {
        /**
         * Newest first, grouped by calendar day. Sorting by [takenAtMs] alone is not enough: [dayId]
         * comes from the camera's local time, so around midnight (or for photos taken in another
         * time zone) a day's items could be split by an item from a neighbouring day, which gave
         * the grid two headers for the same day (and a duplicate-key crash).
         */
        val TimelineOrder: Comparator<MediaItem> =
            compareByDescending<MediaItem> { it.dayId }.thenByDescending { it.takenAtMs }.thenByDescending { it.key }
    }
}

/** Where a grid's content comes from. Every grid screen in the app is one of these. */
sealed interface MediaSource {
    data object Timeline : MediaSource
    data object Recent : MediaSource
    data object Favorites : MediaSource
    data object Videos : MediaSource
    data class LocalBucket(val bucketId: Long, val name: String) : MediaSource
    data class RemoteAlbum(val owner: String, val name: String) : MediaSource
    data class Person(val backend: String, val user: String, val id: String, val name: String) : MediaSource
    data class Place(val osmId: Long, val name: String) : MediaSource
    data class Tag(val name: String) : MediaSource
    data class MapArea(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) : MediaSource
    data object Archive : MediaSource
    data class Items(val title: String, val keys: List<String>) : MediaSource
}
