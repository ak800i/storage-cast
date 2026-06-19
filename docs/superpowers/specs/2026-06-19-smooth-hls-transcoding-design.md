# Smooth HLS transcoding — sequential pipeline + principled downscaling

Date: 2026-06-19
Status: Draft for review (rev 5 — incorporates spec-review findings)

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
  decode→AAC patterns (with constrained copy; see Components); it does not invent a new audio
  path.
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
- **Audio** — the pipeline reuses the live path's audio *patterns*. It treats `Plan.copyAudio`
  as **necessary but not sufficient**: it copies the source audio only when `copyAudio` is set
  *and* the codec is AAC mono/stereo (what `HlsMp4Builder` can mux — AAC sample entries);
  everything else — including MP3, for which `HlsMp4Builder` has no sample entry — is decoded and
  (down)mixed to stereo AAC. (This carries forward existing behavior: `passthroughAudioRange`
  already returns `null` for `audio/mpeg` and falls back to AAC transcode, even though
  `StreamingDecision` lists `audio/mpeg` as HLS-friendly.) It never passes Dolby / E-AC-3 or
  multichannel through on HLS. A unit test locks this contract.
- **Segment cutting** — the encoder is configured with `KEY_I_FRAME_INTERVAL ≈ 6 s`;
  additionally, when a *decoded* frame's PTS crosses an `N·6 s` boundary the pipeline calls
  `encoder.setParameters(PARAMETER_KEY_REQUEST_SYNC_FRAME)` **before rendering that frame to the
  encoder input surface**, so that frame is encoded as an IDR. Encoded video + AAC samples
  accumulate per segment and, at each boundary, are assembled into an fMP4 segment via
  `HlsMp4Builder` and cached.
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
  not accumulate across the run.

The `KEY_I_FRAME_INTERVAL ≈ 6 s` backstop means the encoder emits an IDR at the ~6 s cadence
*regardless* of the per-boundary hint, so a boundary IDR is always present within ≤ ~1 frame.
The design therefore keeps **one** timing authority — the fixed nominal 6 s playlist — and does
**not** introduce variable-length segments, a variable-boundary playlist, or an IDR-derived
`#EXTINF` map. A boundary IDR going genuinely missing would be an encoder fault, not a normal
case; it is treated as a **production failure** for that segment (surfaced as an error / re-cast),
never as a silently stretched segment that would mis-describe its `#EXTINF`. This keeps
`#EXTINF:6.000` and `#EXT-X-TARGETDURATION:6` honest.

### Invariants

- **One init for the whole rendition.** One encoder at one fixed resolution per session ⇒ one
  SPS/PPS ⇒ one `init.mp4`; every segment decodes against it.
- **The committed `init.mp4` stays authoritative for every segment.** Re-based segments and
  one-off builds use a *fresh* encoder configured **identically** to the committed init (same
  resolution / profile / level). The current per-segment HLS path already serves every segment
  from a fresh same-config encoder under one shared init and works, so same-config output is
  compatible in practice; the existing `avcConfigsMatch` check stays as a guard, and device
  verification confirms it for re-based and one-off segments. On a genuine mismatch the **only**
  correct recovery is a **full re-cast** (new session id → new init → `load(...)`) — a freshly
  encoded bitstream cannot be retro-fitted to a different published `avcC`. Full re-cast is
  otherwise reserved for an actual resolution change (the mid-stream collapse / quality-change
  case below), not routine seeks.
- **Absolute PTS across re-base.** Segment `i` always carries source-absolute PTS for
  `[i·6 s, …)` regardless of the pipeline's base, so a re-based pipeline produces
  byte-compatible segments at the same indices.

### Production & request coordination (`segmentBytes(index)`)

The hot pipeline produces forward from its base for the **steady-state** stream; anything it
can't satisfy from its producible window — a seek, a stray probe, or the first segments right
after a re-base — is served **immediately** by the retained `HlsSegmentTranscoder` (the existing
per-segment transcode, kept as the *one-off builder*). That path has the same bounded latency as
today's working-but-rebuffering path, so a seek never blocks longer than a single isolated
segment build, well inside the receiver's segment-fetch timeout. The pipeline then re-bases to
*follow* a sustained relocation and takes over subsequent requests. Two parameters:

