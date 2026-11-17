package app.gallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class BucketRow(val bucketId: Long, val bucketName: String, val count: Int, val coverId: Long, val coverUri: String, val latest: Long)

@Dao
interface LocalMediaDao {
    @Query("SELECT * FROM local_media ORDER BY takenAtMs DESC")
    fun observeAll(): Flow<List<LocalMedia>>

    @Query("SELECT * FROM local_media")
    suspend fun all(): List<LocalMedia>

    @Query("SELECT * FROM local_media WHERE id = :id")
    suspend fun byId(id: Long): LocalMedia?

    @Query("SELECT * FROM local_media WHERE bucketId IN (:buckets) ORDER BY takenAtMs DESC")
    suspend fun inBuckets(buckets: List<Long>): List<LocalMedia>

    @Query(
        """SELECT bucketId, bucketName, COUNT(*) AS count,
           (SELECT id FROM local_media l2 WHERE l2.bucketId = l.bucketId ORDER BY takenAtMs DESC LIMIT 1) AS coverId,
           (SELECT uri FROM local_media l2 WHERE l2.bucketId = l.bucketId ORDER BY takenAtMs DESC LIMIT 1) AS coverUri,
           MAX(takenAtMs) AS latest
           FROM local_media l GROUP BY bucketId ORDER BY latest DESC""",
    )
    fun observeBuckets(): Flow<List<BucketRow>>

    @Query("SELECT DISTINCT bucketId, bucketName, 0 AS count, 0 AS coverId, '' AS coverUri, 0 AS latest FROM local_media ORDER BY bucketName")
    suspend fun buckets(): List<BucketRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<LocalMedia>)

    @Query("DELETE FROM local_media WHERE id NOT IN (:keep)")
    suspend fun deleteNotIn(keep: List<Long>)

    @Query("DELETE FROM local_media")
    suspend fun clear()

    @Query("DELETE FROM local_media WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Transaction
    suspend fun replaceAll(items: List<LocalMedia>) {
        // SQLite has a bound-variable limit, so do the "keep" set in Kotlin.
        val existing = all().map { it.id }.toHashSet()
        val incoming = items.map { it.id }.toHashSet()
        val gone = existing.filterNot { it in incoming }
        gone.chunked(500).forEach { delete(it) }
        items.chunked(500).forEach { insertAll(it) }
    }
}

@Dao
interface RemoteMediaDao {
    @Query("SELECT * FROM remote_media ORDER BY epoch DESC")
    fun observeAll(): Flow<List<RemoteMedia>>

    @Query("SELECT * FROM remote_media")
    suspend fun all(): List<RemoteMedia>

    @Query("SELECT * FROM remote_media WHERE fileId = :id")
    suspend fun byId(id: Long): RemoteMedia?

    @Query("SELECT * FROM remote_media WHERE linkedLocalId = :localId LIMIT 1")
    suspend fun byLocal(localId: Long): RemoteMedia?

    @Upsert
    suspend fun upsert(items: List<RemoteMedia>)

    @Query("DELETE FROM remote_media WHERE dayId = :dayId AND fileId NOT IN (:keep)")
    suspend fun pruneDay(dayId: Int, keep: List<Long>)

    @Query("DELETE FROM remote_media WHERE dayId IN (:dayIds)")
    suspend fun deleteDays(dayIds: List<Int>)

    @Query("DELETE FROM remote_media WHERE fileId IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM remote_media")
    suspend fun clear()

    @Query("UPDATE remote_media SET isFavorite = :fav WHERE fileId = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE remote_media SET path = :path WHERE fileId = :id")
    suspend fun setPath(id: Long, path: String)

    @Query("UPDATE remote_media SET linkedLocalId = :localId WHERE fileId = :id")
    suspend fun link(id: Long, localId: Long?)

    @Query("UPDATE remote_media SET linkedLocalId = NULL")
    suspend fun clearLinks()

