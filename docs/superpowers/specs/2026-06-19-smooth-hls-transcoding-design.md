# Smooth HLS transcoding — sequential pipeline + principled downscaling

Date: 2026-06-19
Status: Draft for review (rev 3 — incorporates spec-review findings)

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
- A from-scratch audio re-architecture — the pipeline **reuses the live path's** audio
  decode→AAC / passthrough semantics (see Components); it does not invent a new audio path.
- Refactoring the live path (`TranscodeStreamer` / `Fmp4Writer` / `HlsMp4Builder`) into a
  shared core. The codebase deliberately keeps the HLS path self-contained so it can never
  regress live streaming; the new pipeline is an **independent adaptation**, not a unified
  branchy module.

## Decisions captured from review

- **Direction A — sequential transcode pipeline** (decode once, cut into segments).
- **Downscaling = explicit quality setting + automatic measured fallback** (both).
- **Seeking required; an occasional longer wait on seek is acceptable.**
- **Time-to-first-frame up to ~15 s is acceptable** — enables a startup pre-buffer.

## Approach A — sequential transcode pipeline producing seekable HLS VOD

### Core idea

Run **one** long-lived decode→encode pipeline per playback run instead of a fresh transcode
per segment. The pipeline decodes the source sequentially from a base position; the encoder
is **asked to emit an IDR near each segment boundary** so the continuous encoded stream can
be **cut into independently-decodable HLS fMP4 segments**. Each frame is decoded exactly
once, codecs are created once per run, and — because one encoder runs at **one fixed
resolution for the whole session** — all segments share one SPS/PPS, which removes the
existing "segment avcC differs from init" corruption risk.

The pipeline is an **independent adaptation modeled on the proven live path**
(`TranscodeStreamer` + `Fmp4Writer`: single-threaded sequential decode→encode→fMP4 with the
non-blocking audio interleave, surface-based 10-bit tone-map, and clean `cancel()`/`finally`
teardown). It does **not** refactor those classes into a shared core — it reuses their
*patterns* and the existing `HlsMp4Builder` (fMP4 assembly) and `HlsTranscodeMath` (pure
helpers), keeping the live path untouched. Its two genuinely new pieces are (a) cutting the
output into segments at IDR boundaries and (b) restarting ("re-base") on a seek.

### The HLS VOD contract stays the same

The media playlist remains a fixed VOD list `seg0 … segN`, where segment `i` covers source
time `[i·6 s, (i+1)·6 s)` (nominal — see *Segment-boundary timing*) and ends with
`#EXT-X-ENDLIST`. The receiver seeks natively by requesting the segment for its target time.
What changes is **how** a requested segment is produced — by a running pipeline rather than an
isolated transcode.

### Components

- **`HlsSegmentPipeline` (new)** — owns the source extractors (video + audio), one video
  decoder + encoder (surface pipeline, 10-bit→8-bit tone-map as today) and the audio path. It
  runs on a single worker thread, decoding forward from `baseSegmentIndex`, and maintains a
  *production frontier* (the highest fully-built segment index), writing finished segments into
  a shared cache. Lifecycle: `start(baseIndex)`, `cancel()`.
- **Audio** — the pipeline reuses the live path's audio *patterns*, but constrained by the
  sender-side `StreamingDecision.Plan`. It copies the source audio **only** when the plan marks
  it HLS-copyable (AAC/MP3 mono/stereo); otherwise it decodes and (down)mixes to stereo AAC. It
  never passes Dolby / E-AC-3 or multichannel through on HLS — those are already routed to the
  live path or to AAC by `StreamingDecision`. The pipeline takes `Plan.copyAudio` as a fixed
  input, not a general copy-audio mode.
- **Segment cutting** — the encoder is configured with `KEY_I_FRAME_INTERVAL ≈ 6 s`;
  additionally, when an output frame's PTS crosses an `N·6 s` boundary the pipeline calls
  `encoder.setParameters(PARAMETER_KEY_REQUEST_SYNC_FRAME)` so the next encoded frame is an
  IDR. Encoded video + AAC samples accumulate per segment and, at each boundary, are assembled
  into an fMP4 segment via `HlsMp4Builder` and cached.
- **`HlsTranscodeSession` (modified)** — keeps the playlist/init/cache responsibilities but
  delegates production to an `HlsSegmentPipeline`. `segmentBytes(index)` coordinates with the
  pipeline (below) instead of building in isolation. `initBytes()` is captured once from the
  pipeline's encoder config at start.
