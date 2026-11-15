package app.gallery.ui

import androidx.navigation.NavController
import app.gallery.data.model.MediaItem
import app.gallery.data.model.MediaSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Arguments too rich for a route string live here, referenced by a short id. */
object NavArgs {
    private val map = ConcurrentHashMap<String, Any>()
    private val counter = AtomicInteger()
    fun put(v: Any): String = "a${counter.incrementAndGet()}".also { map[it] = v }
    @Suppress("UNCHECKED_CAST")
    fun <T> get(id: String?): T? = id?.let { map[it] as? T }
}

data class GridArgs(val title: String, val source: MediaSource, val subtitle: String? = null, val albumName: String? = null)
data class ViewerArgs(val items: List<MediaItem>, val startKey: String, val source: MediaSource? = null)

object Routes {
    const val HOME = "home"
    const val GRID = "grid/{id}"
    const val VIEWER = "viewer/{id}"
    const val SEARCH = "search"
    const val MAP = "map"
    const val TRASH = "trash"
    const val SETTINGS = "settings"
    const val NEXTCLOUD = "nextcloud"
    const val CLUSTERS = "clusters/{backend}"
    const val ALBUMS_ALL = "albums_all"
}

fun NavController.openGrid(args: GridArgs) = navigate("grid/${NavArgs.put(args)}")
fun NavController.openViewer(items: List<MediaItem>, start: MediaItem, source: MediaSource? = null) =
    navigate("viewer/${NavArgs.put(ViewerArgs(items, start.key, source))}")
fun NavController.openClusters(backend: String) = navigate("clusters/$backend")
