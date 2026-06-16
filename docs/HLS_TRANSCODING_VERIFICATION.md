# HLS Transcoding — Verification Checklist

This is the manual, on-device verification for the HLS VOD transcoding path. The
byte-level format logic (ISO-BMFF boxes, Dolby `dac3`/`dec3`, playlist generation,
segment tiling) is covered by the JVM unit tests; the items below are the parts that
can only be confirmed against a real Chromecast.

## Automated checks (run before flashing)

```powershell
$env:ANDROID_HOME="<sdk>"; $env:ANDROID_SDK_ROOT="<sdk>"
.\gradlew.bat assembleDebug testDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL`, all unit tests pass (fMP4 layout, Dolby config,
playlist generation, segment-tiling math). CI runs the same tests on every PR and
on the release build.

## Settings

- [ ] **Settings / video overflow menu → "Seekable HLS transcoding"** is ON (this is the
      default; turn it off only to test the live seek-by-restart fallback).
- [ ] **Settings → "Copy audio (preserve surround)"** — test both states (see below).

## Core playback

- [ ] Cast an incompatible source (e.g. HEVC 10-bit MKV with E-AC-3 5.1). With HLS
      seeking ON it transcodes **automatically with no codec dialog** (10-bit HEVC/AVC
      is detected and routed to the transcoder); video starts within a few seconds and
      plays smoothly (no green/garbled frames, no stutter).
- [ ] With HLS seeking OFF, the same source still shows the codec dialog (Direct
      stream / Transcode / Cancel) — choosing Transcode plays it.
- [ ] An 8-bit H.264/AAC MP4 still casts directly (no transcode, no dialog).
- [ ] Resolution looks correct (≤ 1080p, no stretching). 4K and 21:9 sources are
      letterbox-correct and not upscaled.
- [ ] Audio is in sync with video and free of crackle/dropouts.

## Seeking (the previously-crashing path)

- [ ] Drag the seek bar forward to a far point — playback resumes at the target with
      **no receiver crash / app-disconnect**.
- [ ] Seek backward — resumes correctly.
- [ ] Use the 30s skip controls repeatedly — each lands on the right content.
- [ ] After a seek, audio and subtitles remain in sync with the picture.

## Copy-audio (5.1 surround)

- [ ] With **Copy audio ON**, cast the E-AC-3 5.1 source. A 5.1-capable setup reports
      Dolby/surround and all channels are present (not a stereo downmix).
- [ ] With **Copy audio OFF**, the same source plays as stereo AAC (downmixed) and
      stays in sync.

## Subtitles

- [ ] A source with an embedded text subtitle shows cues, correctly timed, from the
      start of playback.
- [ ] After seeking, subtitle cues remain aligned to the picture (no drift).
- [ ] A subtitle file containing a `STYLE` block still renders (styling applied, cues
      not swallowed).

## Lifecycle / stability

- [ ] Cast several different videos back-to-back in one session. Memory stays stable
      (stale HLS sessions are evicted — confirm in the log: `Evicted N stale HLS
      session(s)`), and each new cast starts cleanly.
- [ ] Stop casting and start again — no leftover/incorrect stream is served.

## Log spot-checks (Settings → Logs, or `adb logcat`)

- [ ] `Register HLS session: ... id=hls_...` appears when casting starts.
- [ ] `Built init segment (... bytes, video=true, audio=true)` appears.
- [ ] `Built segment N (... bytes, v=.. a=..)` appears as the receiver fetches segments
      (and again, on demand, around a seek target).
