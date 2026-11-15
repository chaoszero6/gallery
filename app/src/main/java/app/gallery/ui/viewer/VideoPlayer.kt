package app.gallery.ui.viewer

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import app.gallery.data.model.MediaItem
import app.gallery.ui.LocalContainer
import app.gallery.ui.theme.G
import kotlinx.coroutines.delay
import java.util.Locale

/** Playback state the viewer's bottom bar reads to draw the seek bar. */
@Stable
class VideoState(val player: Player) {
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var muted by mutableStateOf(false)
    var firstFrame by mutableStateOf(false)
    /** First frame drawn and laid out at the video's real size; until then the preview image shows. */
    var revealed by mutableStateOf(false)

    fun togglePlay() {
        if (player.isPlaying) player.pause()
        else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun toggleMute() { muted = !muted; player.volume = if (muted) 0f else 1f }
    fun seekTo(ms: Long) { positionMs = ms; player.seekTo(ms) }
}

/**
 * Plays a phone video directly, or a Nextcloud video through Memories' HLS transcoder
 * (falling back to the original file stream if transcoding is off or fails).
 *
 * Draws only the picture (TextureView, so it moves with the pager and the swipe-to-close gesture)
 * plus a centre play/pause button; the seek bar lives in the viewer's bottom chrome via [onState].
 * The page's preview image stays visible underneath until the first frame is rendered.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    item: MediaItem,
    playing: Boolean,
    showControls: Boolean,
    modifier: Modifier = Modifier,
    loop: Boolean = false,
    overrideUrl: String? = null,
    onState: (VideoState?) -> Unit = {},
    onTap: () -> Unit = {},
) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val config by c.nextcloud.config.collectAsState()
    val transcoding = config?.transcoding == true
    var useHls by remember(item.key, transcoding) { mutableStateOf(overrideUrl == null && item.localUri == null && transcoding) }
    val player = remember {
        val ds = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(c.http))
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(ds)).build()
    }
    val state = remember(player) { VideoState(player) }
    val reportState by rememberUpdatedState(onState)

    LaunchedEffect(item.key, useHls, overrideUrl) {
        val media = when {
            overrideUrl != null -> ExoItem.fromUri(overrideUrl)
            item.localUri != null -> ExoItem.fromUri(item.localUri)
            useHls -> ExoItem.Builder().setUri(Uri.parse(c.api.transcodeUrl(c.videoClientId, item.remoteFileId!!))).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            else -> ExoItem.fromUri(c.api.streamUrl(item.remoteFileId!!))
        }
        player.setMediaItem(media)
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.prepare()
    }
    LaunchedEffect(playing) { player.playWhenReady = playing; if (!playing) player.pause() }

    // The first frame can be drawn before the frame is resized to the video's aspect ratio, which
    // showed one stretched frame; wait two frames after it before revealing the video.
    LaunchedEffect(state.firstFrame) {
        if (state.firstFrame) { withFrameNanos { }; withFrameNanos { }; state.revealed = true }
    }

    // Position ticker for the seek bar.
    LaunchedEffect(state.isPlaying) {
        while (true) {
            state.positionMs = player.currentPosition.coerceAtLeast(0)
            if (!state.isPlaying) break
            delay(200)
        }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w("VideoPlayer", "playback error (hls=$useHls)", error)
                if (useHls) useHls = false // fall back to the original file
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { state.isPlaying = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                val d = player.duration
                if (d > 0) state.durationMs = d
                if (playbackState == Player.STATE_ENDED) state.positionMs = state.durationMs
            }
            override fun onRenderedFirstFrame() { state.firstFrame = true }
        }
        player.addListener(l)
        reportState(state)
        onDispose { reportState(null); player.removeListener(l); player.release() }
    }

    Box(modifier.clickable(remember { MutableInteractionSource() }, indication = null) { onTap() }, contentAlignment = Alignment.Center) {
        ContentFrame(
            player = player,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            contentScale = ContentScale.Fit,
            shutter = {}, // the preview image behind us acts as the shutter
            modifier = Modifier.fillMaxSize().alpha(if (state.revealed) 1f else 0f),
        )
        if (!loop) {
            AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Color(0x66000000)).clickable { state.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.isPlaying) "Pause" else "Play", Modifier.size(40.dp), tint = Color.White)
                }
            }
        }
    }
}

@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
/** Samsung-style seek row shown in place of the filmstrip while a video is playing. */
@Composable
fun VideoSeekBar(state: VideoState, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val dur = state.durationMs.coerceAtLeast(1)
    val pos = if (dragging) (dragValue * dur).toLong() else state.positionMs
    val timeStyle = Modifier.widthIn(min = 44.dp)
    val colors = SliderDefaults.colors(
        thumbColor = G.colors.text,
        activeTrackColor = G.colors.text,
        inactiveTrackColor = G.colors.text.copy(alpha = 0.22f),
    )
    Row(modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(pos), timeStyle, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = G.colors.text, style = Tabular)
        Slider(
            value = (pos.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { state.seekTo((dragValue * dur).toLong()); dragging = false },
            colors = colors,
            thumb = { Box(Modifier.size(14.dp).background(G.colors.text, CircleShape)) },
            track = {
                SliderDefaults.Track(
                    sliderState = it, colors = colors, modifier = Modifier.height(3.dp),
                    thumbTrackGapSize = 0.dp, drawStopIndicator = null,
                )
            },
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
        )
        Text(formatTime(state.durationMs), timeStyle, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = G.colors.textSecondary, style = Tabular)
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { state.toggleMute() }, contentAlignment = Alignment.Center) {
            Icon(
                if (state.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                if (state.muted) "Unmute" else "Mute", Modifier.size(22.dp), tint = G.colors.text,
            )
        }
    }
}

private val Tabular = TextStyle(fontFeatureSettings = "tnum")

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, (s / 60) % 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}
