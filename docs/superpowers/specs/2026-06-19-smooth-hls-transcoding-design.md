# Smooth HLS transcoding — sequential pipeline + principled downscaling

Date: 2026-06-19
Status: Draft for review

## Problem

Casting the HLS transcode path (10-bit HEVC / unsupported video → H.264 over seekable
HLS VOD) rebuffers every few seconds and, when pushed, freezes and skips frames. Observed
with `Penguins of Madagascar (1080p HEVC 10-bit + AAC 7.1)` on a Snapdragon "parrot" device
casting to the Default Media Receiver.

## Root cause (measured)

The HLS engine transcodes **each 6 s segment independently**: per segment it opens a fresh
`MediaExtractor`, `seekTo(startUs, SEEK_TO_PREVIOUS_SYNC)`, and creates a fresh
decoder+encoder ([HlsSegmentTranscoder.transcodeVideoRange]). Because segments are **uniform
6 s slices that are not aligned to source keyframes**, every segment must re-decode the
"pre-roll" from the previous keyframe up to its own start, then render only the frames in
`[startUs, endUs)`.

Evidence — the Penguins source keyframe (GOP) spacing in the first 60 s:

```
keyframe timestamps (s): 0.00, 1.08, 4.75, 15.18, 21.98, 30.49, 40.92, 51.34
GOP gaps (s):            1.08, 3.67, 10.43, 6.80, 8.51, 10.43, 10.43
avg GOP ≈ 7.3 s, max ≈ 10.4 s, frame rate 23.976 fps
```

Worked example — the segment covering `[12 s, 18 s)`:
`seekTo(12 s, PREVIOUS_SYNC)` lands on the keyframe at **4.75 s**, so the decoder must process
**4.75 → 18 s = 13.25 s** of frames to emit **6 s** of output. With an average GOP of ~7.3 s,
a random 6 s boundary re-decodes ~GOP/2 ≈ 3.6 s of throwaway frames, i.e. **~1.6× the decode
work** per segment, plus fresh codec init/teardown each time. This matches the observed
build-time spread (5–8 s of wall time for 6 s segments, varying with GOP position).

This is **architectural**, not a tuning problem: encoder-parameter tweaks and background
prefetch cannot recover from re-decoding ~60 % extra frames. (An experiment that added
prefetch + reduced keyframes confirmed this — it did not keep up and regressed playback.)

## Goals

1. Make HLS transcoding sustain real-time playback on mid-range hardware **without
   arbitrarily reducing quality**.
2. Decode each source frame **at most once**.
3. Preserve seekable HLS VOD (seeks may take a little longer, per product decision).
4. Make resolution reduction a **principled, opt-in or measured** action — never a silent cap.

## Non-goals

- Changing the direct-play or live (progressive-fMP4) paths.
- Supporting per-title adaptive bitrate ladders / multiple renditions.
- Audio re-architecture (existing AAC transcode / passthrough is reused as-is).

## Decisions captured from review

- **Direction A — sequential transcode pipeline** (decode once, cut into segments).
- **Downscaling = explicit quality setting + automatic measured fallback** (both).
- **Seeking required; an occasional longer wait on seek is acceptable.**
- **Time-to-first-frame up to ~15 s is acceptable** — enables a startup pre-buffer.

## Approach A — sequential transcode pipeline producing seekable HLS VOD

### Core idea

Run **one** long-lived decode→encode pipeline per playback run instead of a fresh transcode
per segment. The pipeline decodes the source sequentially from a base position; the encoder
is told to emit an IDR at each segment boundary so the continuous encoded stream can be
**cut into independently-decodable HLS fMP4 segments**. Each frame is decoded exactly once,
codecs are created once per run, and all segments share one SPS/PPS (one encoder), which also
removes the existing "segment avcC differs from init" corruption risk.

This is essentially the proven **live path** (`TranscodeStreamer` + `Fmp4Writer`: sequential
decode→encode→fMP4) with two additions: (a) cut the output into segments at IDR boundaries,
and (b) restart ("re-base") the pipeline on a seek. We reuse `HlsMp4Builder` for fMP4
assembly and `HlsTranscodeMath` for the pure helpers.

