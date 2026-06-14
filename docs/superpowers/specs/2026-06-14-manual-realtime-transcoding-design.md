# Manual Realtime Transcoding Toggle — Design

**Date:** 2026-06-14
**Status:** Approved (pending spec review)

## Summary

Add a user-controlled toggle that forces full realtime transcoding (H.264 video +
AAC audio, served as a live MKV stream) for the video the user casts. The toggle is
a single global, sticky setting surfaced as a control on the video detail screen —
it is not stored per video, so its state carries over to whatever video the user
opens next.

When the toggle is **off** (the default), playback behaves exactly as it does today:
the app probes the media, runs a compatibility check, and either direct-streams,
remuxes, or shows the codec-incompatibility dialog. When the toggle is **on**, the
app skips that decision and, for any successfully probed file, transcodes.

The transcoding engine, local streaming server, and `STREAM_TYPE_LIVE` Cast load
path already exist and are already exercised by `startTranscoding()` (today only
reachable through the "Transcode" button in the codec-incompatibility dialog). This
feature exposes a manual entry point to that same path and persists the user's
choice.

## Goals

- Provide an on-screen control (on the video detail screen) to force realtime
  transcoding. The control reflects and edits a single global, sticky flag — not a
  per-video state.
- Keep the default playback path unchanged when the toggle is off.
- Persist the toggle state across app restarts.
- Reuse the existing transcode/stream/cast plumbing with no new wire format or
  server endpoints.

## Non-Goals

- No "smart" passthrough mode (video copy + audio-only re-encode) for the manual
  toggle. The toggle always does a full transcode.
- No global Settings switch; the control lives on the video detail screen.
- No separate "Cast (Transcoded)" action; the normal Play button respects the
  toggle.
- No change to transcode quality/encoder settings (those remain the constants in
  `TranscodeStreamer`).

## User-Facing Behavior

- On the video detail screen, an overflow menu item **"Realtime transcoding"** is
  checkable.
- Tapping it flips the state, saves it, and shows a brief confirmation toast
  ("Realtime transcoding on/off").
- The checked state reflects the persisted value whenever the menu opens.
- Pressing **Play**:
  - Toggle **off** → current behavior (probe → compatibility check → direct /
    remux / dialog).
  - Toggle **on** → probe → full transcode → live MKV cast. The user's selected
    audio track is honored (already passed through `startTranscoding`).

Note: the toggle is evaluated at every entry into `checkCompatibilityAndCast`, not
only the Play button. In particular, changing the audio track mid-cast re-enters
`checkCompatibilityAndCast` (via `applyLiveAudioTrackChange`); with the toggle on,
that re-cast also transcodes, which keeps the stream consistent and honors the new
audio track.

## Architecture & Components

### 1. Persisted flag (`SettingsActivity`)

Follows the existing `filter_short_videos` pattern in the `storagecast_settings`
`SharedPreferences` store.

- Add companion constants: `KEY_REALTIME_TRANSCODE = "realtime_transcode"` and
  `DEFAULT_REALTIME_TRANSCODE = false`.
- Add static helpers on the companion:
  - `getRealtimeTranscode(context): Boolean`
  - `setRealtimeTranscode(context, enabled: Boolean)`
- No new widget is added to `activity_settings.xml` (the control lives in the
  detail screen menu, not Settings).

### 2. Menu control (`video_detail_menu.xml` + `VideoDetailActivity`)

- Add a checkable item to `video_detail_menu.xml`:
  - `android:id="@+id/action_realtime_transcode"`
  - `android:checkable="true"`
  - `android:title="@string/realtime_transcode"`
  - `app:showAsAction="never"`
- In `onPrepareOptionsMenu`, set the item's `isChecked` from
  `SettingsActivity.getRealtimeTranscode(this)`.
- In `onOptionsItemSelected`, handle `R.id.action_realtime_transcode`: compute the
  new value, persist via `SettingsActivity.setRealtimeTranscode(...)`, update
  `item.isChecked`, and show a confirmation toast.
- Add the string resources `realtime_transcode`, `realtime_transcode_on`, and
  `realtime_transcode_off` to `strings.xml`.

### 3. Playback branch (`VideoDetailActivity.checkCompatibilityAndCast`)

Insert an early branch immediately after the probe result is confirmed non-null and
cached, before the `castCompatibility.checkCompatibility(...)` call:

```
if (SettingsActivity.getRealtimeTranscode(this@VideoDetailActivity)) {
    AppLogger.info(TAG, "Realtime transcoding enabled, forcing transcode")
    startTranscoding(video, probeResult)
    return@launch
}
```

All other paths are untouched.

## Data Flow

Toggle **ON**:

```
Play → checkCompatibilityAndCast → probe (MediaProber)
     → [flag on] → startTranscoding(video, probeResult)
     → TranscodeStreamer.createTranscodeStream  (full re-encode → MKV)
     → castStreamingSource(video, "video/x-matroska")  (STREAM_TYPE_LIVE)
     → MediaServerService.registerStreamingSource → remoteMediaClient.load
```

Toggle **OFF** (unchanged):

```
Play → checkCompatibilityAndCast → probe
     → CastCompatibility.checkCompatibility
     → compatible ? directStreamOrRemux(video) : showCodecCompatibilityDialog(...)
```

## Error Handling

- If `mediaProber.probe(...)` returns `null`, the existing fallback runs first
  (`castVideo` direct stream) and returns before the toggle branch is reached, so
  there is no regression and no null `probeResult` is passed to `startTranscoding`.
- Transcode runtime failures already surface through the existing
  `TranscodeStreamer.ProgressListener.onError` callback, which shows a
  `transcode_failed` toast. No new error handling is required.

## Testing / Verification

This feature depends on hardware media codecs and a live Cast session, so automated
unit coverage is not practical. Verification is:

1. `./gradlew assembleDebug` builds cleanly.
2. Manual on-device check:
   - Toggle **off**: a compatible file direct-streams; an incompatible file shows
     the codec dialog (unchanged behavior).
   - Toggle **on**: any successfully probed file casts via the transcoded live MKV
     stream.
   - Toggle state survives an app restart (persisted in SharedPreferences).
   - Menu checkmark reflects the persisted state when reopened.

## Files Touched

- `app/src/main/java/com/storagecast/ui/SettingsActivity.kt` — flag constants +
  get/set helpers.
- `app/src/main/java/com/storagecast/ui/VideoDetailActivity.kt` — menu prepare,
  menu handling, early transcode branch.
- `app/src/main/res/menu/video_detail_menu.xml` — checkable menu item.
- `app/src/main/res/values/strings.xml` — new strings.
