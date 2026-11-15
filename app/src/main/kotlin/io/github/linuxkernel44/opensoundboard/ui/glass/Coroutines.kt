package io.github.linuxkernel44.opensoundboard.ui.glass

import androidx.compose.runtime.withFrameNanos

suspend fun awaitFrame() { withFrameNanos { } }