    @Transaction
    suspend fun replaceDay(dayId: Int, items: List<RemoteMedia>) {
        // Keep path and link info for rows that already existed.
        val old = items.mapNotNull { byId(it.fileId) }.associateBy { it.fileId }
        val merged = items.map { n ->
            old[n.fileId]?.let { o -> n.copy(path = o.path, linkedLocalId = o.linkedLocalId) } ?: n
        }
        pruneDay(dayId, items.map { it.fileId }.ifEmpty { listOf(-1L) })
        upsert(merged)
    }
}

@Dao
interface RemoteDayDao {
    @Query("SELECT * FROM remote_day")
    suspend fun all(): List<RemoteDay>

    @Upsert
    suspend fun upsert(days: List<RemoteDay>)

    @Query("DELETE FROM remote_day WHERE dayId IN (:ids)")
    suspend fun delete(ids: List<Int>)

    @Query("DELETE FROM remote_day")
    suspend fun clear()
}

@Dao
interface FavoriteDao {
    @Query("SELECT localId FROM local_favorite")
    fun observe(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(f: LocalFavorite)

    @Query("DELETE FROM local_favorite WHERE localId = :id")
    suspend fun remove(id: Long)
}

@Dao
interface UploadDao {
    @Query("SELECT * FROM upload_record")
    fun observeAll(): Flow<List<UploadRecord>>

    @Query("SELECT * FROM upload_record")
    suspend fun all(): List<UploadRecord>

    @Query("SELECT * FROM upload_record WHERE status IN ('queued','uploading') ORDER BY updatedAt")
    suspend fun pending(): List<UploadRecord>

    @Query("SELECT * FROM upload_record WHERE localId = :id")
    suspend fun byId(id: Long): UploadRecord?

    @Upsert
    suspend fun upsert(r: UploadRecord)

    @Upsert
    suspend fun upsertAll(r: List<UploadRecord>)

    @Query("UPDATE upload_record SET status = 'queued', attempts = 0, error = NULL WHERE status = 'failed'")
    suspend fun retryFailed()

    @Query("DELETE FROM upload_record")
    suspend fun clear()
}

@Dao
interface PendingOpDao {
    @Query("SELECT * FROM pending_op ORDER BY id")
    suspend fun all(): List<PendingOp>

    @Query("SELECT COUNT(*) FROM pending_op")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(op: PendingOp): Long

    @Query("DELETE FROM pending_op WHERE id = :id")
    suspend fun delete(id: Long)

    @Upsert
    suspend fun update(op: PendingOp)

    @Query("DELETE FROM pending_op")
    suspend fun clear()
}

@Dao
interface SearchDao {
    @Query("SELECT * FROM search_index WHERE modelVersion = :version")
    suspend fun all(version: Int): List<SearchEntry>

    @Query("SELECT key FROM search_index WHERE modelVersion = :version")
    suspend fun indexedKeys(version: Int): List<String>

    @Query("SELECT COUNT(*) FROM search_index WHERE modelVersion = :version")
    fun observeCount(version: Int): Flow<Int>

    @Query("SELECT labels FROM search_index WHERE labels != ''")
    suspend fun allLabels(): List<String>

    @Upsert
    suspend fun upsert(e: SearchEntry)

    @Query("DELETE FROM search_index WHERE key IN (:keys)")
    suspend fun delete(keys: List<String>)

    /** Entries with nothing in them (failed, or saved by a run that was stopped mid-item). */
    @Query("DELETE FROM search_index WHERE embedding IS NULL AND labels = '' AND ocrText = ''")
    suspend fun deleteEmpty(): Int

    @Query("SELECT * FROM recent_search ORDER BY at DESC LIMIT 10")
    fun observeRecent(): Flow<List<RecentSearch>>

    @Upsert
    suspend fun addRecent(r: RecentSearch)

    @Query("DELETE FROM recent_search WHERE `query` = :q")
    suspend fun deleteRecent(q: String)

    @Query("DELETE FROM recent_search")
    suspend fun clearRecent()
}
