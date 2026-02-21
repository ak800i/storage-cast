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
- On-device transcoding for videos with codecs not natively supported by Cast devices
- Built-in HTTP server to stream media to Cast devices
- Video thumbnails, duration, and file size display
- Material Design UI

## Usage

Download the latest APK from the [Releases](../../releases) tab and install it on your Android device.

## Architecture

- **MediaStore API** — discovers videos on the device
- **Google Cast SDK** — discovers Cast devices and controls playback
- **NanoHTTPD** — serves video and subtitle files over HTTP to the Cast device
- **MediaExtractor** — probes video files for embedded subtitle tracks and extracts them as WebVTT

## Building

### Prerequisites

- JDK 17
- Android SDK with API 34

### Build debug APK

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## CI

A GitHub Actions workflow (`.github/workflows/build.yml`) builds the debug APK on every push and pull request to `main` and uploads it as an artifact.

## License

This project is licensed under the GNU General Public License v3.0 — see the [LICENSE](LICENSE) file for details.