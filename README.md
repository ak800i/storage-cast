# StorageCast

An Android app that casts videos from your device's local storage to Google Cast compatible devices (Chromecast, Android TV, etc.) with embedded subtitle support.

## Features

- Browse videos stored on your Android device in a hierarchical folder view
- Search for videos by name
- Cast videos to any Google Cast compatible device on the same network
- Seek during playback — seek bar with current position and duration
- Detect and extract embedded subtitle tracks from video files
- Load external subtitle files from local storage
- Cast subtitles (converted to WebVTT) alongside video
- Automatic on-device transcoding for videos whose codecs aren't supported by the
  Cast device — including 10-bit HEVC/AVC (Main 10 / High 10), which is detected even
  when the container omits profile metadata (recovered from the SPS)
- Seekable transcoded playback via HLS VOD (enabled by default), so you can seek
  freely in transcoded streams instead of being limited to a live pipe
- Optional "copy audio" mode that transcodes only the video and passes the original
  audio through untouched, preserving 5.1 surround (AC-3 / E-AC-3)
- Built-in HTTP server to stream media (and HLS segments) to Cast devices
- Video thumbnails, duration, and file size display
- Material Design UI

## Usage

Download the latest APK from the [Releases](../../releases) tab and install it on your Android device.

## Architecture

- **MediaStore API** — discovers videos on the device
- **Google Cast SDK** — discovers Cast devices and controls playback
- **NanoHTTPD** — serves video, subtitles, and HLS segments over HTTP to the Cast device
- **MediaExtractor** — probes video files for codecs, profiles, and embedded subtitle
  tracks (extracted as WebVTT)
- **MediaCodec** — surface-based decode→encode transcoding pipeline (with 10-bit→SDR
  tone-mapping), assembled into fragmented-MP4 segments for an HLS VOD playlist

## Building

### Prerequisites

- JDK 17
- Android SDK with API 35

### Build debug APK

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Run unit tests

```bash
./gradlew testDebugUnitTest
```

The pure-JVM unit tests cover the byte-level transcoding format logic (fMP4/CMAF box
layout, Dolby AC-3/E-AC-3 config, HLS playlist generation, segment tiling, SPS profile
parsing, and the cast-vs-transcode compatibility gate).

## CI

Two GitHub Actions workflows handle CI:

- **PR Build** (`.github/workflows/pr-build.yml`) — runs the unit tests and builds a debug APK on every pull request to `main`, uploading the APK as an artifact.
- **Release** (`.github/workflows/release.yml`) — runs the unit tests, builds a signed release APK, and publishes a GitHub Release via manual workflow dispatch.

Both workflows share a composite action (`.github/actions/setup-build`) for common build environment setup (JDK 17, Gradle, gradlew permissions).

## License

This project is licensed under the GNU General Public License v3.0 — see the [LICENSE](LICENSE) file for details.