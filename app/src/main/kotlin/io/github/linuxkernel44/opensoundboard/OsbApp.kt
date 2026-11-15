package io.github.linuxkernel44.opensoundboard

import android.app.Application
import io.github.linuxkernel44.opensoundboard.audio.Player
import io.github.linuxkernel44.opensoundboard.data.Library
import io.github.linuxkernel44.opensoundboard.data.Settings

class OsbApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Library.init(this)
        Player.init(this)
    }
}
