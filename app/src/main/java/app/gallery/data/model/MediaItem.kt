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