- **`MediaServerService.serveHls` (unchanged interface)** — still serves
  `playlist.m3u8 / init.mp4 / segN.m4s`; `segmentBytes` now blocks on pipeline production or
  triggers a re-base.

### Segment-boundary timing (one authority: nominal 6 s)

The playlist is the single timing authority: each `#EXTINF` is nominal **6.000 s** and segment
`i` is the content cut for `[i·6 s, (i+1)·6 s)`. The encoder is *asked* for an IDR at each
boundary; the cut is taken at the **real IDR nearest the boundary**, which on a hardware
encoder lands within ≤ ~1 frame. The fMP4 `tfdt` anchors to each segment's first sample PTS
(absolute), so:

- per-segment length jitters by ≤ ~1 frame around 6 s — the receiver tolerates this `#EXTINF`
  approximation (well under its sub-second tolerance);
- the jitter is **non-cumulative** — every boundary targets the absolute `N·6 s`, so errors do
  not accumulate across the run;
- if a boundary IDR is unexpectedly far late (encoder ignored the hint), the cut **extends to
  the next IDR** — a slightly longer segment — rather than splitting mid-GOP. The
  `KEY_I_FRAME_INTERVAL ≈ 6 s` backstop makes this rare; it is a tested recovery path, not the
  normal case.

This keeps a fixed VOD list; no variable-boundary playlist or IDR-derived `#EXTINF` map is
introduced.

### Invariants

- **One init for the whole rendition.** One encoder at one fixed resolution per session ⇒ one
  SPS/PPS ⇒ one `init.mp4`; every segment decodes against it.
- **Re-base preserves the init or re-casts.** A seek re-base (below) builds a *fresh* encoder.
  Before serving the first re-based segment, the pipeline validates that the new encoder's init
  (avcC + audio config) is byte-identical to the committed `init.mp4`. For a same-resolution
  re-base this holds (deterministic config). If it ever diverges, the session does a **full
  re-cast** (new session id → new playlist/init → `load(...)`) rather than serve a segment that
  mismatches the published init. This turns the existing `avcConfigsMatch` warning into an
  enforced guard.
- **Absolute PTS across re-base.** Segment `i` always carries source-absolute PTS for
  `[i·6 s, …)` regardless of the pipeline's base, so a re-based pipeline produces
  byte-compatible segments at the same indices.

### Production & request coordination (`segmentBytes(index)`)

The pipeline runs ahead of playback but is bounded so it neither starves nor races far ahead.
Two independent knobs:

- **`LEAD`** — how far ahead of the receiver's most-recent request (`lastRequestedIndex`) the
  pipeline may produce (a back-pressure cap). Sized **≥ the receiver's read-ahead depth** so the
  receiver's normal buffering requests always fall inside the produced / in-progress window.
- **`FAR_SEEK`** — a much larger jump threshold (`≫ LEAD`) that distinguishes a genuine seek
  from normal forward read-ahead.

```
segmentBytes(index):
    if cached(index): return it
    lastRequestedIndex = max(lastRequestedIndex, index)
    if genuineSeek(index):
        re-base: cancel pipeline, start a new one at baseIndex = index
    wait (fine-grained, bounded by the HTTP timeout) for production to reach index

genuineSeek(index) =
       index > frontier + FAR_SEEK                       # jumped far ahead of production
    or (index < frontier - BACK_BUFFER and not cached)   # jumped back below retained content
```

- **Normal forward playback / read-ahead** (`index` at or just past the frontier, within
  `FAR_SEEK`) never re-bases — it waits for the forward-running pipeline to reach it. The
  pipeline keeps producing up to `lastRequestedIndex + LEAD`, then back-pressures.
- **Backward seek within the retained window** (`index ∈ [frontier − BACK_BUFFER, frontier]`)
  is served from cache with **no** re-base. `BACK_BUFFER` is sized to a concrete rewind
  guarantee (~12 s ≈ 2 segments); larger values cost memory (each cached segment is several
  MB), so it is bounded.
- **Genuine seek** (far forward, or backward below the retained window) re-bases at the target
  and waits one pre-buffer fill.

This is the hysteresis the Risks section refers to: only a clear out-of-window jump re-bases;
steady playback, read-ahead, and short rewinds do not.

### Startup: prepare-before-load (uses the 15 s budget)

