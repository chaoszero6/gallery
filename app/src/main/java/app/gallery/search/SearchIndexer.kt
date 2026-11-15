package app.gallery.search

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.util.Size
import app.gallery.data.db.GalleryDb
import app.gallery.data.db.SearchEntry
import app.gallery.data.images.ImageSources
import app.gallery.data.model.MediaItem
import app.gallery.data.settings.Settings
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Builds the smart-search index: CLIP embedding + ML Kit labels + text in the photo. */
class SearchIndexer(
    private val context: Context,
    private val db: GalleryDb,
    private val http: OkHttpClient,
    private val images: ImageSources,
    private val settings: Settings,
) {
    data class Status(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val msPerItem: Long = 0)

    val clip = ClipModel(context)
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.65f).build()) }
    private val ocr by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Items in [all] that have no index entry yet, newest first. */
    suspend fun pending(all: List<MediaItem>): List<MediaItem> {
        val have = db.search().indexedKeys(ClipModel.VERSION).toHashSet()
        val remote = settings.uiNow().searchIndexRemote
        return all.filter { it.key !in have && (it.isLocal || remote) }
    }

    /** Indexes up to [limit] items. Returns how many were indexed. */
    suspend fun indexBatch(all: List<MediaItem>, limit: Int, shouldStop: () -> Boolean = { false }): Int = withContext(Dispatchers.Default) {
        val todo = pending(all)
        val batch = todo.take(limit)
        var done = 0
        var totalMs = 0L
        _status.value = Status(true, 0, todo.size)
        for (item in batch) {
            if (shouldStop()) break
            val t0 = System.currentTimeMillis()
            runCatching { indexOne(item) }.onFailure {
                Log.w(TAG, "index ${item.name} failed: ${it.message}")
                // Store an empty entry so a broken file is not retried forever.
                db.search().upsert(SearchEntry(item.key, null, "", "", System.currentTimeMillis(), ClipModel.VERSION))
            }
            done++
            totalMs += System.currentTimeMillis() - t0
            _status.value = Status(true, done, todo.size, totalMs / done)
        }
        if (done > 0) Log.i(TAG, "indexed $done items, ${totalMs / done} ms/item")
        _status.value = Status(false, done, todo.size, if (done > 0) totalMs / done else 0)
        done
    }

    private suspend fun indexOne(item: MediaItem) {
        val bmp = loadBitmap(item) ?: throw IllegalStateException("no image")
        try {
            val emb = if (clip.available) clip.embedImage(bmp) else null
            val input = InputImage.fromBitmap(bmp, 0)
            val labels = runCatching { labeler.process(input).await() }.getOrDefault(emptyList())
                .joinToString("|") { it.text.lowercase() }
            val text = if (item.isVideo) "" else runCatching { ocr.process(input).await().text }.getOrDefault("")
                .replace(Regex("\\s+"), " ").take(2000)
            db.search().upsert(SearchEntry(item.key, emb?.let { ClipModel.toBytes(it) }, labels, text, System.currentTimeMillis(), ClipModel.VERSION))
        } finally {
            bmp.recycle()
        }
    }

    private suspend fun loadBitmap(item: MediaItem): Bitmap? = withContext(Dispatchers.IO) {
        if (item.localUri != null && Build.VERSION.SDK_INT >= 29) {
            return@withContext context.contentResolver.loadThumbnail(item.localUri, Size(512, 512), null)
        }
        val url = images.preview(item, 512) as? String ?: return@withContext null
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            BitmapFactory.decodeStream(r.body.byteStream())
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
    }

    companion object { private const val TAG = "SearchIndexer" }
}
