# OpenSoundboard

An open-source, audiophile soundboard for Android with a Liquid Glass interface.

- **Lossless playback** of WAV, FLAC (up to 24-bit / 192 kHz) plus MP3, Ogg Vorbis and anything MediaCodec decodes (AAC, Opus, ...). Samples are kept as 32-bit float and only resampled (cubic interpolation) when the output rate differs from the file rate.
- **Low-latency output** through Oboe (AAudio, performance mode `LowLatency`), with exclusive mode and MMAP when the device allows it. Choose the output device (including USB DACs and audio interfaces) and the stream sample rate (native, 44.1, 48, 96, 192 kHz).
- **Pads** with a waveform that doubles as a seek bar (played part is colored; tap or drag to jump once the sound is playing, a first tap on a silent pad starts it from the beginning), re-tap to stop, and per-pad modes: one-shot, loop, hold. Per-sound volume and gain (-24 to +24 dB, with peak readout and a normalize button), editing (select a part with the two handles, then keep it or remove it, including a piece in the middle; the sound is re-rendered with a 5 ms crossfade at each join, pads and seek bars show the edited sound, and Reset trim restores the original; the file is never modified), categories and favorites. "One sound at a time" (on by default) makes a new sound cut the one that is playing; turn it off for polyphony.
- **Backgrounds**: five real photographs (aurora, mountain lake, misty forest, Milky Way, Pacific sunset), selectable in Settings. Appearance can follow the system or be forced to light or dark.
- **Gapless playback** (on by default): encoder delay and padding are removed from MP3 (LAME tag) and AAC/M4A files so loops, trims and seeks repeat with no gap and a sound has its exact original length. Loop points are sample-accurate, including when the file is resampled, and loops keep playing at the right pitch if the output device or rate changes.
- **Audio info**: file format, sample rate, bit depth, channels, bitrate, duration and size for every sound; live output route, sample format, burst/buffer size, latency and underruns; peak/RMS meters and a spectrum analyzer.
- **High-resolution recording**: unprocessed microphone or USB input, 44.1 to 192 kHz when supported, saved as 24-bit PCM or 32-bit float WAV.
- **Backup**: export and import your whole library as a zip. Share any sound with other apps: for a trimmed or boosted sound you choose between the original file and the edited version (24-bit WAV with trim, volume and gain applied).
- **Liquid Glass UI** built with [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (Jetpack Compose). Full refraction needs Android 13+; older versions (minimum Android 8) fall back to frosted translucency.
- **Material 3 mode**: one switch in Settings (Interface style) turns Liquid Glass off completely for plain Material 3 surfaces, navigation bar, buttons, chips, switches and sliders, with your system colors on Android 12+.
- **Liquid Glass settings**: opacity, blur, refraction, color vibrancy, edge highlight and chromatic dispersion, applied live to every glass surface, with a reset button.
- **Audio from a link**: paste a link (or share one from YouTube and other apps) to download its best audio stream untouched, with a preview, progress and cancel. [yt-dlp](https://github.com/yt-dlp/yt-dlp) runs first; if it fails it is updated and retried, then [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) takes over. Cut the audio into several pads with markers, silence detection or the video's chapters; each pad is a lossless FLAC decoded from the source. In Settings you can import your own cookies (`cookies.txt`, per site or global) and a YouTube PO token for private or age-restricted media (or let the app generate the PO token on the device, from your session when you imported YouTube cookies); they are stored encrypted with an Android Keystore key and never backed up.
- **In-app updates**: checks the GitHub releases, downloads the new APK with progress, verifies its SHA-256 and signing certificate, then installs it with the system installer. Optional automatic check at launch, "skip this version", and a banner when an update is available.
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
./gradlew assembleRelease -PkeystoreProperties=/path/to/keystore.properties -PosbAbis=arm64-v8a
```

The bundled yt-dlp, Python and ffmpeg add about 55 MB per ABI, so builds target `arm64-v8a` and `x86_64` by default (`-PosbAbis=arm64-v8a` for a phone-only release APK).

## Architecture

- `app/src/main/cpp`: native engine (Oboe stream, voice mixer, level metering, FFT) and decoders (dr_wav, dr_flac, dr_mp3, stb_vorbis).
- `audio/`: JNI bridge, MediaCodec fallback decoder, player, recorder.
- `data/`: library (JSON index + files in app storage), settings, backup.
- `ui/`: Compose screens and the glass components adapted from the AndroidLiquidGlass catalog.

## License

OpenSoundboard's own source code is Apache-2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Since 0.12.0 the release APK bundles GPL-3.0 components (youtubedl-android and NewPipeExtractor for link downloads), so the APK as a whole is distributed under the GPL-3.0; this repository at the release tag is its complete source.
