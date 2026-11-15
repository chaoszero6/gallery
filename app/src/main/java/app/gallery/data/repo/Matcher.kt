package app.gallery.data.repo

/**
 * Decides which phone item and which Nextcloud item are the same photo.
 *
 * The server keeps no content hash, so matching uses what both sides know:
 *  1. an upload record (we uploaded it and got the file id back),
 *  2. the same capture second and byte size,
 *  3. the same file name and byte size.
 * Each phone item and each server item is matched at most once.
 */
object Matcher {
    data class Local(val id: Long, val takenSec: Long, val size: Long, val name: String)
    data class Remote(val fileId: Long, val epoch: Long, val size: Long, val name: String)

    /** Returns server file id -> phone id. */
    fun match(
        locals: List<Local>,
        remotes: List<Remote>,
        uploaded: Map<Long, Long> = emptyMap(), // localId -> remoteFileId
    ): Map<Long, Long> {
        val result = HashMap<Long, Long>()
        val usedLocal = HashSet<Long>()
        val remoteIds = remotes.mapTo(HashSet()) { it.fileId }

        for ((localId, fileId) in uploaded) {
            if (fileId in remoteIds && fileId !in result && usedLocal.add(localId)) result[fileId] = localId
        }

        val byTime = HashMap<Pair<Long, Long>, MutableList<Local>>()
        val byName = HashMap<Pair<String, Long>, MutableList<Local>>()
        for (l in locals) {
            if (l.id in usedLocal) continue
            if (l.takenSec > 0 && l.size > 0) byTime.getOrPut(l.takenSec to l.size) { ArrayList(1) }.add(l)
            if (l.size > 0) byName.getOrPut(l.name.lowercase() to l.size) { ArrayList(1) }.add(l)
        }

        fun take(list: MutableList<Local>?): Local? {
            list ?: return null
            val it = list.iterator()
            while (it.hasNext()) {
                val l = it.next()
                it.remove()
                if (usedLocal.add(l.id)) return l
            }
            return null
        }

        val pending = remotes.filter { it.fileId !in result }
        val second = ArrayList<Remote>()
        for (r in pending) {
            val l = if (r.epoch > 0 && r.size > 0) take(byTime[r.epoch to r.size]) else null
            if (l != null) result[r.fileId] = l.id else second += r
        }
        for (r in second) {
            val l = if (r.size > 0) take(byName[r.name.lowercase() to r.size]) else null
            if (l != null) result[r.fileId] = l.id
        }
        return result
    }
}