Resolution must be chosen and the first segments produced **before** the receiver starts
fetching, so this is an explicit `prepare()` phase on the sender, ahead of
`remoteMediaClient.load(...)`:

1. `castHls` computes `initialSegmentIndex = pendingSeekPositionMs / 6000` (the sender can load
   HLS at a non-zero start time).
2. `prepare(initialSegmentIndex)` runs **resolution selection** (the Auto fallback below) and
   builds `PREBUFFER` segments from `initialSegmentIndex`, committing the `init.mp4`.
3. Only then does `castHls` register the session and call `load(...)`, so the receiver's first
   `init.mp4` / `segN.m4s` requests are already satisfiable from cache.

Because the pipeline runs at > 1× real-time (one decode pass, no codec churn), it then stays
ahead and the cached lead grows, so steady-state playback does not underrun.

### Seek behavior

- Seek inside the cached/near-frontier window → native, instant (segment already available).
- Seek outside it → pipeline re-bases at the target segment; the user waits roughly one
  pre-buffer fill (a few seconds). Accepted per product decision.

### Why this fixes the root cause

- **No pre-roll re-decode** — sequential decode touches each frame once.
- **No per-segment codec init** — codecs live for the whole run.
- **One consistent SPS/PPS** for all segments (one encoder at one fixed resolution per
  session) — removes the avcC-divergence corruption risk and simplifies the shared init.

## Principled downscaling (both mechanisms)

Resolution reduction is never an arbitrary cap; it is either chosen or measured.

### 1. Explicit quality setting

A "Cast quality" setting: **Auto (default) / 1080p / 720p / 540p**. Plumbed into the encode
output size (`HlsTranscodeMath.outputSize` max dimensions). `Auto` lets the measured fallback
below choose; a manual choice fixes the resolution for the session and skips measurement.
Lives in Settings (or the Advanced submenu).

### 2. Automatic measured fallback — decided up front, not mid-stream

A mid-stream resolution change would change `init.mp4` and corrupt the single-init HLS
rendition, so in `Auto` the resolution is chosen **during the `prepare()` pre-buffer, before
`init.mp4`/playlist are served**. The pipeline measures the build ratio
(`wall_time / content_time`) and **bails early**: it checks the ratio after the *first* segment
at each candidate resolution, and if that segment already can't sustain a lead (ratio above
~0.85) it steps down one rung (1080p → 720p → 540p) without finishing a full pre-buffer at the
doomed resolution. So the common case (the first resolution sustains) stays within the ~15 s
budget, and each rejected rung adds only ~one segment of measurement, not a whole pre-buffer.
The session then commits to the chosen resolution — still automatic and measured, without
violating the one-init invariant. Capable devices keep full resolution; weak devices start at a
sustainable one.

