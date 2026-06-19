# Smooth HLS Transcoding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the per-6s-segment HLS transcoder (which re-decodes pre-roll from the previous keyframe for every segment, causing rebuffering) with a single long-lived sequential decode→encode pipeline that decodes each source frame once, cuts the encoded stream into IDR-aligned fMP4 segments, and serves seeks/strays/cold-catch-up via a one-off per-segment builder brought to the same committed encoder config.

**Architecture:** One `HlsSegmentPipeline` runs a continuous surface decode→encode loop from a base segment index, forces an IDR at each `N·6s` boundary, and writes finished segments into a shared cache (a *production frontier*). `HlsTranscodeSession.segmentBytes(index)` routes each request through a pure `HlsSegmentCoordinator` state machine: serve-from-cache, short wait for the frontier, or an immediate one-off build (the existing `HlsSegmentTranscoder`, now parameterized from the committed config) — re-basing the pipeline only on a sustained discontinuity from the moving playhead. Resolution is chosen up front in a background `prepare()` before `remoteMediaClient.load(...)`. The live (`TranscodeStreamer`/`Fmp4Writer`) and direct-play paths stay untouched.

**Tech Stack:** Kotlin, Android `MediaCodec` (surface mode, 10-bit→8-bit tone-map), NanoHTTPD HLS fMP4 VOD, Google Cast (Default Media Receiver), JUnit 4 pure-JVM unit tests (no Robolectric).

**Spec:** [docs/superpowers/specs/2026-06-19-smooth-hls-transcoding-design.md](../specs/2026-06-19-smooth-hls-transcoding-design.md)

---

## Conventions for every task

- **Build env (Windows PowerShell):** set the SDK before any gradle command:
  ```powershell
  $env:ANDROID_HOME="D:\AndroidVMs\SDK"; $env:ANDROID_SDK_ROOT="D:\AndroidVMs\SDK"
  ```
- **Unit tests:** `.\gradlew.bat testDebugUnitTest --console=plain`
- **Debug APK:** `.\gradlew.bat assembleDebug --console=plain`
- Unit tests are pure JVM (JUnit 4.13.2 only). `testOptions.unitTests.isReturnDefaultValues = true`, so `android.util.Log` no-ops — but **do not import `android.media.*` in pure test targets**; keep all pure logic in Android-free files (`HlsTranscodeMath` and the new pure classes below are Android-free).
- Test files live in `app/src/test/java/com/storagecast/media/`, package `com.storagecast.media`, plain class, `@Test fun camelCase_name()`, imports `org.junit.Test` + `org.junit.Assert.*`.

## Chosen parameter defaults (tunable on device — see spec Open Q1)

| Name | Value | Meaning |
|---|---|---|
| `SEGMENT_DURATION_US` | `6_000_000` | existing nominal segment length |
| `PREBUFFER` | `3` | segments built in `prepare()` before load |
| `LEAD` | `4` | production run-ahead cap (segments ahead of playhead) |
| `READAHEAD` | `2` | receiver read-ahead depth; forward jump `> READAHEAD` = seek |
| `WAIT_MARGIN` | `2` | how far past the frontier a request may wait before one-off |
| `BACK_BUFFER` | `2` | retained segments behind the playhead (rewind window) |
| `RELOCATE_AFTER` | `2` | monotonic-adjacent out-of-reach requests that confirm a relocation |
| `MAX_CACHED_SEGMENTS` | `10` | LRU cache size (≥ `LEAD + BACK_BUFFER + PREBUFFER` slack) |
| `RATIO_THRESHOLD` | `0.85` | build-ratio above which the fallback steps a rung down |

## Decisions on open questions

- **Open Q2 (quality-setting location):** a global **"Cast quality"** preference in `SettingsActivity` (key `cast_quality` ∈ `auto`/`1080`/`720`/`540`), read in `castHls`. Default `auto`.
- **Open Q3 (mid-stream collapse re-cast):** build the `NeedsRecast` **listener + the draining
  in-session re-cast** (Task 12) as one mandatory mechanism — the old session is registered
  *without* hard-evicting, returns `503 + Retry-After` for its id while draining, and is evicted on
  a swap-complete signal or TTL. This is the spec's mandated lifecycle and keeps the `503` promise
  honest (a hard-evict would 404 stragglers). The primary `NeedsRecast` trigger is an avcC mismatch
  (rare, since both builders share the committed config); mid-stream thermal collapse reuses the
  same listener. (A simple evict-and-recast is NOT used — it would 404 old-id requests mid-swap.)

---

## File Structure

**New files (pure, Android-free — fully unit-tested):**
- `app/src/main/java/com/storagecast/media/CastQuality.kt` — the quality enum + max-dimension mapping.
- `app/src/main/java/com/storagecast/media/CommittedEncoderConfig.kt` — the complete encoder-config value object shared by both builders.
- `app/src/main/java/com/storagecast/media/HlsSegmentCoordinator.kt` — the pure `segmentBytes` routing/relocation state machine.
- `app/src/main/java/com/storagecast/media/ResolutionFallback.kt` — the pure build-ratio step-down state machine.

**New files (Android — implement + on-device verify):**
- `app/src/main/java/com/storagecast/media/EncoderFormatFactory.kt` — the **single** place that turns a
  `CommittedEncoderConfig` into an AVC encoder `MediaFormat` + `MediaCodec`, used verbatim by BOTH
  the one-off builder and the pipeline so their SPS/PPS (avcC) are identical.
- `app/src/main/java/com/storagecast/media/HlsSegmentPipeline.kt` — the long-lived decode→encode pipeline.

**Modified files:**
- `app/src/main/java/com/storagecast/media/HlsTranscodeMath.kt` — add pure segment-boundary + config-derivation + audio-copy helpers.
- `app/src/main/java/com/storagecast/media/HlsSegmentTranscoder.kt` — accept a `CommittedEncoderConfig` (incl. explicit profile/level/encoder-name, `KEY_I_FRAME_INTERVAL=6`, IDR only at first frame); replace the lossy AAC copy with no-PCM-loss; use the effective (selected) audio track for copyability.
- `app/src/main/java/com/storagecast/media/HlsTranscodeSession.kt` — delegate production to `HlsSegmentPipeline`; route `segmentBytes` via `HlsSegmentCoordinator`; `ReentrantLock`+`Condition`; `NeedsRecast` listener; `prepare()`; effective-track `audioCodecAttr`.
- `app/src/main/java/com/storagecast/server/MediaServerService.kt` — `serveHls` returns `503` + `Retry-After` on a `NeedsRecast`/draining signal; non-evicting register variant for in-session re-cast (Task 12b, optional).
- `app/src/main/java/com/storagecast/ui/VideoDetailActivity.kt` — `castHls` runs `prepare()` on a background worker before `load(...)`, registers a `NeedsRecast` listener, reads the quality preference, fixes the `setStreamDuration` unit bug.
- `app/src/main/java/com/storagecast/ui/SettingsActivity.kt` + `app/src/main/res/...` — the "Cast quality" preference (Task 11a).

**New test files:**
- `app/src/test/java/com/storagecast/media/CastQualityTest.kt`
- `app/src/test/java/com/storagecast/media/CommittedEncoderConfigTest.kt`
- `app/src/test/java/com/storagecast/media/HlsSegmentCoordinatorTest.kt`
- `app/src/test/java/com/storagecast/media/ResolutionFallbackTest.kt`
- new test methods appended to `app/src/test/java/com/storagecast/media/HlsTranscodeMathTest.kt`

---

## Task 0: Device spike (manual go/no-go) — BEFORE building anything

This is a **manual probe**, not code that ships. It validates the two unproven device assumptions the whole pipeline rests on. If either fails, stop: the pipeline path is not viable on this device and we stay on the per-segment path (no regression).

**Files:** none committed (throwaway logging in a scratch branch is fine).

- [ ] **Step 1: Frame-exact forced-IDR probe.** In a throwaway run, drive a single `video/avc` surface encoder with `KEY_I_FRAME_INTERVAL = 6`, then call `encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })` *immediately before* `decoder.releaseOutputBuffer(idx, true)` for the decoded frame whose PTS first crosses each `N·6s` boundary. Log, for each boundary, whether the *next encoded output frame* carries `BUFFER_FLAG_KEY_FRAME` and its PTS.

Run on the target device (Snapdragon "parrot", encoder `c2.qti.avc.encoder`):
```
adb shell getprop ro.product.model   # confirm target device
```
Expected: the boundary frame (first frame ≥ `N·6s`) is encoded as a keyframe on the precise requested frame (not 1–2 frames late) for ~all boundaries. Record the **miss rate**.

- [ ] **Step 2: Decide go/no-go (a).** If miss rate is ~0 (rare misses OK): **GO** — proceed. If the encoder systematically emits the IDR 1–2 frames late: **NO-GO for the pipeline path** on this device; document it and stop (keep the current per-segment path). Record the result in the plan PR description.

- [ ] **Step 3: Receiver request-ordering trace.** Cast a current HLS fMP4 VOD that starts at a non-zero time (use `pendingSeekPositionMs > 0`), then do a forward seek and a backward seek. In `MediaServerService.serveHls`, temporarily log every `seg{n}.m4s` request (index + timestamp + thread). Confirm requests are monotonic / playhead-local, and **measure the read-ahead depth** (how many segments past the playhead the receiver pre-fetches) and whether requests arrive in parallel.

Expected: monotonic forward requests with a small bounded read-ahead. Record the measured read-ahead depth → this sets `READAHEAD` and confirms `LEAD ≥ READAHEAD`.