- **`LEAD`** — the production run-ahead / back-pressure cap: the pipeline builds up to
  `lastRequestedIndex + LEAD`, then waits. Sized **≥ the receiver's read-ahead depth** (so normal
  buffering requests are always in-window) **and** large enough to hold a steady-state reserve
  against transient throughput dips (a heavy GOP, a thermal blip); its upper bound is memory
  (each cached segment is several MB).
- **`BACK_BUFFER`** — the retained window of already-built segments kept behind the playhead for
  short rewinds (~12 s ≈ 2 segments; same memory bound).

```
state: lastRequestedIndex (init = initialSegmentIndex), outOfWindowRun = 0

segmentBytes(index):
    if cached(index): return it                       # already in the producible window
    inWindow = (lastRequestedIndex - BACK_BUFFER) <= index <= (lastRequestedIndex + LEAD)
    if inWindow:
        outOfWindowRun = 0
        lastRequestedIndex = max(lastRequestedIndex, index)
        return waitForProduction(index)               # the pipeline is heading here

    # out of window: a seek or a stray probe — serve it NOW, don't yank the pipeline
    bytes = oneOffBuild(index)
    outOfWindowRun += 1
    if outOfWindowRun >= RELOCATE_AFTER:               # a sustained relocation, not a stray
        reBase(pipeline, baseIndex = index)
        lastRequestedIndex = index                    # reset the cursor to the new base
        outOfWindowRun = 0
    return bytes
```

- **Normal forward read-ahead / steady playback** (within `lastRequestedIndex + LEAD`) is served
  from cache or a short wait; the pipeline is already producing toward it.
- **Short backward rewind** (within `BACK_BUFFER`) is served from cache.
- **Seek** (forward or backward, out of window) is served immediately by the one-off builder; if
  out-of-window requests persist (`RELOCATE_AFTER` consecutive) the pipeline re-bases to the new
  location and **resets its cursor**, so post-seek playback near the new base is never mis-read as
  another discontinuity.
- **Stray / isolated probe** (e.g. a lone `seg0` duration probe while based far ahead) is served
  by the one-off builder and, never reaching `RELOCATE_AFTER`, does not move the base.

The pipeline is the steady-state optimizer; the one-off builder is the universal immediate-serve
path. (The receiver's actual request ordering — expected monotonic / playhead-local for fMP4 VOD,
including a non-zero start — is verified on device before the plan commits to this heuristic.)

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

Because the pipeline runs at > 1× real-time (one decode pass, no codec churn), it quickly fills
its `LEAD` reserve and **stays up to `LEAD` ahead** of the playhead, so steady-state playback
draws from that reserve and does not underrun.

### Seek behavior