### The HLS VOD contract stays the same

The media playlist remains a fixed VOD list `seg0 … segN`, where segment `i` always covers
source time `[i·6 s, (i+1)·6 s)` and ends with `#EXT-X-ENDLIST`. The receiver seeks natively
by requesting the segment for its target time. What changes is **how** a requested segment is
produced — by a running pipeline rather than an isolated transcode.

### Components

- **`HlsSegmentPipeline` (new)** — owns the source extractors (video + audio), one video
  decoder + encoder (surface pipeline, 10-bit→8-bit tone-map as today) and the audio
  decode→AAC (or passthrough) path. It runs on a single worker thread, decoding forward from
  `baseSegmentIndex`. It maintains a *production frontier* (the highest segment index fully
  built) and writes finished segments into a shared cache. Lifecycle: `start(baseIndex)`,
  `cancel()`.
- **Segment cutting** — the encoder is configured with `KEY_I_FRAME_INTERVAL` ≈ segment
  duration; additionally, when an output frame's PTS crosses an `N·6 s` boundary the pipeline
  calls `encoder.setParameters(PARAMETER_KEY_REQUEST_SYNC_FRAME)` so the next encoded frame
  is a guaranteed IDR. Encoded video + AAC samples are accumulated per segment and, at each
  boundary, assembled into an fMP4 segment via `HlsMp4Builder` and cached.
- **`HlsTranscodeSession` (modified)** — keeps the playlist/init/cache responsibilities but
  delegates production to an `HlsSegmentPipeline`. `segmentBytes(index)` coordinates with the
  pipeline (see below) instead of building in isolation. `initBytes()` is captured from the
  first IDR's SPS/PPS.
- **`MediaServerService.serveHls` (unchanged interface)** — still serves
  `playlist.m3u8 / init.mp4 / segN.m4s`; `segmentBytes` now blocks on pipeline production or
  triggers a re-base.

### Request coordination (`segmentBytes(index)`)

```
if cached(index): return it
if index is within [frontier+1 .. frontier+LEAD] of the running pipeline:
    wait (with timeout) for the pipeline to reach it      # normal forward playback
else:
    # a seek landed outside the produced window (jump forward or backward)
    re-base: cancel pipeline, start a new pipeline at baseIndex = index
    wait for it to produce `index`
```

`LEAD` is the number of segments the pipeline may run ahead (the pre-buffer + steady-state
lead). A backward seek or a large forward jump re-bases; small forward drift just waits.

### Startup pre-buffer (uses the 15 s budget)

On load, the pipeline builds `PREBUFFER` segments (e.g. 3 ≈ 18 s of content) before the app
reports the media as ready / lets the receiver start. Because the pipeline now runs at
> 1× real-time (one decode pass, no codec churn), it then **stays ahead** and the cached lead
grows, so steady-state playback does not underrun.

### Seek behavior

- Seek inside the cached/near-frontier window → native, instant (segment already available).
- Seek outside it → pipeline re-bases at the target segment; the user waits roughly one
  pre-buffer fill (a few seconds). Accepted per product decision.

### Why this fixes the root cause

- **No pre-roll re-decode** — sequential decode touches each frame once.
- **No per-segment codec init** — codecs live for the whole run.
- **One consistent SPS/PPS** for all segments — removes the avcC-divergence corruption risk
  and simplifies the shared init segment.

## Principled downscaling (both mechanisms)

Resolution reduction is never an arbitrary cap; it is either chosen or measured.

### 1. Explicit quality setting

A "Cast quality" setting: **Auto (default) / 1080p / 720p / 540p**. Plumbed into the encode
output size (`HlsTranscodeMath.outputSize` max dimensions). `Auto` = start at the source
resolution capped at 1080p and rely on the automatic fallback below. A manual choice fixes
the cap and disables the automatic fallback. Lives in Settings (or the Advanced submenu).

### 2. Automatic measured fallback

