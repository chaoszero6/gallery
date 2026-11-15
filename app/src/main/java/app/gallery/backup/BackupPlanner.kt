package app.gallery.backup

import java.time.Instant
import java.time.ZoneId

/**
 * Works out which phone items are already on Nextcloud and where missing ones should go.
 * Pure logic: no I/O, so it is unit tested with fixed inputs.
 */
object BackupPlanner {

    data class LocalFile(
        val id: Long,
        val name: String,
        val size: Long,
        val takenSec: Long,
        val bucketName: String,
    )

    /** A file found by listing the destination folders on the server. */
    data class DestFile(val path: String, val size: Long, val fileId: Long?)

    /** A photo from the Memories timeline (anywhere on the server). */
    data class IndexedFile(val fileId: Long, val epoch: Long, val size: Long, val name: String)

    data class Config(
        /** Server folder, e.g. "/Photos". No trailing slash. */
        val destRoot: String,
        val perBucketFolder: Boolean = true,
        val yearMonthFolders: Boolean = false,
        val zone: ZoneId = ZoneId.systemDefault(),
    )

    enum class Reason { UPLOAD_RECORD, SAME_NAME_IN_DEST, SAME_TIME_AND_SIZE, SAME_NAME_AND_SIZE }

    data class Present(val local: LocalFile, val reason: Reason, val remoteFileId: Long?)
    data class Missing(val local: LocalFile, val targetPath: String)

    data class Plan(val present: List<Present>, val missing: List<Missing>) {
        val missingBytes: Long get() = missing.sumOf { it.local.size }
    }

    fun folderFor(local: LocalFile, cfg: Config): String {
        val sb = StringBuilder(cfg.destRoot.trimEnd('/'))
        if (cfg.perBucketFolder) sb.append('/').append(sanitize(local.bucketName.ifBlank { "Phone" }))
        if (cfg.yearMonthFolders && local.takenSec > 0) {
            val d = Instant.ofEpochSecond(local.takenSec).atZone(cfg.zone)
            sb.append('/').append(d.year).append('/').append("%02d".format(d.monthValue))
        }
        return sb.toString()
    }

    fun plan(
        locals: List<LocalFile>,
        dest: List<DestFile>,
        index: List<IndexedFile>,
        /** localId -> remote file id, for items this app uploaded before. */
        uploaded: Map<Long, Long>,
        cfg: Config,
    ): Plan {
        val destByPath = dest.associateBy { it.path.lowercase() }
        val existingIds = HashSet<Long>().apply {
            dest.mapNotNullTo(this) { it.fileId }
            index.mapTo(this) { it.fileId }
        }
        val byTime = index.filter { it.epoch > 0 && it.size > 0 }.groupBy { it.epoch to it.size }
        val byName = index.filter { it.size > 0 }.groupBy { it.name.lowercase() to it.size }
        // Paths taken by planned uploads, so two phone files never get the same target.
        val taken = HashSet<String>(destByPath.keys)

        val present = ArrayList<Present>()
        val missing = ArrayList<Missing>()

        for (l in locals) {
            val rec = uploaded[l.id]
            if (rec != null && rec in existingIds) {
                present += Present(l, Reason.UPLOAD_RECORD, rec); continue
            }
            val folder = folderFor(l, cfg)
            val natural = "$folder/${l.name}"
            val d = destByPath[natural.lowercase()]
                ?.takeIf { it.size == l.size }
                ?: suffixedCopy(folder, l, destByPath)
            if (d != null) {
                present += Present(l, Reason.SAME_NAME_IN_DEST, d.fileId); continue
            }
            val t = if (l.takenSec > 0 && l.size > 0) byTime[l.takenSec to l.size]?.firstOrNull() else null
            if (t != null) {
                present += Present(l, Reason.SAME_TIME_AND_SIZE, t.fileId); continue
            }
            val n = if (l.size > 0) byName[l.name.lowercase() to l.size]?.firstOrNull() else null
            if (n != null) {
                present += Present(l, Reason.SAME_NAME_AND_SIZE, n.fileId); continue
            }
            val target = freePath(folder, l.name, taken)
            taken += target.lowercase()
            missing += Missing(l, target)
        }
        return Plan(present, missing)
    }

    /** "IMG.jpg" -> "IMG (1).jpg", "IMG (2).jpg", ... until the path is not taken. Never overwrites. */
    fun freePath(folder: String, name: String, taken: Set<String>): String {
        val first = "$folder/$name"
        if (first.lowercase() !in taken) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val p = "$folder/$base ($i)$ext"
            if (p.lowercase() !in taken) return p
            i++
        }
    }

    /** A copy uploaded earlier under a clash name ("IMG (1).jpg") with the same size. */
    private fun suffixedCopy(folder: String, l: LocalFile, destByPath: Map<String, DestFile>): DestFile? {
        val dot = l.name.lastIndexOf('.')
        val base = if (dot > 0) l.name.substring(0, dot) else l.name
        val ext = if (dot > 0) l.name.substring(dot) else ""
        for (i in 1..20) {
            val f = destByPath["$folder/$base ($i)$ext".lowercase()] ?: return null
            if (f.size == l.size) return f
        }
        return null
    }

    private fun sanitize(s: String) = s.replace('/', '_').replace('\\', '_').trim()
}