- Seek inside the producible window → native, instant (served from cache or a short wait).
- Seek outside it → the target segment is served **immediately by the one-off builder** (bounded,
  ~one isolated segment build, inside the receiver's fetch timeout); the pipeline re-bases behind
  it on a sustained relocation and takes over subsequent segments. So a seek is never slower than
  today's per-segment path, and steady state returns once the re-based pipeline catches up.

### Why this fixes the root cause

- **No pre-roll re-decode** — sequential decode touches each frame once.
- **No per-segment codec init** — codecs live for the whole run.
- **One consistent SPS/PPS** for the steady-state stream (one encoder at one fixed resolution);
  re-based and one-off segments use the same config and the committed init stays authoritative
  (see *Invariants*) — removing the avcC-divergence corruption risk and simplifying the init.

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
`init.mp4`/playlist are served**. It measures the build ratio (`wall_time / content_time`) on
the first **steady-state** segment — the *second* produced segment — because only the first
produced segment after a base/seek pays the one-time `SEEK_TO_PREVIOUS_SYNC` pre-roll (the ~1.6×
decode the new architecture removes from steady state); measuring that pre-roll-inflated segment
would over-estimate cost and needlessly downscale capable devices (against Goals 1 & 4). It
**bails early**: if the steady-state segment can't sustain a lead (ratio above ~0.85) it steps
down one rung (1080p → 720p → 540p) without finishing a full pre-buffer at the doomed resolution.
So the common case (the first resolution sustains) stays within the ~15 s budget, and each
rejected rung adds only ~two segments of measurement, not a whole pre-buffer.

On a weak device that needs multiple step-downs, the *first* cast's `prepare()` can still exceed
the ~15 s budget (each rejected rung measures at sub-real-time speed); this is accepted, and the
implementation may remember the chosen resolution per device so repeat casts skip measurement and
start fast.
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

A mid-stream collapse (e.g. sustained thermal throttling) is rare; because changing resolution
would change the init, it is handled as a **full re-cast** — a new HLS session id → new playlist
→ new `init.mp4` → `remoteMediaClient.load(...)` (reusing the existing session-eviction path) —
never an in-place resolution change on the live rendition.

**Re-cast ownership.** `segmentBytes` runs inside an `MediaServerService` HTTP request and
cannot call `remoteMediaClient.load(...)`; only `VideoDetailActivity.castHls` owns the Cast
client. So a re-cast is not performed by the session itself: `HlsTranscodeSession` raises a typed
`NeedsRecast(reason, startMs, quality)` signal, the in-flight request fails fast, and `castHls`
(the owner) registers a replacement session and issues the `load(...)`. A log line records each
decision; an optional brief toast can inform the user.

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

- **Seek-restart latency vs the receiver's segment-fetch timeout.** The binding constraint is the
  receiver's per-segment fetch timeout, not just UX. The design keeps each seek response to a
  single isolated segment build by serving the target **immediately via the one-off builder** (the
  re-base happens behind it), so a seek never holds the HTTP request open for a pipeline cancel +
  cold restart + GOP pre-roll. "One-off build of the target segment completes within the receiver
  fetch timeout" is an explicit device-verification gate. A short backward rewind is served from
  the retained-window cache.
- **Pipeline liveness / cancellation.** A re-base must cleanly cancel the old pipeline
  (interrupt the worker, release codecs in `finally`) before starting a new one, mirroring the
  live path's teardown. Single-worker ownership avoids two hardware codec sessions at once.
- **Boundary-accurate IDRs.** `REQUEST_SYNC_FRAME` is a hint backed by the
  `KEY_I_FRAME_INTERVAL ≈ 6 s` fallback, so a boundary IDR is always present within ≤ ~1 frame. A
  genuinely missing boundary IDR is a **production failure** for that segment (error / re-cast),
  not a stretched segment — keeping `#EXTINF:6.000` honest (see *Segment-boundary timing*).
- **Re-base init compatibility.** A re-base builds a fresh, identically-configured encoder; the
  committed `init.mp4` stays authoritative. Byte-identical avcC is *not assumed* — device
  verification confirms re-based segments decode against the committed init (the
  `avcConfigsMatch` check is the diagnostic); full re-cast is reserved for resolution changes,
  not routine seeks (see *Invariants*).
- **Decode-bound sources.** Resolution fallback cannot help a source whose *decode* (not
  encode) saturates the device; the session commits to the lowest rung and accepts buffering
  with a message (see *Principled downscaling*).
- **Re-base thrash / stray probes.** Avoided by serving every out-of-window request from the
  one-off builder and only re-basing after `RELOCATE_AFTER` consecutive out-of-window requests,
  then **resetting the request cursor to the new base** (see *Production & request coordination*).
  So an isolated probe never moves the base, and post-seek playback near the new base is not
  mis-read as another discontinuity.
- **Long-session A/V drift.** The continuous audio path carries a running frame-count clock
  (live-path `AudioEncoderFeeder`: `basePtsUs + framesSent·1e6/sampleRate`) while video carries
  source PTS, replacing the current per-segment audio re-anchor. The plan must pin down the
  source-time → audio-PTS mapping, how a re-base re-anchors audio, and validate A/V drift over a
  multi-minute session and across a re-base (not just first-segment sample counts).
- **A/V alignment at boundaries.** Audio and video are cut at the same source time; reuse the
  live path's A/V sync handling. Validate first-segment audio/video sample counts.
- **Regression surface.** The HLS path is opt-in/secondary to the proven live path; gate the
  rollout behind the existing decision logic and keep the live path untouched.

## Testing strategy

- **Pure-JVM unit tests** for the new pure pieces: segment-boundary cutting math (PTS →
  segment index, IDR-at-boundary selection, missing-boundary-IDR → production-failure signal),
  the request routing + cursor logic (in-window vs out-of-window, one-off vs pipeline,
  `RELOCATE_AFTER` sustained-relocation re-base, cursor reset on re-base / non-zero start), the
  build-ratio fallback state machine (steady-state segment, early-bail per candidate), and
  quality → output-size mapping. (Consistent with the existing `HlsTranscodeMath` /
  `StreamingDecision` test style — no device or Robolectric.)
- **On-device verification** (Snapdragon "parrot" → Mi TV) with `Penguins` (1080p HEVC10 +
  AAC 7.1): confirm sustained `PLAYING` with no rebuffering over several minutes, capture the
  per-segment build ratio (< 1.0), verify a mid-range forward seek + a backward seek both resume
  promptly, validate A/V sync over a multi-minute session **and across a re-base**, and confirm
  re-based segments decode cleanly against the committed `init.mp4`.
- **Receiver request-ordering trace** (prerequisite for the coordination heuristic): capture the
  receiver's actual segment-request pattern for an fMP4 VOD that starts at a non-zero time, plus
  a forward and a backward seek, to confirm requests are monotonic / playhead-local and to
  measure the read-ahead depth that sizes `LEAD`.
- **Stress**: a 4K Tigole HEVC source to confirm the automatic fallback **engages and improves
  throughput** (steps resolution down at startup, e.g. 1080p→720p) — verifying the fallback
  mechanism, not that every 4K source becomes perfectly smooth (see the decode floor).

## Implementation phases (for the later plan)

1. Build `HlsSegmentPipeline` — an **independent adaptation** of the live decode→encode loop
   (not a refactor of `TranscodeStreamer`/`Fmp4Writer`) that cuts IDR-aligned fMP4 segments
   via `HlsMp4Builder` and adopts the live path's robust audio semantics, constrained to
   `Plan.copyAudio`. Live path stays untouched.
2. Wire `HlsTranscodeSession` to the pipeline: production frontier, `lastRequestedIndex`
   (init = `initialSegmentIndex`), back-pressure (`LEAD` ≥ measured read-ahead, sized for a
   steady-state reserve), retained-window cache, fine-grained waits, init capture; keep
   `HlsSegmentTranscoder` as the one-off / immediate-serve builder for every out-of-window request.
3. Seek / relocation: serve out-of-window requests via the one-off builder, re-base the pipeline
   after `RELOCATE_AFTER` consecutive out-of-window requests and reset the cursor; `NeedsRecast`
   signal → `castHls` for resolution-change / init-mismatch re-casts.
4. Prepare-before-load: `castHls` computes `initialSegmentIndex` from `pendingSeekPositionMs`
   and runs `prepare()` (resolution select + `PREBUFFER`) before `remoteMediaClient.load(...)`.
5. Explicit quality setting → `HlsTranscodeMath.outputSize` plumbing.
6. Automatic measured fallback inside `prepare()` (steady-state build ratio → resolution chosen
   before serving init, early-bail per candidate); rare mid-stream collapse → full re-cast.
7. Device verification: receiver request-ordering trace, sustained playback, seeks, A/V drift
   across a re-base, re-based-init compatibility, and the 4K fallback stress test.

## Open questions for review

1. `PREBUFFER` / `LEAD` / `BACK_BUFFER` / `RELOCATE_AFTER` (defaults ~3 / ~3–6 / ~2 / ~2, with
   `LEAD` ≥ the receiver's measured read-ahead and sized for a steady-state reserve) and the
   fallback build-ratio threshold (~0.85) — tune during device verification, or fix now?
2. Quality-setting location — Settings screen vs the existing per-video **Advanced** overflow
   submenu?
3. Mid-stream collapse handling — is the full re-cast worth building now, or should a rare
   sustained collapse just tolerate buffering until the user re-casts (resolution is already
   chosen up front, so this should be uncommon)?
