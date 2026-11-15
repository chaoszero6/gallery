package app.gallery.backup

import android.content.ContentResolver
import android.net.Uri
import app.gallery.data.db.UploadDao
import app.gallery.data.db.UploadRecord
import app.gallery.data.nextcloud.DavClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Opens the bytes of a phone item. An interface so tests can supply in-memory data. */
fun interface ByteSource {
    fun open(): InputStream
}

fun ContentResolver.byteSource(uri: Uri) = ByteSource { openInputStream(uri) ?: throw IOException("Cannot open $uri") }

/**
 * Uploads one file to Nextcloud. Small files use a single PUT; large files use chunked upload v2,
 * which resumes from the last finished chunk if the app is killed (state lives in UploadRecord).
 */
class UploadEngine(
    private val dav: DavClient,
    private val uploads: UploadDao,
    private val chunkThreshold: Long = 20L * 1024 * 1024,
    private val chunkSize: Long = 10L * 1024 * 1024,
) {
    private val knownFolders = HashSet<String>()

    /** Returns the server file id, or null if the server did not report one. */
    suspend fun upload(
        record: UploadRecord,
        source: ByteSource,
        mime: String,
        mtimeSec: Long,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Long? {
        val path = record.remotePath
        dav.mkdirs(path.substringBeforeLast('/'), knownFolders)
        val total = record.size
        val id = if (total < chunkThreshold) {
            dav.put(path, streamBody(source, mime, 0, total) { onProgress(it, total) }, mtimeSec)
        } else {
            chunked(record, source, mime, mtimeSec, onProgress)
        }
        return id ?: runCatching { dav.fileId(path) }.getOrNull()
    }

    private suspend fun chunked(
        record: UploadRecord,
        source: ByteSource,
        mime: String,
        mtimeSec: Long,
        onProgress: (Long, Long) -> Unit,
    ): Long? {
        val total = record.size
        var rec = record
        val session = rec.chunkSession ?: ("gallery-" + UUID.randomUUID().toString()).also {
            rec = rec.copy(chunkSession = it, nextChunk = 1)
            uploads.upsert(rec)
        }
        dav.chunkStart(session, rec.remotePath)
        val chunks = ((total + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
        for (i in rec.nextChunk..chunks) {
            val offset = (i - 1) * chunkSize
            val len = minOf(chunkSize, total - offset)
            dav.chunkPut(session, i, rec.remotePath, total, streamBody(source, mime, offset, len) { onProgress(offset + it, total) })
            rec = rec.copy(nextChunk = i + 1, updatedAt = System.currentTimeMillis())
            uploads.upsert(rec)
        }
        val id = dav.chunkFinish(session, rec.remotePath, total, mtimeSec)
        uploads.upsert(rec.copy(chunkSession = null, nextChunk = 1))
        return id
    }

    private fun streamBody(source: ByteSource, mime: String, offset: Long, length: Long, progress: (Long) -> Unit) =
        object : RequestBody() {
            override fun contentType() = mime.toMediaTypeOrNull()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                source.open().use { input ->
                    var skipped = 0L
                    while (skipped < offset) {
                        val s = input.skip(offset - skipped)
                        if (s <= 0) throw IOException("Could not seek to $offset")
                        skipped += s
                    }
                    val src = input.source()
                    var sent = 0L
                    val buf = okio.Buffer()
                    while (sent < length) {
                        val r = src.read(buf, minOf(256 * 1024L, length - sent))
                        if (r == -1L) throw IOException("File shorter than expected")
                        sink.write(buf, r)
                        sent += r
                        progress(sent)
                    }
                }
            }
        }
}
