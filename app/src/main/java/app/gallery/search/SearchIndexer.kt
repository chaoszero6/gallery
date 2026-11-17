package app.gallery.search

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    /**
     * [done] and [total] count the whole library (items indexed before this run included), so the
     * numbers keep climbing across runs instead of starting from 0 each time.
     */
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

    // Time spent per stage, for the log (helps see what limits indexing speed).
    private val tLoad = AtomicLong()
    private val tClip = AtomicLong()
    private val tLabels = AtomicLong()
    private val tOcr = AtomicLong()

    /**
     * Indexes everything in [all] that is not indexed yet, [PARALLEL] items at a time (downloading
     * previews and ML Kit overlap; the CLIP model itself is serialised). Every finished item is saved
     * straight away, so a stopped run loses nothing. Returns how many items were indexed.
     */
    suspend fun indexAll(all: List<MediaItem>, shouldStop: () -> Boolean = { false }): Int = coroutineScope {
        retryEmptyOnce()
        val have = db.search().indexedKeys(ClipModel.VERSION).toHashSet()
        val remote = settings.uiNow().searchIndexRemote
        val eligible = all.count { it.isLocal || remote }
        val todo = all.filter { it.key !in have && (it.isLocal || remote) }
        val before = eligible - todo.size
        if (todo.isEmpty()) {
            _status.value = Status(false, eligible, eligible)
            return@coroutineScope 0
        }
        val done = AtomicInteger()
        val t0 = SystemClock.elapsedRealtime()
        listOf(tLoad, tClip, tLabels, tOcr).forEach { it.set(0) }
        _status.value = Status(true, before, eligible)
        val queue = Channel<MediaItem>(PARALLEL * 2)
        launch {
            for (m in todo) { if (shouldStop()) break; queue.send(m) }
            queue.close()
        }
        List(PARALLEL) {
            launch(Dispatchers.Default) {
                for (item in queue) {
                    if (shouldStop()) break
                    try {
                        indexOne(item)
                    } catch (e: CancellationException) {
                        throw e // stopped: leave the item for next time
                    } catch (e: Exception) {
                        Log.w(TAG, "index ${item.name} failed: ${e.message}")
                        // Store an empty entry so a broken file is not retried forever.
                        db.search().upsert(SearchEntry(item.key, null, "", "", System.currentTimeMillis(), ClipModel.VERSION))
                    }
                    val d = done.incrementAndGet()
                    _status.value = Status(true, before + d, eligible, (SystemClock.elapsedRealtime() - t0) / d)
                    if (d % 500 == 0) logSpeed(d, t0)
                }
            }
        }.joinAll()
        val d = done.get()
        if (d > 0) logSpeed(d, t0)
        _status.value = Status(false, before + d, eligible, if (d > 0) (SystemClock.elapsedRealtime() - t0) / d else 0)
        d
    }

    /**
     * Builds before this one saved an empty entry for the item in progress when a run was stopped,
     * so those items never got indexed. Clear empty entries once so they are tried again
     * (files that really cannot be read just fail again and are skipped from then on).
     */
    private suspend fun retryEmptyOnce() {
        val prefs = context.getSharedPreferences("search_index", Context.MODE_PRIVATE)
        if (prefs.getBoolean("retried_empty_v1", false)) return
        val n = db.search().deleteEmpty()
        prefs.edit().putBoolean("retried_empty_v1", true).apply()
        if (n > 0) Log.i(TAG, "cleared $n empty index entries to retry them")
    }

    private fun logSpeed(d: Int, t0: Long) {
        val ms = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "indexed $d items, ${ms / d} ms/item (wall, $PARALLEL at a time); per item: load ${tLoad.get() / d} ms, " +
            "clip ${tClip.get() / d} ms (${clip.imageAccelerator?.label}), labels ${tLabels.get() / d} ms, text ${tOcr.get() / d} ms")
    }

    private inline fun <T> timed(acc: AtomicLong, block: () -> T): T {
        val t = SystemClock.elapsedRealtime()
        try { return block() } finally { acc.addAndGet(SystemClock.elapsedRealtime() - t) }
    }

    /** Like runCatching().getOrDefault(), but a stopped run still stops (and saves nothing for the item). */
    private inline fun <T> softly(default: T, block: () -> T): T =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { default }

    private suspend fun indexOne(item: MediaItem) {
        val bmp = timed(tLoad) { loadBitmap(item) } ?: throw IllegalStateException("no image")
        try {
            val emb = if (clip.available) timed(tClip) { clip.embedImage(bmp) } else null
            val input = InputImage.fromBitmap(bmp, 0)
            val labels = timed(tLabels) { softly(emptyList()) { labeler.process(input).await() } }
                .joinToString("|") { it.text.lowercase() }
            val text = if (item.isVideo) "" else timed(tOcr) { softly("") { ocr.process(input).await().text } }
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

    companion object {
        private const val TAG = "SearchIndexer"
        /** Items in flight at once. */
        const val PARALLEL = 4
    }
}
