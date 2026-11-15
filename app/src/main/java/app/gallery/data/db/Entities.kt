package app.gallery.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A row from MediaStore (images and videos on the phone). */
@Entity(
    tableName = "local_media",
    indices = [Index("takenAtMs"), Index("bucketId"), Index(value = ["name", "size"]), Index(value = ["takenSec", "size"])],
)
data class LocalMedia(
    @PrimaryKey val id: Long,
    val uri: String,
    val name: String,
    val mime: String,
    val size: Long,
    val takenAtMs: Long,
    /** takenAtMs / 1000, kept as a column so matching can use an index. */
    val takenSec: Long,
    val modifiedSec: Long,
    val dayId: Int,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val bucketId: Long,
    val bucketName: String,
    val relativePath: String,
    val isVideo: Boolean,
    val isFavorite: Boolean,
)

/** A photo or video from the Memories timeline on Nextcloud. */
@Entity(
    tableName = "remote_media",
    indices = [Index("dayId"), Index(value = ["name", "size"]), Index(value = ["epoch", "size"]), Index("linkedLocalId")],
)
data class RemoteMedia(
    @PrimaryKey val fileId: Long,
    val dayId: Int,
    val epoch: Long,
    val size: Long,
    val name: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val etag: String,
    val isVideo: Boolean,
    val durationSec: Int,
    val liveId: String?,
    val isFavorite: Boolean,
    val path: String? = null,
    /** MediaStore id of the matching phone copy, set by the matching pass. */
    val linkedLocalId: Long? = null,
)

/** Days known from /api/days, with their photo count, so only changed days are refetched. */
@Entity(tableName = "remote_day")
data class RemoteDay(
    @PrimaryKey val dayId: Int,
    val count: Int,
    val loadedCount: Int,
)

/** Favorites for phone-only items (MediaStore favorites need a consent dialog for every change). */
@Entity(tableName = "local_favorite")
data class LocalFavorite(@PrimaryKey val localId: Long)

/** Backup state for one phone item. */
@Entity(tableName = "upload_record", indices = [Index("status")])
data class UploadRecord(
    @PrimaryKey val localId: Long,
    val status: String,
    val remotePath: String,
    val remoteFileId: Long? = null,
    val size: Long,
    val attempts: Int = 0,
    val error: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Chunked upload in progress: the upload folder name and the next chunk number. */
    val chunkSession: String? = null,
    val nextChunk: Int = 1,
) {
    companion object {
        const val QUEUED = "queued"
        const val UPLOADING = "uploading"
        const val DONE = "done"
        const val FAILED = "failed"
    }
}

/** A server change made while offline (or not yet confirmed), replayed by PendingOpWorker. */
@Entity(tableName = "pending_op")
data class PendingOp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String? = null,
)

/** Smart-search index entry for one item (key matches MediaItem.key). */
@Entity(tableName = "search_index")
data class SearchEntry(
    @PrimaryKey val key: String,
    val embedding: ByteArray?,
    val labels: String,
    val ocrText: String,
    val indexedAt: Long,
    val modelVersion: Int,
) {
    override fun equals(other: Any?) = other is SearchEntry && other.key == key && other.indexedAt == indexedAt
    override fun hashCode() = key.hashCode()
}

@Entity(tableName = "recent_search")
data class RecentSearch(
    @PrimaryKey val query: String,
    val at: Long,
    val thumbKey: String?,
)
