package io.github.linuxkernel44.opensoundboard.download

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A link shared to the app from YouTube or another app, waiting for the UI to pick it up. */
object Incoming {
    var url by mutableStateOf<String?>(null)

    fun from(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        Regex("https?://\\S+").find(text)?.value?.let { url = it }
    }
}