The pipeline tracks a rolling **build ratio** = `wall_time_to_build_segment / segment_duration`
over the last few segments. If the ratio stays above a threshold (e.g. > ~0.85, meaning it
cannot maintain a lead) while in `Auto`, it steps the resolution down one rung
(1080p → 720p → 540p) and re-bases. This triggers **only on measured inability to keep up**,
so capable devices keep full resolution and weak devices/4K sources degrade gracefully
instead of buffering. A log line records each downshift; an optional brief toast can inform
the user.

## Alternatives considered

- **B — Keyframe-aligned variable-length segments.** Each segment spans exactly one source
  GOP, so `seekTo` lands on the segment's own keyframe (zero re-decode). Smaller change and
  keeps fully independent per-segment random access, but still pays fresh-codec init per
  segment, needs an up-front keyframe index, and produces variable (up to ~10 s+) segments —
  raising first-segment latency and per-segment memory, and struggling with very long GOPs.
  Rejected as the primary fix because it only removes one of the two costs and complicates
  the playlist; the pre-roll cost is better removed wholesale by A.
- **C — Prefetch / parallel codecs on the current per-segment model.** Smallest change, but
  it hides rather than removes the ~1.6× decode waste; it cannot sustain on hardware where a
  build already takes ≈ one segment duration. This is the rejected "quick fix" — it regressed
  in testing.
- **Arbitrary resolution cap.** Rejected by product direction: penalizes capable devices.

## Risks & mitigations

- **Seek-restart latency.** Mitigated by the pre-buffer fill being only a few seconds and the
  product decision that occasional longer seeks are acceptable. A backward seek into recently
  played content may still be cached (keep an LRU of built segments across a re-base where
  the timeline overlaps).
- **Pipeline liveness / cancellation.** A re-base must cleanly cancel the old pipeline
  (interrupt the worker, release codecs in `finally`) before starting a new one, mirroring the
  live path's teardown. Single-worker ownership avoids two hardware codec sessions at once.
- **Boundary-accurate IDRs.** If `REQUEST_SYNC_FRAME` is honored late, a segment could start
  on a non-IDR. Mitigation: verify the first sample of each cut is a keyframe; if not, extend
  the previous segment to the next IDR (variable but always seekable) and log.
- **A/V alignment at boundaries.** Audio and video are cut at the same source time; reuse the
  live path's A/V sync handling. Validate first-segment audio/video sample counts.
- **Regression surface.** The HLS path is opt-in/secondary to the proven live path; gate the
  rollout behind the existing decision logic and keep the live path untouched.

## Testing strategy

- **Pure-JVM unit tests** for the new pure pieces: segment-boundary cutting math (PTS →
  segment index, IDR-at-boundary selection), build-ratio fallback state machine, quality →
  output-size mapping. (Consistent with the existing `HlsTranscodeMath` / `StreamingDecision`
  test style — no device or Robolectric.)
- **On-device verification** (Snapdragon "parrot" → Mi TV) with `Penguins` (1080p HEVC10 +
  AAC 7.1): confirm sustained `PLAYING` with no rebuffering over several minutes, capture the
  per-segment build ratio (< 1.0), and verify a forward seek + a backward seek both resume.
- **Stress**: a 4K Tigole HEVC source to confirm the automatic fallback engages (1080p→720p)
  instead of buffering.

## Implementation phases (for the later plan)

1. Extract the reusable sequential decode→encode core (shared with / modeled on the live
   path) into a segment-cutting pipeline that emits fMP4 segments via `HlsMp4Builder`.
2. Wire `HlsTranscodeSession` to the pipeline (production frontier, request coordination,
   pre-buffer, init capture).
3. Seek re-base + cross-rebase segment LRU.
4. Explicit quality setting (Settings) → output-size plumbing.
5. Automatic measured fallback (build-ratio state machine + re-base at lower res).
6. Device verification + the 4K fallback stress test.

## Open questions for review

1. `PREBUFFER` / `LEAD` segment counts (default 3 / steady lead ~3) and the fallback build-ratio
   threshold (~0.85) — tune during device verification, or fix now?
2. Quality-setting location — Settings screen vs the existing per-video **Advanced** overflow
   submenu?
3. Should the automatic fallback ever step **back up** (e.g. it downshifted during a busy
   moment) or stay down for the rest of the session for stability?
