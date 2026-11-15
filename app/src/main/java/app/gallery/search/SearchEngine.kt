package app.gallery.search

import android.util.Log
import app.gallery.data.db.GalleryDb
import app.gallery.data.db.RecentSearch
import app.gallery.data.model.MediaItem
import app.gallery.data.nextcloud.Cluster
import app.gallery.data.nextcloud.MemoriesApi
import app.gallery.data.repo.MediaRepository
import app.gallery.data.repo.NextcloudRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

data class SearchResults(
    val query: String,
    val people: List<Cluster> = emptyList(),
    val places: List<Cluster> = emptyList(),
    val tags: List<Cluster> = emptyList(),
    val albums: List<Cluster> = emptyList(),
    val items: List<MediaItem> = emptyList(),
    val usedSmartSearch: Boolean = false,
)

/** Combines on-device CLIP similarity, labels, OCR, dates, names and the server's clusters. */
class SearchEngine(
    private val db: GalleryDb,
    private val media: MediaRepository,
    private val nc: NextcloudRepository,
    private val api: MemoriesApi,
    val indexer: SearchIndexer,
) {
    private class Index(val keys: Array<String>, val vecs: Array<FloatArray?>, val labels: Array<String>, val text: Array<String>)

    private var index: Index? = null
    private var indexSize = -1
    private val mutex = Mutex()

    val recent = db.search().observeRecent()

    private suspend fun loadIndex(): Index = mutex.withLock {
        val count = db.search().indexedKeys(ClipModel.VERSION).size
        index?.takeIf { count == indexSize }?.let { return it }
        val all = db.search().all(ClipModel.VERSION)
        val idx = Index(
            all.map { it.key }.toTypedArray(),
            all.map { e -> e.embedding?.let { ClipModel.fromBytes(it) } }.toTypedArray(),
            all.map { it.labels }.toTypedArray(),
            all.map { it.ocrText.lowercase() }.toTypedArray(),
        )
        index = idx; indexSize = count
        idx
    }

    suspend fun suggestions(max: Int = 8): List<String> = withContext(Dispatchers.Default) {
        val counts = HashMap<String, Int>()
        db.search().allLabels().forEach { l -> l.split('|').filter { it.isNotBlank() }.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        counts.entries.sortedByDescending { it.value }.take(max).map { e -> e.key.replaceFirstChar { it.titlecase() } }
    }

    suspend fun remember(query: String, thumbKey: String?) = db.search().addRecent(RecentSearch(query.trim(), System.currentTimeMillis(), thumbKey))
    suspend fun forget(query: String) = db.search().deleteRecent(query)
    suspend fun clearRecent() = db.search().clearRecent()

    suspend fun search(raw: String): SearchResults = withContext(Dispatchers.Default) {
        val q = raw.trim()
        if (q.isEmpty()) return@withContext SearchResults(q)
        val ql = q.lowercase()
        val snap = media.snapshot.value

        fun matches(c: Cluster) = c.name.isNotBlank() && c.name.lowercase().contains(ql)
        val clusters = nc.clusters.value
        val people = nc.people().filter(::matches)
        val places = clusters["places"].orEmpty().filter(::matches)
        val tags = clusters["tags"].orEmpty().filter(::matches)
        val albums = clusters["albums"].orEmpty().filter(::matches)

        val scores = HashMap<String, Float>()
        fun bump(key: String, s: Float) { scores[key] = maxOf(scores[key] ?: 0f, 0f) + s }

        // A date in the query ("beach 2023", "november 2024") is a hard filter; the other words rank within it.
        val range = DateQuery.parse(ql)
        val rest = if (range != null) DateQuery.strip(ql) else ql
        val inRange: (String) -> Boolean = { k -> range == null || snap.byKey[k]?.dayId?.let { it in range } == true }

        // 1) Smart search: CLIP text-image similarity. Keep only clear matches: close to the best
        // score and well above the typical score for this query.
        var smart = false
        val idx = loadIndex()
        if (rest.isNotBlank() && indexer.clip.available && idx.keys.isNotEmpty()) {
            runCatching {
                val tv = indexer.clip.embedText(rest)
                val sims = FloatArray(idx.keys.size) { i -> idx.vecs[i]?.let { dot(it, tv) } ?: Float.NaN }
                val valid = sims.filter { !it.isNaN() }
                if (valid.isNotEmpty()) {
                    val top = valid.max()
                    val mean = valid.average().toFloat()
                    val std = kotlin.math.sqrt(valid.sumOf { ((it - mean) * (it - mean)).toDouble() } / valid.size).toFloat()
                    val floor = maxOf(MIN_SIM, top - 0.04f, mean + 1.5f * std)
                    for (i in sims.indices) if (sims[i] >= floor && inRange(idx.keys[i])) scores[idx.keys[i]] = sims[i]
                }
                smart = true
            }.onFailure { Log.w(TAG, "clip search failed", it) }
        }

        // 2) Labels and text found in photos.
        val words = rest.split(Regex("\\s+")).filter { it.length >= 2 }
        for (i in idx.keys.indices) {
            if (!inRange(idx.keys[i]) || rest.isBlank()) continue
            val labelHit = idx.labels[i].isNotEmpty() && idx.labels[i].split('|').any { l -> l == rest || words.any { w -> l == w || l == w.removeSuffix("s") } }
            if (labelHit) bump(idx.keys[i], 0.15f)
            if (rest.length >= 3 && idx.text[i].contains(rest)) bump(idx.keys[i], 0.25f)
        }

        // 3) File names and folders; a date-only query lists that period, newest first.
        for (m in snap.items) {
            if (range != null && m.dayId !in range) continue
            if (rest.length >= 3 && (m.name.lowercase().contains(rest) || m.bucketName?.lowercase()?.contains(rest) == true)) bump(m.key, 0.3f)
            if (range != null && rest.isBlank()) scores[m.key] = 1f + m.takenAtMs / 1e13f
        }

        // 4) Server text search (Memories "Lens"), when the server has it.
        if (rest.isNotBlank() && nc.config.value?.lens == true) {
            runCatching { api.lensSearch(rest) }.getOrNull()?.forEach { r ->
                val item = snap.byRemoteId[r.fileid] ?: return@forEach
                if (inRange(item.key)) bump(item.key, 0.1f + r.score.toFloat() * 0.2f)
            }
        }

        val items = scores.entries.sortedByDescending { it.value }.take(MAX_RESULTS).mapNotNull { snap.byKey[it.key] }
        val seen = HashSet<String>()
        SearchResults(q, people, places, tags, albums, items.filter { seen.add(it.key) }, smart)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    companion object {
        private const val TAG = "SearchEngine"
        private const val MIN_SIM = 0.20f
        private const val MAX_RESULTS = 400
    }
}

/** Turns date words in a search ("2024", "nov 2023", "today") into a day-id range. */
object DateQuery {
    private val YEAR = Regex("\\b(19|20)\\d{2}\\b")
    private fun monthRegex(m: Month) = Regex("\\b(" + m.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase() + "|" +
        m.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase() + ")\\b")

    /** Recognises "2024", "nov 2023", "today", "yesterday" as day-id ranges (a month needs a year). */
    fun parse(q: String, today: LocalDate = LocalDate.now()): IntRange? {
        if (q == "today") return today.toEpochDay().toInt()..today.toEpochDay().toInt()
        if (q == "yesterday") today.minusDays(1).toEpochDay().toInt().let { return it..it }
        val year = YEAR.find(q)?.value?.toInt() ?: return null
        val month = Month.entries.firstOrNull { monthRegex(it).containsMatchIn(q) }
        return if (month != null) {
            val s = LocalDate.of(year, month, 1)
            s.toEpochDay().toInt()..s.plusMonths(1).minusDays(1).toEpochDay().toInt()
        } else LocalDate.of(year, 1, 1).toEpochDay().toInt()..LocalDate.of(year, 12, 31).toEpochDay().toInt()
    }

    /** The query without its date words ("beach in nov 2023" -> "beach"). */
    fun strip(q: String): String {
        if (q == "today" || q == "yesterday") return ""
        var s = YEAR.replace(q, " ")
        Month.entries.forEach { s = monthRegex(it).replace(s, " ") }
        return s.replace(Regex("\\b(in|from|of|on)\\b"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
