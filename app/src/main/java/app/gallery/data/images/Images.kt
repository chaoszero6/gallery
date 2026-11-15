package app.gallery.data.images

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import app.gallery.data.model.MediaItem
import app.gallery.data.nextcloud.MemoriesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/** A phone thumbnail, loaded through MediaStore's thumbnail cache (fast for grids). */
data class LocalThumb(val uri: Uri, val size: Int)

class LocalThumbFetcher(private val data: LocalThumb, private val context: Context) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(data.uri, Size(data.size, data.size), null)
        } else {
            val id = data.uri.lastPathSegment!!.toLong()
            val isVideo = data.uri.toString().contains("/video/")
            @Suppress("DEPRECATION")
            (if (isVideo) MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            else MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null))
                ?: throw IllegalStateException("No thumbnail")
        }
        ImageFetchResult(image = bmp.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val context: Context) : Fetcher.Factory<LocalThumb> {
        override fun create(data: LocalThumb, options: Options, imageLoader: ImageLoader) = LocalThumbFetcher(data, context)
    }
}

class LocalThumbKeyer : Keyer<LocalThumb> {
    override fun key(data: LocalThumb, options: Options) = "lt:${data.uri}:${data.size}"
}

/** Picks the right image source for an item at a given size. */
class ImageSources(private val api: MemoriesApi) {
    /** Grid thumbnail: phone thumbnail if there is a phone copy, else a Memories square preview. */
    fun thumb(item: MediaItem, sizePx: Int = 256): Any? = when {
        item.localUri != null -> LocalThumb(item.localUri, sizePx)
        item.remoteFileId != null -> api.previewUrl(item.remoteFileId, if (sizePx <= 256) 256 else 512, true, item.remoteEtag)
        else -> null
    }

    /** Memory-cache key the grid stores its small thumbnails under, so the viewer can show it at once. */
    fun thumbCacheKey(item: MediaItem) = "grid-thumb:${item.key}:${item.remoteEtag}"

    /** Uncropped preview (filmstrip, map sheet). */
    fun preview(item: MediaItem, sizePx: Int = 1024): Any? = when {
        item.localUri != null -> LocalThumb(item.localUri, sizePx)
        item.remoteFileId != null -> api.previewUrl(item.remoteFileId, sizePx, false, item.remoteEtag)
        else -> null
    }

    /** Full image for the viewer. */
    fun full(item: MediaItem): Any? = when {
        item.localUri != null -> item.localUri
        item.remoteFileId != null -> api.decodableUrl(item.remoteFileId, item.remoteEtag)
        else -> null
    }
}

fun buildImageLoader(context: Context, http: OkHttpClient): ImageLoader = ImageLoader.Builder(context)
    .components {
        add(LocalThumbFetcher.Factory(context))
        add(LocalThumbKeyer())
        add(OkHttpNetworkFetcherFactory(callFactory = { http }))
        add(VideoFrameDecoder.Factory())
    }
    .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
    .diskCache {
        DiskCache.Builder().directory(context.cacheDir.resolve("image_cache").toOkioPath()).maxSizeBytes(1024L * 1024 * 1024).build()
    }
    .crossfade(false)
    .build()
