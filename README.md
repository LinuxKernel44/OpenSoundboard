# OpenSoundboard

An open-source, audiophile soundboard for Android with a Liquid Glass interface.

- **Lossless playback** of WAV, FLAC (up to 24-bit / 192 kHz) plus MP3, Ogg Vorbis and anything MediaCodec decodes (AAC, Opus, ...). Samples are kept as 32-bit float and only resampled (cubic interpolation) when the output rate differs from the file rate.
- **Low-latency output** through Oboe (AAudio, performance mode `LowLatency`), with exclusive mode and MMAP when the device allows it. Choose the output device (including USB DACs and audio interfaces) and the stream sample rate (native, 44.1, 48, 96, 192 kHz).
- **Pads** with polyphony and per-pad modes: one-shot, loop, hold. Per-pad volume, trim (non-destructive), categories and favorites.
- **Audio info**: file format, sample rate, bit depth, channels, bitrate, duration and size for every sound; live output route, sample format, burst/buffer size, latency and underruns; peak/RMS meters and a spectrum analyzer.
- **High-resolution recording**: unprocessed microphone or USB input, 44.1 to 192 kHz when supported, saved as 24-bit PCM or 32-bit float WAV.
- **Backup**: export and import your whole library as a zip. Share any sound with other apps.
- **Liquid Glass UI** built with [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (Jetpack Compose). Full refraction needs Android 13+; older versions (minimum Android 8) fall back to frosted translucency.
- English (US) and French.

## Download

Signed APKs are attached to each [GitHub release](https://github.com/LinuxKernel44/OpenSoundboard/releases). Each release lists the SHA-256 of the APK and the SHA-256 fingerprint of the signing certificate, so you can check that updates come from the same key:

```
apksigner verify --print-certs OpenSoundboard-<version>.apk
```

## Build

Requirements: JDK 17, Android SDK (compileSdk 37), NDK 27 and CMake 3.22.

```
./gradlew assembleDebug
```

Release builds are signed locally (there is no CI). Put your keystore details in `keystore.properties` (ignored by git) or point to a file outside the repo:

```
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

```
./gradlew assembleRelease -PkeystoreProperties=/path/to/keystore.properties
```

## Architecture

- `app/src/main/cpp`: native engine (Oboe stream, voice mixer, level metering, FFT) and decoders (dr_wav, dr_flac, dr_mp3, stb_vorbis).
- `audio/`: JNI bridge, MediaCodec fallback decoder, player, recorder.
- `data/`: library (JSON index + files in app storage), settings, backup.
- `ui/`: Compose screens and the glass components adapted from the AndroidLiquidGlass catalog.

## License

Apache-2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