- [ ] **Step 4: Lock the measured params.** Update the "Chosen parameter defaults" table above if the measured read-ahead differs from `2`. Remove all throwaway logging before Task 1.

---

## Task 1: `CastQuality` enum + max-dimension mapping (pure, TDD)

**Files:**
- Create: `app/src/main/java/com/storagecast/media/CastQuality.kt`
- Test: `app/src/test/java/com/storagecast/media/CastQualityTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Test

class CastQualityTest {
    @Test fun auto_capsAt1080() {
        assertEquals(1920 to 1080, CastQuality.AUTO.maxDimensions())
    }

    @Test fun p720_capsAt720() {
        assertEquals(1280 to 720, CastQuality.P720.maxDimensions())
    }

    @Test fun p540_capsAt540() {
        assertEquals(960 to 540, CastQuality.P540.maxDimensions())
    }

    @Test fun fromPref_mapsKnownValues_defaultsToAuto() {
        assertEquals(CastQuality.AUTO, CastQuality.fromPref("auto"))
        assertEquals(CastQuality.P1080, CastQuality.fromPref("1080"))
        assertEquals(CastQuality.P720, CastQuality.fromPref("720"))
        assertEquals(CastQuality.P540, CastQuality.fromPref("540"))
        assertEquals(CastQuality.AUTO, CastQuality.fromPref(null))
        assertEquals(CastQuality.AUTO, CastQuality.fromPref("garbage"))
    }

    @Test fun autoFallbackRungs_startAt1080_descend() {
        assertEquals(listOf(CastQuality.P1080, CastQuality.P720, CastQuality.P540), CastQuality.autoRungs())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.storagecast.media.CastQualityTest" --console=plain`
Expected: FAIL (unresolved reference `CastQuality`).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.storagecast.media

/** Cast video-quality cap. AUTO starts at 1080p and lets the measured fallback step down. */
enum class CastQuality {
    AUTO, P1080, P720, P540;

    /** Max (width, height) cap fed to [HlsTranscodeMath.outputSize]. */
    fun maxDimensions(): Pair<Int, Int> = when (this) {
        AUTO, P1080 -> 1920 to 1080
        P720 -> 1280 to 720
        P540 -> 960 to 540
    }

