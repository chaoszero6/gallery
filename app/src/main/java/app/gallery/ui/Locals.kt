package app.gallery.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import app.gallery.AppContainer

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No container") }

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Thumbnail <-> viewer shared-element transition, keyed by media key. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedMedia(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val anim = LocalAnimScope.current ?: return this
    return with(shared) {
        this@sharedMedia.sharedElement(
            sharedContentState = rememberSharedContentState("media-$key"),
            animatedVisibilityScope = anim,
        )
    }
}

/**
 * True once the screen's enter transition has finished and while it is not exiting.
 * Surfaces that can't take part in the shared-element animation (video) wait for this.
 */
@Composable
fun transitionSettled(): Boolean {
    val anim = LocalAnimScope.current ?: return true
    val t = anim.transition
    return t.currentState == androidx.compose.animation.EnterExitState.Visible &&
        t.targetState == androidx.compose.animation.EnterExitState.Visible
}

/**
 * [sharedMedia] only for the item actually on screen. The viewer keeps neighbouring pages composed,
 * and linking those too made several thumbnails fly in and out together.
 */
@Composable
fun Modifier.sharedMediaIf(enabled: Boolean, key: String): Modifier = if (enabled) sharedMedia(key) else this

/** Key of the item the viewer is showing, so the grid underneath can scroll to it before the close animation. */
object ViewerFocus {
    var key by mutableStateOf<String?>(null)
}
