# Smooth HLS transcoding — sequential pipeline + principled downscaling

Date: 2026-06-19
Status: Draft for review (rev 13 — incorporates spec-review findings)

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
2. Decode each source frame **at most once in steady-state forward playback** (seeks and the rare
   boundary-IDR recovery use bounded one-off builds, which may re-decode a small, bounded range).
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
be **cut into independently-decodable HLS fMP4 segments**. In steady-state forward playback each
frame is decoded exactly once, codecs are created once per run, and — because one encoder runs at
**one fixed resolution for the whole session** — all segments share one SPS/PPS, which removes the
existing "segment avcC differs from init" corruption risk. (One-off builds for seeks/recovery are
cached, so the re-based pipeline skips indices already in the cache rather than re-encoding them.)

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
  multichannel through on HLS. Copy eligibility **and** the master-playlist `CODECS` value are
  keyed off the **effective (user-selected) audio track / committed `AudioInit`**, not
  `probe.primaryAudio` (today the decision uses `primaryAudio` while the builder uses
  `selectedAudioTrack ?: primaryAudio` — they must agree). The HLS session/builder keys audio copy
  eligibility **and** the master-playlist `CODECS` off the **effective (selected) audio track /
  committed `AudioInit`**, so they describe the track actually muxed. (Making the *selected* track
  also drive the top-level `StreamingDecision` route is a pre-existing, separate concern — its `Plan`
  has no shape for "direct video + selected-audio remux," so changing `decide()` naively would
  regress the existing `directStreamOrRemux` path; that is **out of scope** here and must not be
  touched.) Both builders timestamp audio with **absolute source PTS** (each segment's `tfdt` is its
  first sample's source-anchored PTS; per-sample timestamps within a segment are frame-count-derived,
  monotonic and gapless), so audio is interchangeable at any index. A unit test locks the
  selected-vs-primary copy/`CODECS` contract.
- **Segment cutting** — the committed config includes `KEY_I_FRAME_INTERVAL ≈ 6 s` (shared by both
  builders, so the one-off builder must drop its current frequent-IDR `KEY_I_FRAME_INTERVAL = 1`
  (1 s cadence) and force an IDR only at its first frame — otherwise junction bitrate/quality
  jumps); additionally,
  when a *decoded* frame's PTS crosses an `N·6 s` boundary the pipeline calls
  `encoder.setParameters(PARAMETER_KEY_REQUEST_SYNC_FRAME)` **before rendering that frame to the
  encoder input surface**, so that frame is encoded as an IDR. Encoded video + AAC samples
  accumulate per segment and, at each boundary, are assembled into an fMP4 segment via
  `HlsMp4Builder` and cached. On a detected boundary-IDR miss, the pipeline closes `seg(i-1)` at the
  last frame `< i·6 s`, yields `seg i` to the one-off builder for `[i·6 s, (i+1)·6 s)`, and re-forces
  the IDR at `(i+1)·6 s` — keeping the fixed grid intact. A segment is flushed only once **both** the
  video and audio encoders have drained past its end (each has produced a sample with PTS ≥
  `(i+1)·6 s`), so independent codec output latency never truncates boundary audio — the same
  half-open `[i·6 s, (i+1)·6 s)` rule the existing `HlsTranscodeMath` predicates already encode.
- **`HlsTranscodeSession` (modified)** — keeps the playlist/init/cache responsibilities but
  delegates production to an `HlsSegmentPipeline`. `segmentBytes(index)` coordinates with the
  pipeline (below) instead of building in isolation. `initBytes()` is captured once from the
  pipeline's encoder config at start.
- **`MediaServerService.serveHls` (unchanged interface)** — still serves
  `playlist.m3u8 / init.mp4 / segN.m4s`; `segmentBytes` now serves from the pipeline's window or
  the one-off builder, and may trigger a re-base or re-cast.

### Segment-boundary timing (one authority: nominal 6 s)