    companion object {
        fun fromPref(value: String?): CastQuality = when (value) {
            "1080" -> P1080
            "720" -> P720
            "540" -> P540
            else -> AUTO
        }

        /** The descending rung order the AUTO measured fallback walks. */
        fun autoRungs(): List<CastQuality> = listOf(P1080, P720, P540)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.storagecast.media.CastQualityTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/storagecast/media/CastQuality.kt app/src/test/java/com/storagecast/media/CastQualityTest.kt
git commit -m "feat(hls): add CastQuality enum + max-dimension mapping"
```

---

## Task 2: Segment-boundary math helpers in `HlsTranscodeMath` (pure, TDD)

**Files:**
- Modify: `app/src/main/java/com/storagecast/media/HlsTranscodeMath.kt`
- Test: `app/src/test/java/com/storagecast/media/HlsTranscodeMathTest.kt` (append)

- [ ] **Step 1: Write the failing tests** (append to `HlsTranscodeMathTest.kt`)

```kotlin
    @Test fun segmentIndexForPts_floorsToSegment() {
        assertEquals(0, HlsTranscodeMath.segmentIndexForPts(0L, 6_000_000L))
        assertEquals(0, HlsTranscodeMath.segmentIndexForPts(5_999_999L, 6_000_000L))
        assertEquals(1, HlsTranscodeMath.segmentIndexForPts(6_000_000L, 6_000_000L))
        assertEquals(2, HlsTranscodeMath.segmentIndexForPts(12_500_000L, 6_000_000L))
    }

    @Test fun crossesBoundary_trueOnlyWhenPrevBelowAndCurrentAtOrAbove() {
        // boundary at 6s; frame at 5.96s -> 6.00s crosses it
        assertTrue(HlsTranscodeMath.crossesBoundary(5_960_000L, 6_000_000L, 6_000_000L))
        // both below
        assertFalse(HlsTranscodeMath.crossesBoundary(5_900_000L, 5_960_000L, 6_000_000L))
        // both at/above (already crossed earlier)
        assertFalse(HlsTranscodeMath.crossesBoundary(6_000_000L, 6_040_000L, 6_000_000L))
    }

    @Test fun segmentDrained_requiresBothTracksPastEnd() {
        assertTrue(HlsTranscodeMath.segmentDrained(6_000_000L, 6_010_000L, 6_000_000L))
        assertFalse(HlsTranscodeMath.segmentDrained(6_000_000L, 5_990_000L, 6_000_000L)) // audio short
        assertFalse(HlsTranscodeMath.segmentDrained(5_990_000L, 6_010_000L, 6_000_000L)) // video short
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.storagecast.media.HlsTranscodeMathTest" --console=plain`
Expected: FAIL (unresolved `segmentIndexForPts` / `crossesBoundary` / `segmentDrained`).

- [ ] **Step 3: Add the helpers to `HlsTranscodeMath`** (inside `object HlsTranscodeMath`, after the existing functions)

```kotlin
    /** Floor PTS to its segment index. */
    fun segmentIndexForPts(ptsUs: Long, segDurUs: Long): Int = (ptsUs / segDurUs).toInt()

    /**
     * True when consecutive frames straddle [boundaryUs]: the previous frame is strictly below
     * and the current frame is at/above. The pipeline requests a sync frame before rendering the
     * current (crossing) frame so it becomes the segment's first (IDR) frame.
     */
    fun crossesBoundary(prevPtsUs: Long, ptsUs: Long, boundaryUs: Long): Boolean =
        prevPtsUs < boundaryUs && ptsUs >= boundaryUs

    /**
     * A segment ending at [endUs] may be flushed only once BOTH encoders have produced a sample
     * with PTS >= endUs, so independent codec output latency never truncates boundary audio.
     */
    fun segmentDrained(videoMaxPtsUs: Long, audioMaxPtsUs: Long, endUs: Long): Boolean =
        videoMaxPtsUs >= endUs && audioMaxPtsUs >= endUs
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.storagecast.media.HlsTranscodeMathTest" --console=plain`
Expected: PASS (all `HlsTranscodeMathTest` methods, old and new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/storagecast/media/HlsTranscodeMath.kt app/src/test/java/com/storagecast/media/HlsTranscodeMathTest.kt
git commit -m "feat(hls): segment-boundary + both-tracks-drain math helpers"
```

---

## Task 3: `CommittedEncoderConfig` + derivation (pure, TDD)

The encoder config decided once and applied verbatim to both builders. The Android side (Task 7/8) fills `profile`/`level`/`encoderName` from `MediaCodecInfo` and creates the `MediaFormat`; the *numeric derivation* (size/bitrate/fps/IFI) is pure and tested here.

**Files:**
- Create: `app/src/main/java/com/storagecast/media/CommittedEncoderConfig.kt`
- Test: `app/src/test/java/com/storagecast/media/CommittedEncoderConfigTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Test

class CommittedEncoderConfigTest {
    @Test fun derive_capsResolutionToQuality_noUpscale_evenDims() {
        // 1080p source, AUTO cap (1920x1080) -> unchanged
        val c = CommittedEncoderConfig.derive(1920, 1080, srcBitrate = 12_000_000, srcFps = 24, CastQuality.AUTO)
        assertEquals(1920, c.width); assertEquals(1080, c.height)
    }

    @Test fun derive_p720_stepsDown1080Source() {
        val c = CommittedEncoderConfig.derive(1920, 1080, srcBitrate = 12_000_000, srcFps = 24, CastQuality.P720)
        assertEquals(1280, c.width); assertEquals(720, c.height)
    }

    @Test fun derive_clampsBitrateAndFps_andSetsSixSecondIdr() {
        val c = CommittedEncoderConfig.derive(1920, 1080, srcBitrate = 50_000_000, srcFps = 60, CastQuality.AUTO)
        assertEquals(8_000_000, c.bitrate)      // clamped to 8 Mb/s
        assertEquals(30, c.frameRate)           // clamped to 30
        assertEquals(6, c.iFrameIntervalSec)    // 6s GOP (vs the old 1s all-IDR)
    }

    @Test fun derive_keepsLowerSourceBitrateAndFps() {
        val c = CommittedEncoderConfig.derive(1280, 720, srcBitrate = 3_000_000, srcFps = 24, CastQuality.AUTO)
        assertEquals(3_000_000, c.bitrate)
        assertEquals(24, c.frameRate)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.storagecast.media.CommittedEncoderConfigTest" --console=plain`
Expected: FAIL (unresolved `CommittedEncoderConfig`).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.storagecast.media

/**
 * The complete encoder contract captured once per HLS session and applied verbatim to BOTH the
 * long-lived pipeline encoder and the one-off [HlsSegmentTranscoder], so their SPS/PPS (avcC)
 * match and the published init.mp4 stays authoritative for every segment.
 *
 * [profile], [level] and [encoderName] are resolved on the Android side (they need MediaCodecInfo);
 * they default to "unset" here so the pure derivation is testable. The numeric fields below are the
 * pure, deterministic part shared by both builders.
 */
data class CommittedEncoderConfig(
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val frameRate: Int,
    val iFrameIntervalSec: Int,
    val profile: Int = UNSET,
    val level: Int = UNSET,
    val encoderName: String? = null,
) {
    companion object {
        const val UNSET = -1

        /** Six-second GOP so a boundary IDR lands ~every segment (vs the old 1s all-IDR). */
        const val I_FRAME_INTERVAL_SEC = 6

        /** Pure numeric derivation. Android side fills profile/level/encoderName afterward. */
        fun derive(inW: Int, inH: Int, srcBitrate: Int, srcFps: Int, quality: CastQuality): CommittedEncoderConfig {
            val (maxW, maxH) = quality.maxDimensions()
            val (w, h) = HlsTranscodeMath.outputSize(inW, inH, maxW, maxH)
            return CommittedEncoderConfig(
                width = w,
                height = h,
                bitrate = HlsTranscodeMath.clampBitrate(srcBitrate),
                frameRate = HlsTranscodeMath.clampFrameRate(srcFps.toDouble()),
                iFrameIntervalSec = I_FRAME_INTERVAL_SEC,
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.storagecast.media.CommittedEncoderConfigTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/storagecast/media/CommittedEncoderConfig.kt app/src/test/java/com/storagecast/media/CommittedEncoderConfigTest.kt
git commit -m "feat(hls): CommittedEncoderConfig value object + pure derivation"
```

---

## Task 4: Effective-audio copy/CODECS contract (pure, TDD)

Today `StreamingDecision.copyAudio` and `audioCodecAttr` key off `probe.primaryAudio`, but the builder muxes `selectedAudioTrack ?: primaryAudio`. On HLS, audio is always AAC (Dolby routes to LIVE; MP3 transcodes to AAC because `HlsMp4Builder` has no MP3 entry). This task adds a pure helper so copy-eligibility and CODECS describe the **effective (selected) track**.

**Files:**
- Modify: `app/src/main/java/com/storagecast/media/HlsTranscodeMath.kt`
- Test: `app/src/test/java/com/storagecast/media/HlsTranscodeMathTest.kt` (append)

- [ ] **Step 1: Write the failing tests** (append to `HlsTranscodeMathTest.kt`)

```kotlin
    @Test fun effectiveCopyAudio_trueOnlyForAacMonoStereoWhenPlanAllows() {
        assertTrue(HlsTranscodeMath.effectiveCopyAudio(true, "audio/mp4a-latm", 2))
        assertTrue(HlsTranscodeMath.effectiveCopyAudio(true, "audio/mp4a-latm", 1))
    }

    @Test fun effectiveCopyAudio_falseForMultichannelAac() {
        assertFalse(HlsTranscodeMath.effectiveCopyAudio(true, "audio/mp4a-latm", 6))
    }

    @Test fun effectiveCopyAudio_falseForMp3_evenWhenPlanAllows() {
        // plan.copyAudio may be true for audio/mpeg, but HlsMp4Builder can't mux MP3 -> transcode
        assertFalse(HlsTranscodeMath.effectiveCopyAudio(true, "audio/mpeg", 2))
    }

    @Test fun effectiveCopyAudio_falseWhenPlanDisallows() {
        assertFalse(HlsTranscodeMath.effectiveCopyAudio(false, "audio/mp4a-latm", 2))
    }

    @Test fun hlsAudioCodecAttr_isAlwaysAacOnHls() {
        // HLS audio is always AAC (copy AAC, or transcode everything else to AAC)
        assertEquals("mp4a.40.2", HlsTranscodeMath.hlsAudioCodecAttr())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.storagecast.media.HlsTranscodeMathTest" --console=plain`
Expected: FAIL (unresolved `effectiveCopyAudio` / `hlsAudioCodecAttr`).

- [ ] **Step 3: Add the helpers to `HlsTranscodeMath`**

```kotlin
    /**
     * HLS copy is allowed only when the PLAN allows it AND the EFFECTIVE (selected) track is AAC
     * mono/stereo — the only thing HlsMp4Builder can mux. Everything else (incl. MP3, multichannel)
     * is decoded and (down)mixed to stereo AAC. Keys off the effective track, not primaryAudio.
     */
    fun effectiveCopyAudio(planCopyAudio: Boolean, effectiveMime: String?, effectiveChannels: Int): Boolean =
        planCopyAudio && effectiveMime == "audio/mp4a-latm" && effectiveChannels in 1..2

    /** HLS audio is always AAC-LC stereo/mono (copied AAC or transcoded-to-AAC). */
    fun hlsAudioCodecAttr(): String = "mp4a.40.2"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.storagecast.media.HlsTranscodeMathTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/storagecast/media/HlsTranscodeMath.kt app/src/test/java/com/storagecast/media/HlsTranscodeMathTest.kt
git commit -m "feat(hls): effective-track audio copy/CODECS contract"
```

---

## Task 5: `ResolutionFallback` step-down state machine (pure, TDD)

**Files:**
- Create: `app/src/main/java/com/storagecast/media/ResolutionFallback.kt`
- Test: `app/src/test/java/com/storagecast/media/ResolutionFallbackTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionFallbackTest {
    @Test fun sustains_commitsCurrentRung() {
        val fb = ResolutionFallback(CastQuality.autoRungs(), threshold = 0.85)
        assertEquals(CastQuality.P1080, fb.current)
        assertFalse(fb.evaluate(0.70))     // ratio below threshold -> committed, no step
        assertEquals(CastQuality.P1080, fb.current)
        assertTrue(fb.committed)
    }

    @Test fun overThreshold_stepsDownOneRung_thenSustains() {
        val fb = ResolutionFallback(CastQuality.autoRungs(), threshold = 0.85)
        assertTrue(fb.evaluate(0.95))      // too slow at 1080 -> step down
        assertEquals(CastQuality.P720, fb.current)
        assertFalse(fb.committed)
        assertFalse(fb.evaluate(0.60))     // 720 sustains -> commit
        assertEquals(CastQuality.P720, fb.current)
        assertTrue(fb.committed)
    }

    @Test fun atFloor_commitsEvenWhenStillTooSlow() {
        val fb = ResolutionFallback(CastQuality.autoRungs(), threshold = 0.85)
        fb.evaluate(0.95)                  // -> 720
        fb.evaluate(0.95)                  // -> 540
        assertEquals(CastQuality.P540, fb.current)
        assertFalse(fb.evaluate(0.95))     // floor: accept buffering, no further step
        assertEquals(CastQuality.P540, fb.current)
        assertTrue(fb.committed)
        assertTrue(fb.atFloor)
    }

    @Test fun manualSingleRung_alwaysCommits() {
        val fb = ResolutionFallback(listOf(CastQuality.P720), threshold = 0.85)
        assertFalse(fb.evaluate(0.99))     // single rung: commit regardless
        assertEquals(CastQuality.P720, fb.current)
        assertTrue(fb.committed)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.storagecast.media.ResolutionFallbackTest" --console=plain`
Expected: FAIL (unresolved `ResolutionFallback`).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.storagecast.media

/**
 * Walks descending quality [rungs] during prepare(). After producing a steady-state segment at the
 * current rung, call [evaluate] with its build ratio (wall_time / content_time). Steps down one rung
 * if the ratio is above [threshold] and a lower rung exists; otherwise commits the current rung.
 */
class ResolutionFallback(
    private val rungs: List<CastQuality>,
    private val threshold: Double = 0.85,
) {
    init { require(rungs.isNotEmpty()) }

    private var idx = 0

    val current: CastQuality get() = rungs[idx]
    val atFloor: Boolean get() = idx == rungs.lastIndex
    var committed: Boolean = false
        private set

    /** @return true if it stepped down a rung; false if it committed [current]. */
    fun evaluate(buildRatio: Double): Boolean {
        if (buildRatio <= threshold || atFloor) {
            committed = true
            return false
        }
        idx++
        return true
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.storagecast.media.ResolutionFallbackTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/storagecast/media/ResolutionFallback.kt app/src/test/java/com/storagecast/media/ResolutionFallbackTest.kt
git commit -m "feat(hls): ResolutionFallback build-ratio step-down state machine"
```

---

## Task 6: `HlsSegmentCoordinator` routing state machine (pure, TDD)

The heart of `segmentBytes`: given a request `index`, the current `frontier`, the retained `lowWatermark`, and a `isCached` predicate, decide **serve-from-cache / wait-for-production / one-off (and whether to re-base)** — exactly the spec pseudocode, anchored on the moving playhead.

**Files:**
- Create: `app/src/main/java/com/storagecast/media/HlsSegmentCoordinator.kt`
- Test: `app/src/test/java/com/storagecast/media/HlsSegmentCoordinatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Test

class HlsSegmentCoordinatorTest {
    private fun coord(initial: Int = 0) = HlsSegmentCoordinator(
        initialSegmentIndex = initial,
        lead = 4, readAhead = 2, waitMargin = 2, backBuffer = 2, relocateAfter = 2,
    )

    // Helper: a cache that contains a fixed set of indices.
    private fun cacheOf(vararg idx: Int): (Int) -> Boolean = { it in idx.toSet() }

    @Test fun cachedRequest_servesCache_advancesPlayhead() {
        val c = coord()
        val d = c.route(index = 1, frontier = 5, lowWatermark = 0, isCached = cacheOf(0, 1, 2))
        assertEquals(HlsSegmentCoordinator.Decision.ServeCached, d)
        assertEquals(1, c.prevIndex)
    }

    @Test fun justPastFrontier_waitsForProduction() {
        val c = coord()
        // frontier=5, request 6 (within frontier+WAIT_MARGIN=7), not cached
        val d = c.route(index = 6, frontier = 5, lowWatermark = 0, isCached = cacheOf(0, 1, 2, 3, 4, 5))
        assertEquals(HlsSegmentCoordinator.Decision.WaitForProduction(6), d)
    }

    @Test fun farForwardSeek_servesOneOff_andRebasesAfterSustained() {
        val c = coord(initial = 0)
        c.route(index = 0, frontier = 0, lowWatermark = 0, isCached = cacheOf(0))            // playhead at 0
        // seek to 50 (far past playhead+READAHEAD): one-off, no rebase yet (run=1)
        val d1 = c.route(index = 50, frontier = 0, lowWatermark = 0, isCached = cacheOf(0))
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(50, rebaseTo = null), d1)
        // next adjacent request 51 confirms the relocation (run=2=RELOCATE_AFTER) -> rebase
        val d2 = c.route(index = 51, frontier = 0, lowWatermark = 0, isCached = cacheOf(0, 50))
        // base = first non-cached >= 50, treating 51 (served one-off now) as cached too -> 52
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(51, rebaseTo = 52), d2)
    }

    @Test fun transientFallBehind_servesOneOff_butNeverRebases() {
        val c = coord(initial = 0)
        // steady playback advanced prevIndex to 30 via cache hits
        for (i in 0..30) c.route(i, frontier = i + 4, lowWatermark = i - 2, isCached = { it <= i + 4 })
        // pipeline briefly stalls 2 behind: frontier=28; request 31 is just out of reach AND contiguous
        // with the moving playhead (31 - prevIndex(30) = 1 <= READAHEAD) -> catch-up one-off, NOT a seek.
        val d = c.route(index = 31, frontier = 28, lowWatermark = 28, isCached = { it <= 28 })
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(31, rebaseTo = null), d)
        // next contiguous catch-up request 32 (32 - prevIndex(31) = 1 <= READAHEAD): still no rebase.
        val d2 = c.route(index = 32, frontier = 29, lowWatermark = 28, isCached = { it <= 29 })
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(32, rebaseTo = null), d2)
    }

    @Test fun strayProbe_doesNotRebase() {
        val c = coord(initial = 40)
        c.route(index = 40, frontier = 44, lowWatermark = 38, isCached = { it <= 44 }) // playhead 40
        // lone seg0 probe (far below window) -> one-off, run=1 (does not reach RELOCATE_AFTER)
        val d = c.route(index = 0, frontier = 44, lowWatermark = 38, isCached = { it in 38..44 })
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(0, rebaseTo = null), d)
        // receiver resumes at the still-cached playhead 45 -> ServeCached, relocation run reset, never rebased
        val d2 = c.route(index = 45, frontier = 49, lowWatermark = 43, isCached = { it in 43..49 })
        assertEquals(HlsSegmentCoordinator.Decision.ServeCached, d2)
    }

    @Test fun backwardRewindBelowWindow_rebasesWhenSustained() {
        val c = coord(initial = 100)
        c.route(index = 100, frontier = 104, lowWatermark = 98, isCached = { it in 98..104 })
        // rewind to 90 (below lowWatermark, not cached) -> one-off, run=1
        val d1 = c.route(index = 90, frontier = 104, lowWatermark = 98, isCached = { it in 98..104 })
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(90, rebaseTo = null), d1)
        // adjacent 91 confirms -> rebase to the first NON-cached index >= 90, treating 90 (already
        // cached) and 91 (served one-off now) as cached -> base 92.
        val d2 = c.route(index = 91, frontier = 104, lowWatermark = 98, isCached = { it in 98..104 || it == 90 })
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(91, rebaseTo = 92), d2)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.storagecast.media.HlsSegmentCoordinatorTest" --console=plain`
Expected: FAIL (unresolved `HlsSegmentCoordinator`).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.storagecast.media

/**
 * Pure routing/relocation state machine for HlsTranscodeSession.segmentBytes(index).
 * Decides whether a request is served from cache, by a short wait for the production frontier, or by
 * an immediate one-off build — and whether the pipeline should re-base to follow a sustained
 * discontinuity. Anchored on the MOVING playhead (prevIndex), so normal playback far past the start
 * never re-bases. Not thread-safe; the caller mutates it only under its ReentrantLock.
 */
class HlsSegmentCoordinator(
    initialSegmentIndex: Int,
    private val lead: Int,
    private val readAhead: Int,
    private val waitMargin: Int,
    private val backBuffer: Int,
    private val relocateAfter: Int,
) {
    var prevIndex: Int = initialSegmentIndex
        private set
    private var relocRun = 0
    private var relocAnchor: Int? = null

    sealed class Decision {
        object ServeCached : Decision()
        data class WaitForProduction(val index: Int) : Decision()
        /** Serve [index] via a one-off build now; if [rebaseTo] != null, re-base the pipeline there. */
        data class OneOff(val index: Int, val rebaseTo: Int?) : Decision()
    }

    fun route(index: Int, frontier: Int, lowWatermark: Int, isCached: (Int) -> Boolean): Decision {
        if (isCached(index)) {
            prevIndex = index; relocRun = 0; relocAnchor = null
            return Decision.ServeCached
        }
        if (index > frontier && index <= frontier + waitMargin) {
            prevIndex = index; relocRun = 0; relocAnchor = null
            return Decision.WaitForProduction(index)
        }

        // out of reach: produced-but-evicted, far ahead, or a seek -> serve one-off now
        val anchor = relocAnchor
        if (anchor != null && index == anchor + relocRun) {
            relocRun += 1                                   // a started candidate is sustaining
        } else if (index > prevIndex + readAhead || index < lowWatermark) {
            relocAnchor = index; relocRun = 1               // a discontinuity starts a candidate
        } else {
            relocRun = 0; relocAnchor = null                // contiguous catch-up, not a seek
        }

        var rebaseTo: Int? = null
        if (relocRun >= relocateAfter) {
            // [index] is being served one-off right now, so treat it as cached when finding the base.
            rebaseTo = firstNonCached(relocAnchor!!) { i -> i == index || isCached(i) }
            relocRun = 0; relocAnchor = null
        }
        prevIndex = index
        return Decision.OneOff(index, rebaseTo)
    }

    private fun firstNonCached(from: Int, cached: (Int) -> Boolean): Int {
        var i = from
        while (cached(i)) i++
        return i
    }

    /** The retained low-watermark the caller passes is prevIndex - backBuffer; exposed for clarity. */
    fun lowWatermark(): Int = prevIndex - backBuffer
}
```

> Note: `lead` and `lowWatermark()` exist for the caller's convenience; the routing math uses `frontier`, `waitMargin`, `readAhead`, and the `lowWatermark` argument. `lead` bounds production in the pipeline (Task 8), not here.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.storagecast.media.HlsSegmentCoordinatorTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Run the full suite to confirm no regressions**

Run: `.\gradlew.bat testDebugUnitTest --console=plain`
Expected: PASS (all existing + new tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/storagecast/media/HlsSegmentCoordinator.kt app/src/test/java/com/storagecast/media/HlsSegmentCoordinatorTest.kt
git commit -m "feat(hls): HlsSegmentCoordinator routing/relocation state machine"
```

---

## Task 7: Parameterize `HlsSegmentTranscoder` from the committed config + fix lossy AAC

Make the one-off builder produce **interchangeable** segments: same committed config (explicit profile/level/encoder-name, `KEY_I_FRAME_INTERVAL=6` with an IDR only at the first frame), and replace the lossy `minOf(...)` AAC copy with no-PCM-loss feeding. No JVM unit test (MediaCodec); verified by build + on-device (Task 13). Keep the existing pure helpers it calls.

**Files:**
- Modify: `app/src/main/java/com/storagecast/media/HlsSegmentTranscoder.kt`

> Line numbers below are approximate (the file shifts as you edit) — locate the targets by symbol
> name (`transcodeRange`, `transcodeVideoRange`, `createVideoEncoder`, `transcodeAudioRange`).

- [ ] **Step 1: Create the shared `EncoderFormatFactory`** (so the pipeline in Task 8 builds the
  *byte-identical* encoder format — the whole interchangeability contract depends on this).

```kotlin
package com.storagecast.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build

/** Single source of truth that materializes a CommittedEncoderConfig into an AVC encoder. */
object EncoderFormatFactory {
    const val OUTPUT_VIDEO_MIME = "video/avc"

    fun buildAvcEncoderFormat(c: CommittedEncoderConfig): MediaFormat =
        MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME, c.width, c.height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, c.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, c.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, c.iFrameIntervalSec) // 6s GOP (was 1 = all-IDR)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            if (c.profile != CommittedEncoderConfig.UNSET) {
                setInteger(MediaFormat.KEY_PROFILE, c.profile)
                setInteger(MediaFormat.KEY_LEVEL, c.level)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) setInteger(MediaFormat.KEY_LATENCY, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }

    /** Create the encoder by the committed name when present, else by type. */
    fun createCommittedEncoder(c: CommittedEncoderConfig): MediaCodec {
        c.encoderName?.let { name -> runCatching { return MediaCodec.createByCodecName(name) } }
        return MediaCodec.createEncoderByType(OUTPUT_VIDEO_MIME)
    }

    /** Pick a hardware AVC encoder name (so both builders use the same codec implementation). */
    fun pickHardwareAvcEncoderName(): String? {
        val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        return list.codecInfos.firstOrNull { info ->
            info.isEncoder &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || info.isHardwareAccelerated) &&
                info.supportedTypes.any { it.equals(OUTPUT_VIDEO_MIME, ignoreCase = true) }
        }?.name
    }

    /**
     * (AVCProfileHigh, AVCLevel41) — matches the playlist CODECS "avc1.640029" and covers every rung
     * (<=1080p30) — if [encoderName] advertises it; else (UNSET, UNSET) to fall back to encoder
     * defaults. Pinning these makes both builders' avcC identical (the spec invariant).
     */
    fun avcHighL41IfSupported(encoderName: String?): Pair<Int, Int> {
        val high = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        val l41 = MediaCodecInfo.CodecProfileLevel.AVCLevel41
        val unset = CommittedEncoderConfig.UNSET to CommittedEncoderConfig.UNSET
        val name = encoderName ?: return unset
        val info = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            .codecInfos.firstOrNull { it.name == name } ?: return unset
        val ok = runCatching {
            info.getCapabilitiesForType(OUTPUT_VIDEO_MIME).profileLevels
                .any { it.profile == high && it.level >= l41 }
        }.getOrDefault(false)
        return if (ok) high to l41 else unset
    }
}
```

- [ ] **Step 2: Add a `committedConfig` parameter to `transcodeRange`** (locate `fun transcodeRange`):

```kotlin
fun transcodeRange(
    inputPath: String,
    probeResult: MediaProbeResult,
    selectedAudioTrack: AudioTrackInfo?,
    startUs: Long,
    endUs: Long,
    copyAudio: Boolean,
    committedConfig: CommittedEncoderConfig,
): Result
```

Thread `committedConfig` into `transcodeVideoRange(inputPath, track, startUs, endUs, committedConfig)`.

- [ ] **Step 3: Build the encoder format from the committed config + fix the published dimensions.**
  In `transcodeVideoRange`, replace the hardwired encoder-`MediaFormat` block (the `outputSize(...)` /
  `clampBitrate` / `clampFrameRate` / `createVideoFormat(...)` locals, ~L100–122) with:

```kotlin
val format = EncoderFormatFactory.buildAvcEncoderFormat(committedConfig)
```

  Then **change the `VideoInit` construction at the tail of the function** from
  `HlsMp4Builder.VideoInit(it, outW, outH)` to:

```kotlin
HlsMp4Builder.VideoInit(it, committedConfig.width, committedConfig.height)
```

  and **delete the now-dead `outW`/`outH`/`outBitrate`/`outFps` locals** (they used the local
  1920×1080 caps and would mismatch the published `init.mp4` dimensions on any downscaled config).

- [ ] **Step 4: Create the encoder via the factory** (replace `createVideoEncoder()`'s body, or its call):

```kotlin
val encoder = EncoderFormatFactory.createCommittedEncoder(committedConfig)
```

- [ ] **Step 5: Force an IDR at the first emitted segment frame.** `KEY_I_FRAME_INTERVAL` is now 6, so
  a fresh encoder still starts its segment with an IDR — request it explicitly to be safe. After
  `encoder.start()` and the input surface is connected:

```kotlin
encoder.setParameters(android.os.Bundle().apply {
    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
})
```

- [ ] **Step 6: Replace the lossy AAC copy with no-PCM-loss feeding** (locate the
  `limit = minOf(decoded.remaining(), encBuf.remaining())` block in `transcodeAudioRange`). Mirror the
  live path's `AudioEncoderFeeder`: queue ALL decoded PCM and feed it across multiple encoder input
  buffers; timestamp encoded output as `basePtsUs + framesSent * 1_000_000L / sampleRate`, where
  `basePtsUs` is the first decoded PCM frame's **absolute source PTS** (so segments are
  audio-interchangeable). Port the `AudioEncoderFeeder` loop from `TranscodeStreamer` (locate the
  `inner class AudioEncoderFeeder`) into a private helper in `HlsSegmentTranscoder`, replacing the
  single-buffer `minOf` copy. Keep `KEY_MAX_OUTPUT_CHANNEL_COUNT = 2` on the decoder for downmix.
  **Gate the enqueue to `[startUs, endUs)`** — enqueue only decoded PCM with `pts >= startUs &&
  pts < endUs` (so `basePtsUs` = the first frame ≥ `startUs`, matching the pipeline's bucket for that
  segment); do **not** queue the `SEEK_TO_PREVIOUS_SYNC` pre-roll. (The shared feeder is
  range-agnostic; gating happens at the call site so the one-off and pipeline stay interchangeable.)

- [ ] **Step 7: Keep Task 7 self-contained — update the one production caller so it still compiles.**
  `transcodeRange` has exactly one caller today: `HlsTranscodeSession.buildAndCacheSegment` (locate it).
  Pass a temporary derived config there (Task 9 replaces this with the session's committed config):

```kotlin
val tmpConfig = CommittedEncoderConfig.derive(
    probeResult.primaryVideo!!.width, probeResult.primaryVideo!!.height,
    probeResult.primaryVideo!!.bitrate, probeResult.primaryVideo!!.frameRate.toInt(), CastQuality.AUTO,
)
val result = transcoder.transcodeRange(inputPath, probeResult, selectedAudioTrack, startUs, endUs, copyAudio, tmpConfig)
```

- [ ] **Step 8: Confirm it compiles and existing tests still pass**

Run: `.\gradlew.bat assembleDebug --console=plain` then `.\gradlew.bat testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL; all existing tests PASS (the temporary call site in Step 7 keeps it compiling).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/storagecast/media/EncoderFormatFactory.kt app/src/main/java/com/storagecast/media/HlsSegmentTranscoder.kt app/src/main/java/com/storagecast/media/HlsTranscodeSession.kt
git commit -m "feat(hls): EncoderFormatFactory + parameterize one-off builder; no-PCM-loss AAC"
```

---

## Task 8: Build `HlsSegmentPipeline` (the long-lived decode→encode loop)

The new continuous pipeline. Modeled on `TranscodeStreamer`'s surface loop but cuts the running stream into IDR-aligned fMP4 segments (via `HlsMp4Builder`) and exposes a production frontier + cache. No JVM unit test (MediaCodec); verified on device (Task 13). The pure cut/flush decisions reuse the Task 2 helpers.

**Files:**
- Create: `app/src/main/java/com/storagecast/media/HlsSegmentPipeline.kt`

- [ ] **Step 1: Define the class skeleton + lifecycle**

```kotlin
package com.storagecast.media

/**
 * One long-lived sequential decode->encode pipeline per HLS run. Decodes the source forward from a
 * base segment index (each frame once), forces an IDR at every N*6s boundary, cuts the encoded
 * stream into fMP4 segments via HlsMp4Builder, and publishes finished segments to [onSegment] while
 * advancing [frontier]. Bounded to LEAD segments ahead of the playhead (back-pressure). Modeled on
 * TranscodeStreamer's surface loop (10-bit tone-map, encoder-before-decoder, finally teardown);
 * does NOT refactor it.
 */
class HlsSegmentPipeline(
    private val inputPath: String,
    private val probeResult: MediaProbeResult,
    private val selectedAudioTrack: AudioTrackInfo?,
    private val copyAudio: Boolean,
    private val committedConfig: CommittedEncoderConfig,
    private val segDurUs: Long,
    private val lead: Int,
    /** Called with each finished segment (index, bytes, videoInit, audioInit). */
    private val onSegment: (SegmentResult) -> Unit,
    /** Called when a boundary-IDR miss drops segment [index] (so the session routes it to one-off). */
    private val onSkipped: (Int) -> Unit = {},
    /** Returns the current playhead (prevIndex) so production can back-pressure to playhead+LEAD. */
    private val playhead: () -> Int,
) {
    data class SegmentResult(
        val index: Int,
        val bytes: ByteArray,
        val videoInit: HlsMp4Builder.VideoInit,
        val audioInit: HlsMp4Builder.AudioInit?,
    )

    @Volatile private var cancelled = false
    @Volatile var frontier: Int = -1
        private set
    @Volatile var capturedVideoInit: HlsMp4Builder.VideoInit? = null
        private set
    @Volatile var capturedAudioInit: HlsMp4Builder.AudioInit? = null
        private set

    private var worker: Thread? = null

    fun start(baseIndex: Int) { /* Step 2 */ }
    fun cancel() { cancelled = true; worker?.interrupt(); worker?.join(2000) }
}
```

- [ ] **Step 2: Implement the surface decode→encode loop with IDR forcing + boundary cutting.** In `start(baseIndex)`, spawn a single daemon thread running `runPipeline(baseIndex)`:
  - Open video + audio `MediaExtractor`s; `seekTo(baseIndex * segDurUs, SEEK_TO_PREVIOUS_SYNC)` (the **one** pre-roll for this base — every later frame is decoded once).
  - Configure the encoder from `committedConfig` using the **shared** `EncoderFormatFactory` (Task 7),
    so the pipeline encoder format is byte-identical to the one-off builder's:
    `val encoder = EncoderFormatFactory.createCommittedEncoder(committedConfig)` then
    `encoder.configure(EncoderFormatFactory.buildAvcEncoderFormat(committedConfig), null, null, CONFIGURE_FLAG_ENCODE)`.
    Create encoder → input surface → decoder configured with that surface (encoder-before-decoder,
    as `TranscodeStreamer` does).
  - For each decoded output frame at `ptsUs`: **render it only if `ptsUs >= baseIndex * segDurUs`** —
    frames below the base are `SEEK_TO_PREVIOUS_SYNC` pre-roll, so decode them (to advance the decoder)
    but call `decoder.releaseOutputBuffer(idx, false)` (do not send to the encoder surface), exactly
    as the one-off builder's `HlsTranscodeMath.shouldRenderVideoFrame` gate does. The encoder's first
    real frame is then the base segment's first frame (always an IDR). For a **rendered** frame, if
    `HlsTranscodeMath.crossesBoundary(prevPts, ptsUs, nextBoundaryUs)`, call
    `encoder.setParameters(REQUEST_SYNC_FRAME)` **before** `decoder.releaseOutputBuffer(idx, true)`,
    then advance `nextBoundaryUs += segDurUs`.
  - Accumulate encoded video samples (AVCC via `HlsMp4Builder.ensureAvcc`) and AAC samples into per-segment buckets keyed by `HlsTranscodeMath.segmentIndexForPts(samplePts, segDurUs)`.
  - When `HlsTranscodeMath.segmentDrained(videoMaxPts, audioMaxPts, (segIndex+1)*segDurUs)` for the current `segIndex`, assemble `HlsMp4Builder.buildMediaSegment(sequenceNumber = segIndex + 1, videoSamples, audioSamples, 33_333L, 21_333L)`, capture `videoInit`/`audioInit` on the first segment, set the pipeline's internal `frontier = segIndex` (**advisory only** — the *routing* frontier is owned by the session, which advances it in `putSegment` after the segment is actually cached), and invoke `onSegment(...)`.
  - **Boundary-IDR miss recovery:** when a segment is assembled, verify its first video sample is a keyframe. If not, drop that segment's pipeline bytes (do **not** publish via `onSegment`), call `onSkipped(segIndex)` (so the session routes that index straight to the one-off builder instead of waiting for a segment the pipeline will never produce), log the miss, and continue the pipeline (it re-forces the IDR at the next boundary).
  - **Back-pressure:** before producing `segIndex`, if `segIndex > playhead() + lead`, park briefly (`Thread.sleep(20)` loop) until the playhead advances or cancelled.
  - Audio uses the same no-PCM-loss / absolute-source-PTS feeder as Task 7, but the pipeline enqueues PCM **continuously** (no `[startUs,endUs)` gate — it is one continuous stream); per-segment bucketing happens by sample PTS at the cut, as for video. (Same feeder helper as the one-off, different enqueue policy: the one-off gates at enqueue, the pipeline does not.)
  - Wrap the whole loop in `try { ... } finally { safe-release all codecs/extractors }`; check `cancelled`/`Thread.interrupted()` at the top of every loop.

- [ ] **Step 3: Confirm it compiles**

Run: `.\gradlew.bat assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/storagecast/media/HlsSegmentPipeline.kt
git commit -m "feat(hls): HlsSegmentPipeline continuous decode->encode segment producer"
```

---

## Task 9: Wire `HlsTranscodeSession` to the pipeline + coordinator + one-off

Replace per-segment building with: the pipeline produces forward; `segmentBytes` routes via `HlsSegmentCoordinator`; out-of-reach requests use the parameterized one-off builder; a `ReentrantLock`+`Condition` releases the lock during waits; avcC is validated against the committed init before serving; a `NeedsRecast` listener is exposed.

**Files:**
- Modify: `app/src/main/java/com/storagecast/media/HlsTranscodeSession.kt`

- [ ] **Step 1: Add constructor params + the `NeedsRecast` contract**

```kotlin
data class NeedsRecast(val reason: String, val startMs: Long, val quality: CastQuality)

class HlsTranscodeSession(
    private val inputPath: String,
    private val probeResult: MediaProbeResult,
    private val selectedAudioTrack: AudioTrackInfo?,
    private val copyAudio: Boolean = false,
    private val subtitleVtt: ByteArray? = null,
    private val quality: CastQuality,
    // The Android codec-resolution owner. The Activity (Task 10) supplies this: it derives the
    // numeric config for a quality rung AND resolves the encoder name + explicit profile/level.
    // prepare() owns the measurement loop and calls this per rung; the chosen result is stored as
    // [committedConfig] and used verbatim by both the pipeline and the one-off builder.
    private val configForQuality: (CastQuality) -> CommittedEncoderConfig,
    private val onNeedsRecast: (NeedsRecast) -> Unit = {},
) {
```

- [ ] **Step 2: Replace the lock + cache + add coordinator/pipeline fields**

```kotlin
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val produced = lock.newCondition()
    private val segmentCache = object : LinkedHashMap<Int, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, ByteArray>) = size > MAX_CACHED_SEGMENTS
    }
    private var coordinator: HlsSegmentCoordinator? = null
    private var pipeline: HlsSegmentPipeline? = null
    @Volatile private var committedConfig: CommittedEncoderConfig? = null   // chosen in prepare()
    @Volatile private var frontier: Int = -1                                // highest CACHED index (routing frontier)
    @Volatile var draining: Boolean = false                                 // set on NeedsRecast (Task 12)
    private val oneOffLock = Any()                                          // serializes one-off builds
    @Volatile private var serialSeekMode = false                           // set on a 1+1 codec-limit failure
    private val skipped = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()  // pipeline IDR-miss drops
```
Bump `MAX_CACHED_SEGMENTS` to `10`. Compute `audioCodecAttr` via `HlsTranscodeMath.hlsAudioCodecAttr()` (always `mp4a.40.2`). **Declare the tuning constants in the companion** (from the *Chosen parameter defaults* table): `const val PREBUFFER = 3`, `LEAD = 4`, `READAHEAD = 2`, `WAIT_MARGIN = 2`, `BACK_BUFFER = 2`, `RELOCATE_AFTER = 2`, `RATIO_THRESHOLD = 0.85` (Tasks 9/11 use these unqualified).

- [ ] **Step 3: Add `prepare(initialSegmentIndex)`** — builds the pipeline, primes `PREBUFFER` segments, captures init. (Called from `castHls` on a background thread.)

```kotlin
private fun newPipeline(baseIndex: Int) = HlsSegmentPipeline(
    inputPath, probeResult, selectedAudioTrack,
    copyAudio = HlsTranscodeMath.effectiveCopyAudio(copyAudio, (selectedAudioTrack ?: probeResult.primaryAudio)?.mime, (selectedAudioTrack ?: probeResult.primaryAudio)?.channelCount ?: 2),
    committedConfig!!, SEGMENT_DURATION_US, LEAD,
    onSegment = { putSegment(it) },
    onSkipped = { idx -> skipped.add(idx) },
    playhead = { lock.withLock { coordinator?.prevIndex ?: baseIndex } },
)

fun prepare(initialSegmentIndex: Int) {
    committedConfig = configForQuality(quality)   // single rung here; Task 11b adds the measured loop
    val coord = HlsSegmentCoordinator(initialSegmentIndex, LEAD, READAHEAD, WAIT_MARGIN, BACK_BUFFER, RELOCATE_AFTER)
    lock.withLock { coordinator = coord }
    val pipe = newPipeline(initialSegmentIndex)
    lock.withLock { pipeline = pipe }
    pipe.start(initialSegmentIndex)
    // wait until PREBUFFER segments from initialSegmentIndex are cached (bounded; cancel on timeout)
    waitForFrontier(initialSegmentIndex + PREBUFFER - 1, timeoutMs = 20_000)
}
```
`putSegment(result)` (under `lock`): cache the bytes, validate `result.videoInit.avcC` against the committed init's avcC via `HlsTranscodeMath.avcConfigsMatch`; on first segment build `initSegment`; on a genuine mismatch, raise `onNeedsRecast(NeedsRecast("avcc-mismatch", ...))` and do NOT publish; **after a successful cache, set `frontier = maxOf(frontier, result.index)`** so the routing frontier only advances once the segment is actually cached (avoids routing a just-announced-but-not-yet-cached index to a one-off); then signal `produced`. (`HlsSegmentPipeline.frontier` stays internal/advisory — the session owns the routing frontier.)

- [ ] **Step 4: Rewrite `segmentBytes(index)` to route via the coordinator**

```kotlin
fun segmentBytes(index: Int): ByteArray? {
    if (index < 0 || index >= segmentCount) return null
    lock.lock()
    try {
        val coord = coordinator ?: return buildOneOff(index)   // no pipeline (fallback) -> per-segment
        val frontier = this.frontier                           // session-owned (highest CACHED index)
        val low = coord.prevIndex - BACK_BUFFER
        when (val d = coord.route(index, frontier, low) { i -> segmentCache.containsKey(i) }) {
            HlsSegmentCoordinator.Decision.ServeCached -> return segmentCache[index]
            is HlsSegmentCoordinator.Decision.WaitForProduction -> {
                if (index in skipped) return buildOneOff(index)     // pipeline dropped this index (IDR miss)
                val deadline = System.nanoTime() + 5_000_000_000L   // ~one build; pipeline runs > 1x realtime
                while (!segmentCache.containsKey(index) && index !in skipped && System.nanoTime() < deadline) {
                    produced.await(250, java.util.concurrent.TimeUnit.MILLISECONDS) // releases lock
                }
                return segmentCache[index] ?: buildOneOff(index)
            }
            is HlsSegmentCoordinator.Decision.OneOff -> {
                val bytes = buildOneOff(index)     // build + cache [index] FIRST
                d.rebaseTo?.let { reBase(it) }     // rebaseTo already excludes [index] (served above)
                return bytes
            }
        }
    } finally {
        if (lock.isHeldByCurrentThread) lock.unlock()
    }
}
```
The one-off helper, the serial-degrade fallback, and re-base — shown as real code (this is the
most concurrency-sensitive part):

```kotlin
private val inFlight = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.FutureTask<ByteArray>>()

// Called whether or not the caller holds `lock`. Releases it for the multi-second build, then
// re-acquires it (so segmentBytes' `finally` can unlock). Coalesces duplicate in-flight builds.
private fun buildOneOff(index: Int): ByteArray {
    val heldByMe = lock.isHeldByCurrentThread
    if (heldByMe) lock.unlock()
    try {
        val task = inFlight.computeIfAbsent(index) {
            java.util.concurrent.FutureTask { actuallyBuildOneOff(index) }
        }
        try {
            if (inFlight[index] === task) task.run()   // the creator runs it; concurrent callers just get()
            return task.get()
        } finally { inFlight.remove(index, task) }
    } finally {
        if (heldByMe) lock.lock()
    }
}

private fun actuallyBuildOneOff(index: Int): ByteArray = synchronized(oneOffLock) {  // serialize codec alloc
    val cfg = committedConfig!!
    val res = try {
        transcoder.transcodeRange(inputPath, probeResult, selectedAudioTrack,
            index * SEGMENT_DURATION_US, (index + 1) * SEGMENT_DURATION_US, copyAudio, cfg)
    } catch (e: Exception) {                         // 1+1 codec-limit: free the pipeline, retry serially
        serialSeekMode = true
        reBase(index)                                // cancels the pipeline (frees its codecs) + rebuilds at index
        transcoder.transcodeRange(inputPath, probeResult, selectedAudioTrack,
            index * SEGMENT_DURATION_US, (index + 1) * SEGMENT_DURATION_US, copyAudio, cfg)
    }
    val bytes = HlsMp4Builder.buildMediaSegment(index + 1, res.videoSamples, res.audioSamples, 33_333L, 21_333L)
    lock.withLock {
        if (videoInit != null && !HlsTranscodeMath.avcConfigsMatch(videoInit!!.avcC, res.video?.avcC)) {
            onNeedsRecast(NeedsRecast("avcc-mismatch", index.toLong() * SEGMENT_DURATION_US / 1000, quality))
        }
        segmentCache[index] = bytes      // cache ONLY -- do NOT advance `frontier` (it tracks pipeline production)
        produced.signalAll()             // a Condition must be signaled while holding its lock
    }
    bytes
}

// Cancel the old pipeline OUTSIDE the lock (its worker takes `lock` in putSegment/playhead; cancel()
// joins the worker, so holding `lock` during join would deadlock). Caller holds `lock`.
private fun reBase(baseIndex: Int) {
    val old = pipeline; pipeline = null
    lock.unlock()
    try {
        old?.cancel()                                // interrupt + join(2000) with the lock released
        val pipe = newPipeline(baseIndex)            // same construction as prepare()
        pipe.start(baseIndex)
        lock.lock(); pipeline = pipe
    } catch (t: Throwable) { lock.lock(); throw t }
}
```
- **`frontier` semantics (critical):** the routing `frontier` is advanced **only** in `putSegment`
  (pipeline-produced, contiguous segments). One-off builds cache bytes (so `isCached(index)` is true)
  but must **not** advance `frontier` — otherwise a seek-target one-off would make the next adjacent
  request look like `WaitForProduction` (inside `frontier + WAIT_MARGIN`), resetting the relocation
  run so the pipeline never follows the seek.
- **One-off priority for parallel bursts (conditional on the Task 0 trace).** If the Task 0 receiver
  trace shows segment requests arrive **in parallel**, add a priority policy: a non-target read-ahead
  request (`index != coordinator.prevIndex`) that finds a one-off already running returns **`503` +
  `Retry-After`** (via the `serveHls` 503 path Task 12 adds) instead of queuing. If requests are
  strictly sequential, coalescing alone suffices and this is skipped.

- [ ] **Step 5: `initBytes()` returns the committed init (built in `prepare`); `release()` cancels the pipeline**

```kotlin
fun initBytes(): ByteArray = lock.withLock { initSegment ?: error("init not ready; prepare() must run first") }
fun release() { pipeline?.cancel(); lock.withLock { segmentCache.clear(); initSegment = null; coordinator = null; pipeline = null } }
```

- [ ] **Step 6: Confirm compile + existing tests**

Run: `.\gradlew.bat assembleDebug --console=plain` then `.\gradlew.bat testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL. Test updates required:
- **Rewrite the `HlsTranscodeSessionTest` constructor helper** to the new signature. The current
  5-arg helper must become, e.g.:
```kotlin
private fun session(durationMs: Long, audioMime: String?, copyAudio: Boolean = false, subtitle: ByteArray? = null) =
    HlsTranscodeSession("/x.mkv", probe(durationMs, audioMime), null, copyAudio, subtitle,
        quality = CastQuality.AUTO,
        configForQuality = { CommittedEncoderConfig.derive(1920, 1080, 5_000_000, 24, it) })
```
- **Update the two existing Dolby-CODECS tests** in `HlsTranscodeSessionTest.kt`:
  `masterPlaylist_advertisesEac3WhenCopyAudio` and `masterPlaylist_advertisesAc3WhenCopyAudio` now
  emit `CODECS="avc1.640029,mp4a.40.2"` (HLS audio is always AAC; Dolby always routes to LIVE per
  `StreamingDecision`, never copied on HLS). Change both assertions to expect `mp4a.40.2`, or delete
  them (the behavior they tested can no longer occur).
- Tests that exercised lazy `initBytes()`/`segmentBytes()` building must call `prepare(0)` first, OR
  be narrowed to the pure surface (playlist text, bounds) — codec paths can't run in JVM. Anything
  needing real transcoding in JVM (impossible) should assert the routing contract via
  `HlsSegmentCoordinator` instead.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/storagecast/media/HlsTranscodeSession.kt app/src/test/java/com/storagecast/media/HlsTranscodeSessionTest.kt
git commit -m "feat(hls): wire HlsTranscodeSession to pipeline + coordinator + one-off"
```

---

## Task 10: Prepare-before-load in `castHls` + `setStreamDuration` unit fix

Run `prepare()` on a background worker before `remoteMediaClient.load(...)`, build the committed config (incl. resolved profile/level/encoder-name), register a `NeedsRecast` listener, and fix the duration unit bug.

**Files:**
- Modify: `app/src/main/java/com/storagecast/ui/VideoDetailActivity.kt`,
  `app/src/main/java/com/storagecast/ui/SettingsActivity.kt` (the `getCastQuality` read helper)

- [ ] **Step 1: Define the `configForQuality` resolver (the single Android codec-resolution owner).**
  First add the **read-side** preference helper so this task compiles before Task 11a builds the UI:
  add a `getCastQuality(context): String` companion to `SettingsActivity` (default `"auto"`) following
  the existing `getHlsSeeking` / `getRealtimeTranscode` companion pattern. Then add a private method
  that turns a `CastQuality` into a *fully-resolved* `CommittedEncoderConfig` —
  numeric derivation plus the chosen hardware AVC encoder name and explicit profile/level for the
  derived size. The session calls this per rung; the Activity owns it because it needs `MediaCodecInfo`:

```kotlin
private fun configForQuality(probe: MediaProbeResult, q: CastQuality): CommittedEncoderConfig {
    val v = probe.primaryVideo!!
    val base = CommittedEncoderConfig.derive(v.width, v.height, v.bitrate, v.frameRate.toInt(), q)
    val encoderName = EncoderFormatFactory.pickHardwareAvcEncoderName()
    // Pin explicit profile/level (spec invariant: identical avcC across both encoders). High@4.1
    // matches the playlist CODECS "avc1.640029" and covers every rung (<=1080p30). If the encoder
    // doesn't advertise it, fall back to UNSET (defaults) + the avcConfigsMatch / NeedsRecast backstop.
    val (profile, level) = EncoderFormatFactory.avcHighL41IfSupported(encoderName)
    return base.copy(profile = profile, level = level, encoderName = encoderName)
}

val quality = CastQuality.fromPref(SettingsActivity.getCastQuality(this))
```

- [ ] **Step 2: Build the session, run `prepare()` off-main via the activity coroutine, then `load()`
  on-main.** Use the codebase's coroutine idiom (`activityScope = CoroutineScope(SupervisorJob() +
  Dispatchers.Main)` already exists; there is **no** `Executor` field). Add two activity fields to hold
  the **active** HLS session + its base path (`private var activeHlsSession: HlsTranscodeSession? =
  null`, `private var activeHlsBasePath: String? = null`) so a later re-cast (Task 12) can reach it.
  Create three small helpers in the activity: `showPreparingUi()` / `hidePreparingUi()` (toggle a
  progress state) and `loadHlsOnReceiver(hlsBasePath, probeResult)` (the existing `MediaInfo` /
  `MediaLoadRequestData` build + `remoteMediaClient.load(...)`). **Evict/cancel the currently-active
  HLS session BEFORE preparing the new one** (spec: "any already-active HLS session is
  cancelled/evicted before the new one prepares") so two long-lived pipelines never coexist:

```kotlin
activeHlsSession?.release()    // cancel old pipeline + free its codecs before the new prepare()
activeHlsSession = null
val hlsSession = HlsTranscodeSession(
    video.path, probeResult, selectedAudioTrack, copyAudio, subtitleVtt,
    quality = quality,
    configForQuality = { q -> configForQuality(probeResult, q) },
    onNeedsRecast = { recast -> runOnUiThread { handleNeedsRecast(video, probeResult, recast) } },
)
showPreparingUi()
activityScope.launch {
    try {
        val initialSegmentIndex = (pendingSeekPositionMs / (HlsTranscodeSession.SEGMENT_DURATION_US / 1000)).toInt()
        withContext(Dispatchers.IO) { hlsSession.prepare(initialSegmentIndex) }
        hidePreparingUi()
        val hlsBasePath = service.registerHlsSession(video.title, hlsSession)
        activeHlsSession = hlsSession; activeHlsBasePath = hlsBasePath
        loadHlsOnReceiver(hlsBasePath, probeResult)
    } catch (t: Throwable) {
        hidePreparingUi()
        hlsSession.release()
        // surface a brief error; the user can retry / pick a lower Cast quality
    }
}
```

- [ ] **Step 3: Fix the `setStreamDuration` unit bug** (locate `setStreamDuration` in the existing
  `MediaInfo` build, now inside `loadHlsOnReceiver`) — the SDK expects **milliseconds**:

```kotlin
if (probeResult.durationMs > 0) setStreamDuration(probeResult.durationMs)   // was: durationMs * 1000
```

- [ ] **Step 4: Stub `handleNeedsRecast(video, probeResult, recast)`** — Task 12 implements the
  draining re-cast. For now, add the method signature returning early (so Task 10 compiles); Task 12
  fills the body.

- [ ] **Step 5: Confirm compile**

Run: `.\gradlew.bat assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/storagecast/ui/VideoDetailActivity.kt
git commit -m "feat(hls): prepare-before-load on background worker; fix setStreamDuration units"
```

---

## Task 11: Quality setting + measured fallback in `prepare()`

### Task 11a: "Cast quality" preference

**Files:**
- Modify: `app/src/main/java/com/storagecast/ui/SettingsActivity.kt`, `app/src/main/res/layout/activity_settings.xml`, `app/src/main/res/values/strings.xml`

- [ ] **Step 1:** Add a 4-option control (Auto / 1080p / 720p / 540p) writing SharedPreferences key `cast_quality` ∈ `auto`/`1080`/`720`/`540` (default `auto`). Add a `getCastQuality(context): String` companion helper to `SettingsActivity` following the existing `getHlsSeeking`/`getRealtimeTranscode` companion pattern (read it via `CastQuality.fromPref(SettingsActivity.getCastQuality(this))` in `castHls`). Match the existing Settings UI style (spinner or radio group).
- [ ] **Step 2:** `.\gradlew.bat assembleDebug --console=plain` → BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit `feat(hls): cast quality preference (auto/1080/720/540)`.

### Task 11b: Measured fallback inside `prepare()`

**Files:**
- Modify: `app/src/main/java/com/storagecast/media/HlsTranscodeSession.kt`

- [ ] **Step 1:** In `prepare()`, when `quality == AUTO`, drive a `ResolutionFallback(CastQuality.autoRungs(), RATIO_THRESHOLD)`:
  - For the current rung, set `committedConfig = configForQuality(rung)`, build the pipeline, and
    measure the **steady-state** build ratio = wall time for the frontier to advance from the
    *second* produced segment to the third (excludes the pre-roll-inflated first segment).
  - `fallback.evaluate(ratio)`: if it stepped down, `pipeline.cancel()`, set
    `committedConfig = configForQuality(fallback.current)`, rebuild the pipeline, re-measure.
  - On commit, continue priming `PREBUFFER` segments and capture init.
  - If a viability gate disables the pipeline (avcC mismatch), choose the rung from a cold
    per-segment ratio or a conservative default (P720). (`getMaxSupportedInstances() < 2` is *not* a
    disable — codec count only degrades seeks, per Task 9's serial-degrade.)
  - A manual quality uses `ResolutionFallback(listOf(thatRung))` (always commits, skips measurement).
- [ ] **Step 2:** `.\gradlew.bat assembleDebug --console=plain` then `.\gradlew.bat testDebugUnitTest --console=plain` → all PASS.
- [ ] **Step 3:** Commit `feat(hls): measured resolution fallback in prepare()`.

---

## Task 12: `NeedsRecast` listener → draining in-session re-cast

The avcC-mismatch / thermal-collapse trigger. `segmentBytes` (NanoHTTPD thread) can't call
`load(...)`; the session publishes to the activity-registered listener, and the swap drains the old
session (no hard-evict) so a straggler old-id request never 404s mid-transition.

**Files:**
- Modify: `app/src/main/java/com/storagecast/server/MediaServerService.kt`, `app/src/main/java/com/storagecast/ui/VideoDetailActivity.kt`

- [ ] **Step 1: Draining flag + `503` on the server.** Add a per-session `@Volatile var draining = false`
  to `HlsTranscodeSession`, set when `onNeedsRecast` fires. In `serveHls`, when the resolved session
  `draining`, return **`503`** with `Retry-After: 1` for its `seg{n}.m4s` / `init.mp4` requests
  (locate the existing dispatch + the `404 No HLS session` path; add the 503 branch before serving
  bytes). Cached bytes may still be served if present; new builds return 503.
- [ ] **Step 2: Non-evicting register + TTL drain.** Add
  `fun registerHlsSessionWithoutEvict(label, session): String` to `MediaServerService` (and the inner
  server) that registers the new id **without** removing prior sessions. Schedule eviction of the old
  session after a short TTL (e.g. 5 s) or on a swap-complete signal (first successful request against
  the new id), then `oldSession.release()`. (The existing hard-evict `registerHlsSession` stays for
  the **user-initiated fresh cast** path only.)
- [ ] **Step 3: `handleNeedsRecast`** (main thread): mark the stored old session draining
  (`activeHlsSession?.draining = true`), build a replacement `HlsTranscodeSession` (same title, new
  id) with `pendingSeekPositionMs = recast.startMs` and the recast quality, run `prepare()` off-main
  (as in Task 10), register it via `registerHlsSessionWithoutEvict`, update
  `activeHlsSession`/`activeHlsBasePath`, and `load(...)` the new url. The old id drains (503) until
  TTL.
- [ ] **Step 4:** `.\gradlew.bat assembleDebug --console=plain` → BUILD SUCCESSFUL.
- [ ] **Step 5:** Commit `feat(hls): draining in-session re-cast + 503 backoff`.

---

## Task 13: On-device verification (manual gate)

Not committed code — the acceptance gate. Run on the target chain (Snapdragon "parrot" → Mi TV "Living Room Kitchen TV", 192.168.1.57).

- [ ] **Step 1: Build + install**

```powershell
$env:ANDROID_HOME="D:\AndroidVMs\SDK"; $env:ANDROID_SDK_ROOT="D:\AndroidVMs\SDK"
.\gradlew.bat assembleDebug --console=plain
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

- [ ] **Step 2: Steady-state** — cast `Penguins of Madagascar` (1080p HEVC10 + AAC 7.1). Confirm sustained `PLAYING` with **no rebuffering over several minutes**; capture the per-segment build ratio (target `< 1.0`).
- [ ] **Step 3: Seeks** — a mid-range forward seek and a backward seek both resume promptly (within the receiver fetch timeout); the one-off serves the target, the pipeline re-bases and catches up.
- [ ] **Step 4: A/V sync** — validate sync over a multi-minute session **and across a re-base** (listen specifically at the one-off↔pipeline handoff segment after a seek for the ≤1–2 AAC-frame seam).
- [ ] **Step 5: Init compatibility** — confirm re-based and one-off segments decode cleanly against the committed `init.mp4` (no `avcConfigsMatch` warnings / no `NeedsRecast` on routine seeks).
- [ ] **Step 6: 2+2 codecs** — confirm two hardware codec sessions coexist during a seek (pipeline + one-off); if the device is 1+1-only, confirm the serial-degrade seek path works (one rebuffer after the target, then catches up).
- [ ] **Step 7: Duration** — confirm the receiver seek bar shows the correct duration (the `setStreamDuration` fix).
- [ ] **Step 8: 4K fallback stress** — cast a 4K Tigole HEVC source; confirm the AUTO fallback steps the resolution down at startup (e.g. 1080p→720p) and improves throughput (mechanism works; not that every 4K source is perfectly smooth).
- [ ] **Step 9:** Record results; if Step 2 fails the build-ratio target on this encoder, revisit the Task 0 frame-exact-IDR result (the pipeline path may not be viable here → per-segment fallback).

---

## Final self-review checklist (run after all tasks)

- [ ] **Spec coverage:** sequential pipeline (T8), one-off interchangeability + committed config (T3/T7/T9), coordination/relocation (T6/T9), frame-exact IDR + miss recovery (T0/T8), both-tracks drain (T2/T8), prepare-before-load (T10), measured fallback + quality (T1/T5/T11), selected-vs-primary audio (T4/T9), `setStreamDuration` fix (T10), `NeedsRecast`/re-cast (T12), device gates (T0/T13). Confirm each maps to a task; add tasks for any gap.
- [ ] **No placeholders:** every code step shows real code; every test step shows real assertions.
- [ ] **Type consistency:** `CommittedEncoderConfig`, `CastQuality`, `HlsSegmentCoordinator.Decision`, `HlsSegmentPipeline.SegmentResult`, `NeedsRecast` names/fields match across Tasks 1, 3, 6, 8, 9, 10, 12.
- [ ] **Full suite green:** `.\gradlew.bat testDebugUnitTest --console=plain` (existing 99 + new CastQuality/CommittedEncoderConfig/HlsSegmentCoordinator/ResolutionFallback/HlsTranscodeMath tests).
