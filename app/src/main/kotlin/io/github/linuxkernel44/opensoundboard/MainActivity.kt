package io.github.linuxkernel44.opensoundboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import android.content.Intent
import io.github.linuxkernel44.opensoundboard.download.Incoming
import io.github.linuxkernel44.opensoundboard.ui.OpenSoundboardApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (savedInstanceState == null) Incoming.from(intent)
        setContent { OpenSoundboardApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Incoming.from(intent)
    }
}
