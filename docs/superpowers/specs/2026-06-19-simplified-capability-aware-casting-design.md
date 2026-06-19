# Simplified, capability-aware casting — design

Date: 2026-06-19
Status: Approved-by-default (user delegated decisions; review pending)

## Goal

Replace the confusing trio of manual toggles (Realtime transcoding / Copy audio /
Seekable HLS) with a single **Auto** behavior that:

1. **Direct-plays** when the receiver can handle both the video and audio streams.
2. **Transcodes only what's needed** (don't re-encode streams the receiver already
   supports — e.g. transcode video but copy the audio).
3. **Avoids the live stream** wherever possible (use seekable HLS VOD instead).
4. **Chooses the best settings itself**, while still letting the user override.

## Hardware constraints discovered (on-device, 2026-06-19)

Verified on a Xiaomi MiTV-AFMU1 (Default Media Receiver), consistent with the
first-gen Chromecast:

- **No real capability API.** The Cast SDK exposes only coarse receiver info
  (video-out, model, `hi_res_audio`), not a codec list. So proactive "can you play
  HEVC/E-AC-3?" probing isn't possible; capability is learned **reactively**.
- **Dolby can't go over HLS.** `CODECS="…,ec-3"` (or `ac-3`) makes the receiver answer
  `Invalid Request` → `IDLE/ERROR` right after the master playlist. The same receiver
  plays E-AC-3 fine via **direct play** and via the **progressive live** stream.
- Everything else over HLS fMP4 works: `avc1 + aac` plays and **seeks natively**
  (random-access into mid-stream segments, transcoded on demand).

## Decisions

### Routing (the core auto-decision)

Given the probed source (`primaryVideo`, `primaryAudio`) and learned receiver hints:

| Source video | Source audio | Plan |
|---|---|---|
| receiver-OK | receiver-OK | **Direct play** (no transcode) |
| needs transcode | HLS-friendly (AAC/MP3/Opus/Vorbis/FLAC) | **HLS**: transcode video, **copy audio** |
| needs transcode | needs transcode, non-Dolby (e.g. DTS) | **HLS**: transcode video + audio→AAC |
| receiver-OK | needs transcode, non-Dolby | **HLS**: copy video, transcode audio→AAC* |
| needs transcode | **Dolby** (E-AC-3/AC-3), keep 5.1 | **Live**: transcode video, copy audio (only live case) |
| receiver-OK | **Dolby**, receiver-OK | **Direct play** |

\* "copy video over HLS" (video passthrough) is a follow-up; until then this case
transcodes video too (correct, just not maximally efficient). Not needed for the
primary 10-bit-HEVC content, which always transcodes video.

### Why live only for Dolby+video-transcode (Option B)

The only case where "avoid live" and "keep 5.1 with video-only transcode" conflict.
Rejected alternatives: pre-transcoding the whole file to a seekable MP4 (Option A —
satisfies all goals but forces a long pre-roll and large disk use for multi-GB files);
downmixing Dolby to AAC for HLS (Option C — loses surround the user explicitly wants).
Live is proven, instant, and preserves 5.1; it's confined to this single case.

### Reactive capability learning

The SDK can't tell us support up front, so:
- Start from a conservative baseline (H.264/VP8/VP9/AV1 video; AAC/MP3/Opus/Vorbis/FLAC
  audio are "receiver-OK"; HEVC and 10-bit are treated as needing transcode by default).
- If a **direct play** errors on the receiver (`IDLE/ERROR` shortly after load),
  automatically re-cast as a transcode and **remember** "this device couldn't direct-play
  codec X" so future casts skip straight to transcoding.
- Persist learned hints per `deviceId` in SharedPreferences.

### UI simplification

- Default mode **Auto (recommended)** — no toggles needed; the routing above runs.
- An **Advanced** section (collapsed) keeps manual overrides for power users:
  force-transcode, force-direct, prefer-live, keep-original-audio. These map onto the
  same engine; they're escape hatches, not the primary path.
- Removing the old top-level toggles outright is deferred until the Auto path is
  verified on-device, to keep the change reversible.

## Implementation phases

1. **`StreamingDecision`** — pure, unit-tested module producing a `Plan`
   (path = DIRECT/HLS/LIVE, transcodeVideo, copyAudio, reason) from the probe +
   receiver hints. No Android deps.
2. **Wire** `checkCompatibilityAndCast` / `startTranscoding` to the plan, so copy-audio
   and HLS-vs-live are chosen automatically (no manual toggles required).
3. **Reactive fallback** — on receiver error after direct play, escalate to transcode and
   persist the learned hint.
4. **UI** — Auto default + Advanced overrides.
5. **Device verification** for each phase over ADB.

## Out of scope (for now)

- HLS video passthrough (copy supported video without re-encode).
- Full pre-transcode-to-file path (Option A).
- A real receiver capability handshake (no SDK support).
