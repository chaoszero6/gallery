package app.gallery.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Battery optimisation pauses the app's background jobs (backup, search indexing) soon after the
 * app leaves the screen. The user can exempt the app with one system prompt.
 */
object BackgroundAccess {
    fun allowed(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Shows Android's "Let app always run in background?" prompt (falls back to the app's info page). */
    @SuppressLint("BatteryLife") // Sideloaded gallery with long-running backup and indexing; the user asked for it.
    fun request(context: Context) {
        val pkg = Uri.parse("package:${context.packageName}")
        try {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
        } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
    }
}

/** Whether the app may run in the background; read again each time the screen resumes. */
@Composable
fun rememberBackgroundAllowed(): Boolean {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(BackgroundAccess.allowed(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = BackgroundAccess.allowed(context)
        onPauseOrDispose { }
    }
    return allowed
}

/**
 * Asked before starting indexing while the app is still battery-optimised. Either button starts the
 * job ([onContinue]); "Allow" also opens the system prompt.
 */
@Composable
fun BackgroundAccessDialog(onDismiss: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep going when Gallery is closed?") },
        text = {
            Text(
                "Battery optimisation pauses Gallery soon after you leave it, so indexing (and Nextcloud " +
                    "backup) stop until you open the app again.\n\nAllow Gallery to run in the background? " +
                    "You can change this later in Settings › Permissions.",
            )
        },
        confirmButton = { TextButton({ BackgroundAccess.request(context); onContinue() }) { Text("Allow") } },
        dismissButton = { TextButton(onContinue) { Text("Not now") } },
    )
}