The playlist is the single timing authority: each `#EXTINF` is nominal **6.000 s** and segment
`i` is the content cut for `[i·6 s, (i+1)·6 s)`. The cut rule (identical for both builders) is:
**segment `i` starts at the first frame whose PTS ≥ `i·6 s`, and that frame is an IDR.** The
pipeline forces it by requesting a sync frame (`PARAMETER_KEY_REQUEST_SYNC_FRAME`, best-effort)
before rendering that frame, backed by `KEY_I_FRAME_INTERVAL ≈ 6 s`; the one-off builder gets it
for free (a fresh encoder always starts its segment with an IDR). The fMP4 `tfdt` anchors to that
first sample's absolute PTS, so:

- segment lengths vary by < 1 frame purely from **frame quantization** — 6 s is not an integer
  number of frames (≈ 143.86 at 23.976 fps), so the boundary lands mid-frame and the cut takes the
  first frame past it. This is deterministic and identical for both builders, and the receiver
  tolerates the sub-frame `#EXTINF` approximation;
- it is **non-cumulative** — every boundary targets the absolute `N·6 s`.

For both builders to agree on the *exact* first frame of segment `i`, the pipeline needs the
encoder to emit the IDR on the **precise requested frame** (the sync request is issued before that
frame is rendered to the input surface). This is a **binary device-verification gate**: the device
trace must confirm *frame-exact* IDR placement, not merely a ≈ 6 s cadence. An encoder that
systematically emits the IDR a frame late is a no-go for the pipeline path on that device (it would
fall back to the per-segment one-off path wholesale) — so frame-exactness is verified up front, not
assumed, not relied on. Given a frame-exact encoder, a *rare* individual miss (a single boundary
that came out non-IDR — the pipeline checks the observed boundary frame, so a miss is detected) is
served by the one-off builder for that one index — a recovery for the occasional miss, **not** a
systematic fallback. The gate measures the **miss rate**: a low rate makes recovery cheap; a high
rate (an encoder that systematically misses) means the pipeline can't help enough on that device,
which falls back to the current per-segment path (the problem stays unsolved there, but with no
regression). No variable-length segments, variable-boundary playlist, or IDR-derived `#EXTINF` map
is introduced; `#EXTINF:6.000` and `#EXT-X-TARGETDURATION:6` stay honest.

### Invariants

- **One committed config for the whole rendition.** One fixed encoder config per session ⇒ one
  SPS/PPS ⇒ one `init.mp4`; every segment (pipeline or one-off) decodes against it. "Committed
  config" means the **complete encoder `MediaFormat`** captured once at `prepare()` — every key that
  influences the SPS/PPS (dimensions, profile/level, `KEY_I_FRAME_INTERVAL`, `KEY_MAX_B_FRAMES = 0`,
  `KEY_LATENCY`, `KEY_COLOR_FORMAT`, bitrate + bitrate mode, frame rate) — applied **verbatim** to
  both builders, not just a resolution/profile/level/bitrate/frame-rate summary, so their avcC
  matches and the avcC guard never fires on a routine seek.
- **The committed `init.mp4` is authoritative; only a config change re-casts.** The pipeline and
  the one-off builder both encode with the **same committed config** (resolution / profile / level
  / bitrate / frame-rate), so their output is compatible with one published `init.mp4` — the
  current per-segment path already serves every segment from a fresh same-config encoder under one
  shared init and works, which is exactly this case at equal config. The `avcConfigsMatch` check
  stays as a guard; the **only** cause of a genuine avcC mismatch is an actual config/resolution
  change, handled by a **full re-cast** (new session id → new init → `load(...)`), never by serving
  a mismatched segment. Same committed config *should* yield a matching avcC, but this is **not
  assumed**: the implementation **validates each one-off / re-based segment's avcC against the
  committed init before serving it**, and on a mismatch does not serve it under the old init — it
  raises `NeedsRecast` (below). A device that shows persistent mismatch disables the pipeline path
  and falls back wholesale to the per-segment path (with its own committed init). Routine seeks do
  not change config, so in practice they never re-cast.