**Decode floor.** Downscaling reduces *encode* (and surface-scale) cost; the decoder always
runs at the source resolution, so a genuinely *decode-bound* source (e.g. heavy 4K HEVC-10 on a
weak decoder) may not be rescued by stepping resolution down. If even the lowest rung can't
sustain, the device simply cannot transcode that source in real time: the session commits to
the lowest rung and accepts buffering, surfacing a brief informational message. (Such sources
are usually played directly by capable receivers and only reach the HLS path on a receiver that
can't — the unavoidable edge.) Resolution fallback therefore targets the common
**encode-bound** case; it is not promised to make every 4K source smooth.

A mid-stream collapse (e.g. sustained thermal throttling) is rare; because it would change
the init, it is handled as a **full re-cast** — a new HLS session id → new playlist → new
`init.mp4` → `remoteMediaClient.load(...)` (reusing the existing session-eviction path) —
never an in-place resolution change on the live rendition. A log line records each decision;
an optional brief toast can inform the user.

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

- **Seek-restart latency.** Mitigated by the pre-buffer fill being only a few seconds, by
  app-initiated seeks pre-empting the re-base, and by the product decision that occasional
  longer seeks are acceptable. A backward seek into recently played content is served from the
  retained-window cache without a re-base.
- **Pipeline liveness / cancellation.** A re-base must cleanly cancel the old pipeline
  (interrupt the worker, release codecs in `finally`) before starting a new one, mirroring the
  live path's teardown. Single-worker ownership avoids two hardware codec sessions at once.
- **Boundary-accurate IDRs.** `REQUEST_SYNC_FRAME` is a hint backed by the
  `KEY_I_FRAME_INTERVAL ≈ 6 s` fallback; segment length jitters ≤ ~1 frame and a far-late IDR
  extends the cut to the next IDR (see *Segment-boundary timing*). Covered by a late/missing-IDR
  recovery test.
- **Re-base init divergence.** A re-base builds a fresh encoder; before serving its first
  segment the pipeline validates the new init is byte-identical to the published `init.mp4`,
  else it does a full re-cast (see *Invariants*).
- **Decode-bound sources.** Resolution fallback cannot help a source whose *decode* (not
  encode) saturates the device; the session commits to the lowest rung and accepts buffering
  with a message (see *Principled downscaling*).
- **Re-base thrash.** Avoided by the retained-window cache + hysteresis (see *Production &
  request coordination*): only a clear out-of-window jump (forward `> FAR_SEEK`, or backward
  below `BACK_BUFFER`) re-bases; steady playback, read-ahead, and short rewinds do not.
- **A/V alignment at boundaries.** Audio and video are cut at the same source time; reuse the
  live path's A/V sync handling. Validate first-segment audio/video sample counts.
- **Regression surface.** The HLS path is opt-in/secondary to the proven live path; gate the
  rollout behind the existing decision logic and keep the live path untouched.

## Testing strategy

- **Pure-JVM unit tests** for the new pure pieces: segment-boundary cutting math (PTS →
  segment index, IDR-at-boundary selection, late/missing-IDR → extend-to-next-IDR recovery),
  the genuine-seek decision (`FAR_SEEK` / `BACK_BUFFER` / read-ahead within `LEAD`), the
  build-ratio fallback state machine (early-bail per candidate), re-base init-validate →
  recast decision, and quality → output-size mapping. (Consistent with the existing
  `HlsTranscodeMath` / `StreamingDecision` test style — no device or Robolectric.)
- **On-device verification** (Snapdragon "parrot" → Mi TV) with `Penguins` (1080p HEVC10 +
  AAC 7.1): confirm sustained `PLAYING` with no rebuffering over several minutes, capture the
  per-segment build ratio (< 1.0), and verify a forward seek + a backward seek both resume.
- **Stress**: a 4K Tigole HEVC source to confirm the automatic fallback **engages and improves
  throughput** (steps resolution down at startup, e.g. 1080p→720p) — verifying the fallback
  mechanism, not that every 4K source becomes perfectly smooth (see the decode floor).

## Implementation phases (for the later plan)

1. Build `HlsSegmentPipeline` — an **independent adaptation** of the live decode→encode loop
   (not a refactor of `TranscodeStreamer`/`Fmp4Writer`) that cuts IDR-aligned fMP4 segments
   via `HlsMp4Builder` and adopts the live path's robust audio semantics, constrained to
   `Plan.copyAudio`. Live path stays untouched.
2. Wire `HlsTranscodeSession` to the pipeline: production frontier, `lastRequestedIndex`,
   back-pressure (`LEAD` ≥ receiver read-ahead), retained-window cache, fine-grained waits,
   init capture.
3. Seek re-base: genuine-seek detection (`FAR_SEEK` forward / `BACK_BUFFER` backward) with
   app-seek pre-empt, plus the init-validate → full-recast guard.
4. Prepare-before-load: `castHls` computes `initialSegmentIndex` from `pendingSeekPositionMs`
   and runs `prepare()` (resolution select + `PREBUFFER`) before `remoteMediaClient.load(...)`.
5. Explicit quality setting → `HlsTranscodeMath.outputSize` plumbing.
6. Automatic measured fallback inside `prepare()` (build-ratio → resolution chosen before
   serving init, early-bail per candidate); rare mid-stream collapse → full re-cast.
7. Device verification + the 4K fallback stress test.

## Open questions for review

1. `PREBUFFER` / `LEAD` / `BACK_BUFFER` / `FAR_SEEK` segment counts (defaults ~3 / ~3 / ~2 /
   ~8, with `LEAD` ≥ the receiver's measured read-ahead and `FAR_SEEK ≫ LEAD`) and the fallback
   build-ratio threshold (~0.85) — tune during device verification, or fix now?
2. Quality-setting location — Settings screen vs the existing per-video **Advanced** overflow
   submenu?
3. Mid-stream collapse handling — is the full re-cast worth building now, or should a rare
   sustained collapse just tolerate buffering until the user re-casts (resolution is already
   chosen up front, so this should be uncommon)?
