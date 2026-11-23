package com.rain.sdk.sample.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/*
 * How the sample handles a revealed secret (a recovery phrase or a private key) once the SDK has
 * returned it: a flagged, self-clearing clipboard copy, and a secure window while it is on screen.
 */

/** How long a copied secret stays on the clipboard before the sample clears it. */
internal const val CLIPBOARD_CLEAR_MS = 60_000L

/**
 * Copies a secret. On API 33 and above the clip is flagged sensitive, so the system's clipboard
 * preview masks it. Below that a toast says the value shows unmasked. After [clearAfterMs] the
 * clipboard is emptied whatever it holds by then. The clear runs on a main-looper handler over the
 * application context, so it survives the view model and the activity, and it is unconditional,
 * because `getPrimaryClip()` returns null for an unfocused app on API 29 and above and an "is it
 * still ours" check would skip exactly when the user has left. Clobbering a later copy is the
 * lesser harm.
 */
fun copySensitiveToClipboard(context: Context, label: String, text: String, clearAfterMs: Long = CLIPBOARD_CLEAR_MS) {
    val application = context.applicationContext
    val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    } else {
        Toast.makeText(application, "Copied. The clipboard shows it unmasked", Toast.LENGTH_SHORT).show()
    }
    clipboard.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({ clearClipboard(application) }, clearAfterMs)
}

/** Empties the clipboard. */
fun clearClipboard(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.clearPrimaryClip()
}

/**
 * Keeps `FLAG_SECURE` on the activity window while [active], so a revealed secret cannot be
 * screenshotted, recorded, or shown in the recents thumbnail. Window-wide, so the whole app is
 * blank in recents meanwhile. Accessibility services still read the text. A no-op outside an
 * activity, in previews for example.
 */
@Composable
fun SecureWindowWhile(active: Boolean) {
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(active, window) {
        if (active) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (active) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