- **Builder interchangeability (scoped).** The steady-state pipeline and the one-off
  `HlsSegmentTranscoder` are two implementations of *one* segment contract: identical committed
  config, the same boundary cut rule (see *Segment-boundary timing*), and the same
  absolute-source-time → audio mapping.
  Both must therefore be brought to the committed config **and the same audio transcode semantics**
  (the live path's no-PCM-loss, frame-count-timestamped AAC) — today the one-off builder fixes its
  own output size/bitrate/fps and uses an older lossy AAC path, so this is a real change, not a
  pure reuse. Interchangeability is then:
    - **exact** for video and for *copy* audio (both builders read identical source frames at
      identical PTS) — a seek/stray segment from the one-off builder splices seamlessly next to
      pipeline segments;
    - **within ≤ 1 AAC frame (~21 ms)** for *transcoded* AAC at a one-off↔(one-off or pipeline)
      junction, because independent encoders anchor their 1024-sample grid differently (6 s is not
      an integer number of AAC frames, so the grids cannot be made to align). These junctions occur
      during a seek's catch-up — the first `RELOCATE_AFTER`-ish segments after a seek are one-off,
      then the re-based pipeline takes over — so a seek can carry one or a few sub-frame seams while
      the receiver re-buffers, **not** in steady-state playback. Accepted as the cost of serving
      seeks without blocking; device verification **listens at the one-off↔pipeline handoff**, not
      just for gross drift. (Copy audio has no such seam.)
- **Absolute PTS across re-base.** Segment `i` always carries source-absolute PTS for
  `[i·6 s, …)` regardless of the pipeline's base, so a re-based pipeline produces
  index-interchangeable segments at the same indices (interchangeable per the scoped contract
  above, not byte-identical across two encoder runs).

### Production & request coordination (`segmentBytes(index)`)

The hot pipeline produces forward from its base for the **steady-state** stream; anything it
can't deliver from its **production frontier** in time — a seek, a stray probe, or the first
segments during a cold re-base catch-up — is served **immediately** by the `HlsSegmentTranscoder`
(the existing per-segment transcode, brought to the committed config as the *one-off builder*).
That path has the same bounded latency as today's working-but-rebuffering path, so such a request
never blocks longer than a single isolated segment build, well inside the receiver's segment-fetch
timeout. The routing gate is the **actual production frontier** (what the pipeline has built), not
index arithmetic, so a cold re-based pipeline keeps serving one-off until its frontier catches up
to the playhead. Parameters:

- **`LEAD`** — the production run-ahead cap: the pipeline produces up to `LEAD` segments ahead of
  the playhead, then waits. Sized large enough for a steady-state reserve against transient
  throughput dips; memory-bounded (each cached segment is several MB). In steady state the frontier
  stays ahead of the playhead, so requests hit the cache.
- **`READAHEAD`** — the receiver's read-ahead depth (`≤ LEAD`). A forward request more than
  `READAHEAD` past the playhead (and out of the frontier's reach) is a discontinuity (a seek), not
  read-ahead. Kept **separate from `LEAD`** so a mid-size forward seek isn't mistaken for read-ahead.
- **`WAIT_MARGIN`** — how far *past the current frontier* a request may wait for production (a small
  ~1–2 segments, bounded by the fetch timeout) before it is served one-off instead.
- **`BACK_BUFFER`** — the retained window of already-built segments kept behind the playhead for
  short rewinds (~12 s ≈ 2 segments; defines the retained low-watermark; same memory bound).
- **`RELOCATE_AFTER`** — how many monotonic-adjacent requests at a *new* location confirm a real
  relocation (vs a lone stray probe) before the pipeline re-bases to follow it.

```
state: prevIndex (init = initialSegmentIndex), frontier (highest produced),
       lowWatermark (oldest retained index), relocRun = 0, relocAnchor = none

segmentBytes(index):
    if cached(index):
        prevIndex = index; relocRun = 0                  # playhead advanced — not a relocation
        return it
    if frontier < index <= frontier + WAIT_MARGIN:       # pipeline is about to produce it
        prevIndex = index; relocRun = 0
        return waitForProduction(index)                  # short, bounded wait

    # out of reach: produced-but-evicted, far ahead, or a seek — serve NOW via the one-off builder
    bytes = oneOffBuild(index)
    if relocAnchor != none and index == relocAnchor + relocRun:
        relocRun += 1                                    # a started candidate is sustaining
    elif index > prevIndex + READAHEAD or index < lowWatermark:   # forward seek, or backward below window
        relocAnchor = index; relocRun = 1                # a discontinuity starts a candidate
    else:                                                # contiguous forward catch-up — not a seek
        relocRun = 0; relocAnchor = none
    if relocRun >= RELOCATE_AFTER:                        # confirmed — follow the receiver
        reBase(pipeline, baseIndex = relocAnchor); relocRun = 0; relocAnchor = none
    prevIndex = index
    return bytes
```

- **Steady playback / read-ahead** — the frontier stays ahead of the playhead, so requests are
  cached (cache hits advance `prevIndex`) or a short `WAIT_MARGIN` wait; the pipeline keeps producing
  forward and never re-bases, even if it transiently falls behind (those requests stay within
  `READAHEAD` of the playhead, so they are served one-off but recognized as catch-up, not a seek).
- **Forward seek** — a request more than `READAHEAD` past the playhead and out of the frontier's
  reach; once it *sustains* for `RELOCATE_AFTER` adjacent requests the pipeline re-bases to it. The
  test is against the **moving playhead** (`prevIndex`), so normal playback far past the start never
  re-bases, and a mid-size forward seek is caught (it isn't hidden inside `LEAD`).
- **Backward rewind below the retained window** (`index < lowWatermark`) is a discontinuity served
  one-off; if it sustains it re-bases backward — independent of `READAHEAD`, so even a short rewind
  that falls outside `BACK_BUFFER` re-bases rather than serving one-off forever.
- **Stray / isolated probe** (a lone `seg0` duration probe while based far ahead) is a discontinuity
  that does *not* sustain, so it is served one-off and never moves the base.

The pipeline is the steady-state optimizer; the one-off builder is the universal immediate-serve
path, gated on the production frontier. (The receiver's actual request ordering — expected
monotonic / playhead-local for fMP4 VOD, including a non-zero start — is verified on device before
the plan commits to this rule.)

**Concurrency & resources.** `segmentBytes` is called on NanoHTTPD worker threads, so the
cursor/relocation/cache/frontier state is mutated only under an explicit `ReentrantLock` +
`Condition` (not a `synchronized` block) that is **released while waiting for production** (a wait
must not serialize every request behind one build). The relocation rule uses only the observable
request index — re-base needs a monotonic-adjacent run, so an interleaved or far-flung parallel
probe breaks the run rather than advancing it. **One-off builds are serialized** (one at a time,
the lock released during the build) so concurrent NanoHTTPD threads can't allocate several extra
codec sessions at once; results are cached so a repeated request hits the cache. Serving a one-off
while the pipeline is alive (a seek *and* the subsequent cold catch-up) uses a **second hardware
codec session**, so 2+2 concurrent codecs is a **fast-seek capability**, not a pipeline-eligibility
gate. It is detected **reactively at the first concurrent one-off** (the first mid-session seek —
`prepare()` never runs a concurrent one-off): `MediaCodecInfo.CodecCapabilities.getMaxSupportedInstances()`
is a conservative preflight hint, confirmed by catching a `configure()` / `start()` failure (which
may surface as "new codec refused" or "existing pipeline codec killed," the latter briefly
disturbing the playing segment). On a 2+2-capable device, a seek is served one-off **while** the
pipeline re-bases behind it. On a **1+1-only** device, seeks **degrade serially** — tear down the
pipeline, serve the seek via the one-off, rebuild the pipeline at the target, and serve the cold
catch-up by bounded `WAIT_MARGIN` waits (a brief rebuffer), never a concurrent one-off — so the
steady-state benefit (pipeline only, 1+1) is kept and only seeks are slower (accepted per the
product decision). Wholesale fallback to the current per-segment path is reserved for the
frame-exact-IDR and avcC-mismatch gates (where the pipeline can't produce a correct stream), not
for codec count. The device request trace captures **parallelism**, not just ordering, since it
sizes both `LEAD` and this rule.

### Startup: prepare-before-load (uses the 15 s budget)

Resolution must be chosen and the first segments produced **before** the receiver starts
fetching, so this is an explicit `prepare()` phase on the sender, ahead of
`remoteMediaClient.load(...)`:

1. `castHls` computes `initialSegmentIndex = pendingSeekPositionMs / 6000` (the sender can load
   HLS at a non-zero start time). This is the *expected* first index; the receiver request-ordering
   trace confirms it before it is relied on (if the receiver instead probes `seg0` first, the
   one-off builder serves that cleanly).
2. `prepare(initialSegmentIndex)` runs on a **background worker** (it is heavy transcode work —
   on-main would ANR), with cancellation and a user-visible "preparing" state, and any
   already-active HLS session is cancelled/evicted **before** the new one prepares. It runs
   **resolution selection** (the Auto fallback below) and builds `PREBUFFER` segments from
   `initialSegmentIndex`, committing the `init.mp4`.
3. Only then, back on the main thread, does `castHls` register the session and call `load(...)`, so
   the receiver's first `init.mp4` / `segN.m4s` requests are already satisfiable from cache.

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
the first **steady-state** segment — the *second* produced segment. Because the pipeline decodes
continuously (no discrete per-segment build), the ratio is defined as the wall time for the
production **frontier to advance one segment once warm** (excluding the first, pre-roll-inflated
segment and the initial pipeline fill). Only the first produced segment after a base/seek pays the
one-time `SEEK_TO_PREVIOUS_SYNC` pre-roll (the ~1.6× decode the new architecture removes from
steady state); measuring that pre-roll-inflated segment would over-estimate cost and needlessly
downscale capable devices (against Goals 1 & 4). It **bails early**: if the steady-state segment
can't sustain a lead (ratio above ~0.85) it steps down one rung (1080p → 720p → 540p) without
finishing a full pre-buffer at the doomed resolution. If a viability gate (frame-exact-IDR /
avcC stability) disables the pipeline, `prepare()` resolves viability **before** measuring
resolution, and resolution is then chosen from the per-segment path's cold build ratio (or a
conservative default rung), since there is no warm-frontier segment to measure.
So the common case (the first resolution sustains) stays within the ~15 s budget, and each
rejected rung adds only ~two segments of measurement, not a whole pre-buffer. The session then
commits to the chosen resolution — still automatic and measured, without violating the one-init
invariant. Capable devices keep full resolution; weak devices start at a sustainable one.

On a weak device that needs multiple step-downs, the *first* cast's `prepare()` can still exceed
the ~15 s budget (each rejected rung measures at sub-real-time speed); this is accepted, and the
implementation may remember the chosen resolution per device so repeat casts skip measurement and
start fast.

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
→ new `init.mp4` → `remoteMediaClient.load(...)` (via the in-session re-cast lifecycle below —
register-without-evict + draining, **not** the hard-evict path) — never an in-place resolution
change on the live rendition.

**Re-cast ownership.** `segmentBytes` runs on a NanoHTTPD request thread and cannot call
`remoteMediaClient.load(...)`; only `VideoDetailActivity.castHls` owns the Cast client. So the
session does not re-cast itself: it publishes a typed `NeedsRecast(reason, startMs, quality)` to a
**listener the activity registers at session creation** (since `castHls` is one-shot and would not
poll an `AtomicReference`); the listener posts to the main thread, registers a replacement session,
and issues the `load(...)`. The two replacement
lifecycles are distinct:

- **User-initiated fresh cast** (a new title) — the previous HLS session is cancelled/evicted
  *before* the new one prepares (today's hard-evict path is fine here).
- **In-session re-cast** (config change / collapse / init mismatch — same title, new id) — the new
  session is prepared and registered **without invalidating the old id**; the in-flight request
  returns **503 + `Retry-After`** and the old session stays in a short **draining** state (serving
  safe bytes / `503` for its id) until a swap-complete signal or TTL, then it is evicted — so a
  delayed old-id request never becomes a fatal 404 mid-transition.

A log line records each decision; an optional brief toast can inform the user.

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
  live path's teardown. The pipeline owns one codec session; a one-off build during a seek
  transiently adds a second (bounded 2+2), handled below.
- **Concurrent codec limits.** Serving a one-off while the pipeline is alive (seek + cold catch-up)
  needs 2+2 hardware codecs — a **fast-seek capability** detected reactively at the first seek
  (`getMaxSupportedInstances()` hint + `configure()` / `start()` failure). A **1+1-only** device
  keeps the pipeline (and its steady-state benefit) but **degrades seeks serially** (teardown →
  one-off → rebuild → catch-up via bounded waits); wholesale per-segment fallback is reserved for
  the frame-exact-IDR / avcC gates, not codec count (see *Production & request coordination*).
- **Boundary IDRs are best-effort.** Android's `REQUEST_SYNC_FRAME` is advisory and
  `KEY_I_FRAME_INTERVAL` is a cadence hint, so the pipeline cuts on the **observed** keyframe; if a
  boundary frame is not an IDR, that index is served by the one-off builder (guaranteed IDR), not a
  stretched segment (see *Segment-boundary timing*). Device traces confirm the cadence on the
  target encoders before the pipeline path is relied on.
- **Init compatibility across builders.** Both builders encode with the same committed config, so
  their `avcC` matches the published init by construction (the current equal-config per-segment
  path already proves this); the `avcConfigsMatch` check guards it, and the one-off builder must be
  **parameterized from the committed config** (it is hardwired today). A genuine avcC mismatch can
  only come from a config change → full re-cast (see *Invariants*).
- **Decode-bound sources.** Resolution fallback cannot help a source whose *decode* (not
  encode) saturates the device; the session commits to the lowest rung and accepts buffering
  with a message (see *Principled downscaling*).
- **Re-base thrash / stray probes.** Avoided by serving every out-of-window request from the
  one-off builder and only re-basing after `RELOCATE_AFTER` consecutive out-of-window requests,
  then **resetting the request cursor to the new base** (see *Production & request coordination*).
  So an isolated probe never moves the base, and post-seek playback near the new base is not
  mis-read as another discontinuity.
- **Long-session A/V drift & audio interchangeability.** Both builders must anchor audio to the
  **same absolute-source-time → audio-PTS mapping** (independent of the pipeline's base), so any
  index is audio-interchangeable and a re-base re-anchors cleanly. Validate A/V drift over a
  multi-minute session and across a re-base (not just first-segment sample counts); a source-audio
  gap is the residual risk to watch.
- **A/V alignment at boundaries.** Audio and video are cut at the same source time; reuse the
  live path's A/V sync handling. Validate first-segment audio/video sample counts.
- **Regression surface.** The HLS path is the **default route for seekable transcode** (e.g. the
  reported HEVC-10 + AAC `Penguins` case routes to HLS today), so this change affects the primary
  path for those titles — not a niche opt-in. It is still walled off from the proven live and
  direct-play paths; gate the rollout behind the existing decision logic and keep those paths
  untouched.

## Testing strategy

- **Pure-JVM unit tests** for the new pure pieces: segment-boundary cutting math (PTS →
  segment index, first-frame-≥-boundary IDR selection, observed-non-IDR → one-off recovery),
  builder interchangeability at the *contract* level (same committed init/config, first sample
  IDR, `tfdt`/PTS/durations tile cleanly, audio PTS continuity) — byte-for-byte equality is
  asserted only in pure `HlsMp4Builder` tests with identical mock sample inputs, never across two
  real encoder sessions — the request routing + cursor logic (in-window vs out-of-window, one-off
  vs pipeline, monotonic-run `RELOCATE_AFTER` re-base, cursor reset on re-base / non-zero start,
  parallel-probe robustness), the build-ratio fallback state machine (steady-state segment,
  early-bail per candidate), the selected-vs-primary audio-track contract, and quality →
  output-size mapping. (Consistent with the existing `HlsTranscodeMath` / `StreamingDecision` test
  style — no device or Robolectric.)
- **On-device verification** (Snapdragon "parrot" → Mi TV) with `Penguins` (1080p HEVC10 +
  AAC 7.1): confirm sustained `PLAYING` with no rebuffering over several minutes, capture the
  per-segment build ratio (< 1.0), verify a mid-range forward seek + a backward seek both resume
  promptly, validate A/V sync over a multi-minute session **and across a re-base**, confirm
  re-based and one-off segments decode cleanly against the committed `init.mp4`, confirm
  **frame-exact IDR placement** at boundaries, and confirm 2+2 concurrent hardware codecs during a
  seek. (If the re-cast path is built, also confirm the receiver backs off on an HTTP 503 +
  `Retry-After` rather than aborting.)
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
   via `HlsMp4Builder` using the shared boundary cut rule, and adopts the live path's audio
   semantics with absolute-source PTS, constrained to `Plan.copyAudio`. Live path stays untouched.
2. Parameterize `HlsSegmentTranscoder` from the committed encoder config and keep it as the
   one-off / immediate-serve builder (interchangeable segments; one-off builds serialized + cached);
   this includes replacing its lossy AAC copy with the live path's no-PCM-loss semantics, which must
   land **before** any device verification of pipeline+one-off interaction (the pipeline delegates
   every seek / catch-up / IDR-miss to it). Wire `HlsTranscodeSession` to the pipeline: production
   frontier, `prevIndex` (init = `initialSegmentIndex`), frontier-gated routing (`WAIT_MARGIN`),
   back-pressure (`LEAD` ≥ measured read-ahead, sized for a steady-state reserve), retained-window
   cache, the lock-released-during-waits concurrency model, init capture.
3. Seek / relocation: serve out-of-reach requests via the one-off builder, re-base after a
   `RELOCATE_AFTER` monotonic-adjacent run at a new location (forward jump > `READAHEAD` from the
   moving playhead, or backward below the retained low-watermark); `NeedsRecast` via a listener the
   activity registers at session creation → `castHls` (posts to main; HTTP 503 + `Retry-After`) for
   config-change / init-mismatch re-casts.
4. Prepare-before-load: `castHls` computes `initialSegmentIndex` from `pendingSeekPositionMs`
   and runs `prepare()` (resolution select + `PREBUFFER`) on a background worker before
   `remoteMediaClient.load(...)`; also fix the existing `setStreamDuration` unit bug (it passes
   `durationMs * 1000` while the SDK expects milliseconds, so the VOD seek bar is currently 1000×
   too long) and verify the receiver-reported duration in the request-ordering trace.
5. Explicit quality setting → `HlsTranscodeMath.outputSize` plumbing.
6. Automatic measured fallback inside `prepare()` (steady-state build ratio → resolution chosen
   before serving init, early-bail per candidate); rare mid-stream collapse → full re-cast.
7. Device verification: receiver request-ordering/parallelism trace, sustained playback, seeks,
   A/V drift across a re-base, builder-interchangeable / re-based init compatibility, 2+2 codec
   concurrency, and the 4K fallback stress test.

## Open questions for review

1. `PREBUFFER` / `LEAD` / `READAHEAD` / `WAIT_MARGIN` / `BACK_BUFFER` / `RELOCATE_AFTER` (defaults
   ~3 / ~3–6 / ~2 / ~1–2 / ~2 / ~2, with `READAHEAD` = the receiver's measured read-ahead depth and
   `LEAD ≥ READAHEAD` sized for a steady-state reserve) and the fallback build-ratio threshold
   (~0.85) — tune during device verification, or fix now?
2. Quality-setting location — Settings screen vs the existing per-video **Advanced** overflow
   submenu?
3. Mid-stream collapse handling — is the full re-cast worth building now, or should a rare
   sustained collapse just tolerate buffering until the user re-casts (resolution is already
   chosen up front, so this should be uncommon)?
