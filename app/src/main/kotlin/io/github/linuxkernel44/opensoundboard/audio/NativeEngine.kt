package io.github.linuxkernel44.opensoundboard.audio

import java.nio.ByteBuffer

/** JNI bridge to the Oboe based native mixer (see app/src/main/cpp/engine.cpp). */
object NativeEngine {
    init { System.loadLibrary("osbaudio") }

    external fun start(deviceId: Int, sampleRate: Int, exclusive: Boolean): Boolean
    external fun stop()
    /** Returns [channels, sampleRate, bitDepth, frames, trimmedDelay, trimmedPadding] or null when the format is not natively handled. */
    external fun loadFile(id: Int, path: String, gapless: Boolean): IntArray?
    external fun codecOf(id: Int): String
    external fun loadPcm(id: Int, buffer: ByteBuffer, frames: Int, channels: Int, sampleRate: Int, codec: String): Boolean
    external fun unload(id: Int)
    /** mode: 0 one-shot, 1 loop, 2 hold. */
    external fun play(id: Int, mode: Int, gain: Float, startFrac: Float, endFrac: Float)
    external fun stopSound(id: Int)
    external fun stopAll()
    /** Moves every voice of sound [id] to [fraction] (0..1) of the whole file, clamped to its trim range. */
    external fun seek(id: Int, fraction: Float)
    /** Playback position of the most recent voice of [id] as a fraction of the whole file. */
    external fun progress(id: Int): Float
    /** Largest absolute sample (linear, 1.0 = full scale) of sound [id] between two fractions of the file. */
    /** Writes the sound between two fractions of the file, multiplied by [gain], as a 24-bit WAV. */
    external fun exportWav(id: Int, startFraction: Float, endFraction: Float, gain: Float, path: String): Boolean
    external fun peakLevel(id: Int, startFraction: Float, endFraction: Float): Float
    external fun isPlaying(id: Int): Boolean
    external fun setMasterGain(gain: Float)
    /** [peakL, peakR, rmsL, rmsR] since the last call. */
    external fun levels(): FloatArray
    /** [rate, burst, bufferFrames, exclusive, format, deviceId, mmap, channels, latencyMs, xruns] */
    external fun streamInfo(): DoubleArray
    external fun spectrum(bands: Int): FloatArray
    external fun peaks(id: Int, buckets: Int): FloatArray?
}
