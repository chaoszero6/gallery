package app.gallery.ui.viewer

import android.net.Uri
import android.util.Log
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.sin
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
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
import app.gallery.ui.theme.Sym
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
    var buffering by mutableStateOf(false)
    var playWhenReady by mutableStateOf(false)
    /** Seek while the finger moves (phone files); streamed videos seek once on release. */
    var liveScrub = false

    /** The user asked for playback but no picture is moving yet: show the loader. */
    val loading: Boolean get() = playWhenReady && (buffering || !firstFrame)

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

    state.liveScrub = overrideUrl == null && item.localUri != null
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
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { state.playWhenReady = playWhenReady }
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) state.playWhenReady = false
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
            // Loader once loading has taken a moment (phone videos start before it would flash).
            var showLoader by remember { mutableStateOf(false) }
            LaunchedEffect(state.loading) {
                if (state.loading) { delay(250); showLoader = true } else showLoader = false
            }
            AnimatedVisibility(showControls || showLoader, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Color(0x66000000)).clickable { state.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(showLoader, label = "loader") { l ->
                        if (l) VideoLoader(Modifier.size(64.dp))
                        else Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.isPlaying) "Pause" else "Play", Modifier.size(40.dp), tint = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * Seek row shown in place of the filmstrip while a video is open: elapsed time, a wavy progress
 * line (it ripples while playing and lies flat when paused, like Android's media controls), the
 * duration and mute. Drag anywhere on the line to scrub; it thickens and shows a time bubble.
 */
@Composable
fun VideoSeekBar(state: VideoState, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val dur = state.durationMs.coerceAtLeast(1)
    val pos = if (dragging) (dragFraction * dur).toLong() else state.positionMs
    val timeStyle = Modifier.widthIn(min = 38.dp)
    val haptics = LocalHapticFeedback.current
    var lastLiveSeek by remember { mutableLongStateOf(0L) }
    // Floats on its own pill (like the action bar below it), so it stays readable over any video.
    Row(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp)
            .shadow(10.dp, RoundedCornerShape(30.dp), ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
            .clip(RoundedCornerShape(30.dp)).background(G.colors.pill)
            .height(52.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatTime(pos), timeStyle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = G.colors.text, style = Tabular)
        WavySeekLine(
            fraction = (pos.toFloat() / dur).coerceIn(0f, 1f),
            playing = state.isPlaying,
            dragging = dragging,
            timeLabel = formatTime(pos),
            onDragStart = { f ->
                dragging = true; dragFraction = f
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            },
            onDrag = { f ->
                dragFraction = f
                val now = SystemClock.uptimeMillis()
                if (state.liveScrub && now - lastLiveSeek > 120) { lastLiveSeek = now; state.player.seekTo((f * dur).toLong()) }
            },
            onDragEnd = { f -> state.seekTo((f * dur).toLong()); dragging = false; haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) },
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text(formatTime(state.durationMs), timeStyle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = G.colors.textSecondary, style = Tabular)
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { state.toggleMute() }, contentAlignment = Alignment.Center) {
            Icon(
                if (state.muted) Sym.VolumeOff else Sym.VolumeUp,
                if (state.muted) "Unmute" else "Mute", Modifier.size(22.dp), tint = G.colors.text,
            )
        }
    }
}

@Composable
private fun WavySeekLine(
    fraction: Float,
    playing: Boolean,
    dragging: Boolean,
    timeLabel: String,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = G.colors.text
    // The wave flattens while paused or scrubbing, and the line thickens under the finger.
    val amplitude by animateFloatAsState(if (playing && !dragging) 1f else 0f, tween(450), label = "amp")
    val thickness by animateDpAsState(if (dragging) 7.dp else 4.dp, label = "thick")
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { t ->
            if (last != 0L) phase = (phase + (t - last) / 1e9f * 2f * PI.toFloat() / 1.6f) % (2f * PI.toFloat())
            last = t
        }
    }
    var widthPx by remember { mutableIntStateOf(1) }
    val currentFraction by rememberUpdatedState(fraction)
    val density = LocalDensity.current
    Box(modifier.height(44.dp).onSizeChanged { widthPx = it.width.coerceAtLeast(1) }) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
                .pointerInput(Unit) {
                    fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                    detectTapGestures { o -> onDragStart(at(o.x)); onDragEnd(at(o.x)) }
                }
                .pointerInput(Unit) {
                    fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                    var f = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { o -> f = at(o.x); onDragStart(f) },
                        onHorizontalDrag = { change, _ -> change.consume(); f = at(change.position.x); onDrag(f) },
                        onDragEnd = { onDragEnd(f) },
                        onDragCancel = { onDragEnd(currentFraction) },
                    )
                },
        ) {
            val cy = size.height / 2
            val stroke = thickness.toPx()
            val x = size.width * fraction
            val waveLen = 22.dp.toPx()
            val amp = 3.dp.toPx() * amplitude
            // Played part: a sine wave that eases to flat just before the playhead.
            if (x > 0f) {
                val path = Path()
                val step = 2.dp.toPx()
                var px = 0f
                path.moveTo(0f, cy)
                while (px <= x) {
                    val taper = ((x - px) / (waveLen / 2)).coerceIn(0f, 1f)
                    path.lineTo(px, cy + amp * taper * sin(2f * PI.toFloat() * px / waveLen - phase))
                    px += step
                }
                path.lineTo(x, cy)
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            // Rest of the video: a quiet straight line.
            if (x < size.width) drawLine(color.copy(alpha = 0.22f), Offset(x + stroke, cy), Offset(size.width, cy), stroke, StrokeCap.Round)
            // Playhead: a small upright pill.
            val ph = (if (dragging) 22.dp else 16.dp).toPx()
            val pw = 4.dp.toPx()
            drawRoundRect(color, Offset(x - pw / 2, cy - ph / 2), Size(pw, ph), CornerRadius(pw / 2))
        }
        // Time bubble above the finger while scrubbing.
        AnimatedVisibility(dragging, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart)) {
            val bubbleW = 56.dp
            val offsetX = with(density) { (widthPx * fraction).toDp() } - bubbleW / 2
            Box(
                Modifier.offset(x = offsetX, y = (-34).dp).width(bubbleW).clip(CircleShape).background(G.colors.text),
                contentAlignment = Alignment.Center,
            ) {
                Text(timeLabel, Modifier.padding(vertical = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = G.colors.background, style = Tabular)
            }
        }
    }
}

/** Two arcs chasing each other round the centre button while a video loads. */
@Composable
private fun VideoLoader(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "loader")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    val sweep by t.animateFloat(30f, 250f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sweep")
    Canvas(modifier.padding(16.dp).semantics { contentDescription = "Loading video" }) {
        val st = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round)
        drawArc(Color.White.copy(alpha = 0.25f), 0f, 360f, false, style = st)
        drawArc(Color.White, spin, sweep, false, style = st)
    }
}

private val Tabular = TextStyle(fontFamily = app.gallery.ui.theme.AppFont, fontFeatureSettings = "tnum")

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, (s / 60) % 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}
