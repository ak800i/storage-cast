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
- **Open Q3 (mid-stream collapse re-cast):** build the `NeedsRecast` **listener + a simple evict-and-recast** (Task 12). The TTL-bounded *draining* old-session lifecycle is an explicit **optional follow-up** (Task 12b) — skip unless device testing shows mid-transition 404s matter. The primary `NeedsRecast` trigger is an avcC mismatch (rare); mid-stream thermal collapse re-cast reuses the same listener.

---

## File Structure

**New files (pure, Android-free — fully unit-tested):**
- `app/src/main/java/com/storagecast/media/CastQuality.kt` — the quality enum + max-dimension mapping.
- `app/src/main/java/com/storagecast/media/CommittedEncoderConfig.kt` — the complete encoder-config value object shared by both builders.
- `app/src/main/java/com/storagecast/media/HlsSegmentCoordinator.kt` — the pure `segmentBytes` routing/relocation state machine.
- `app/src/main/java/com/storagecast/media/ResolutionFallback.kt` — the pure build-ratio step-down state machine.

**New file (Android — implement + on-device verify):**
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
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(51, rebaseTo = 51), d2) // first non-cached >= 50 is 51
    }

    @Test fun transientFallBehind_servesOneOff_butNeverRebases() {
        val c = coord(initial = 0)
        // steady playback advanced prevIndex to 30 via cache hits
        for (i in 0..30) c.route(i, frontier = i + 4, lowWatermark = i - 2, isCached = { it <= i + 4 })
        // pipeline briefly stalls: frontier=30, request 33 (> frontier+WAIT_MARGIN=32) but contiguous w/ playhead
        val d = c.route(index = 33, frontier = 30, lowWatermark = 28, isCached = { it <= 30 })
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(33, rebaseTo = null), d)
        // a couple more contiguous catch-up requests: still no rebase (jump <= READAHEAD from playhead)
        val d2 = c.route(index = 34, frontier = 31, lowWatermark = 29, isCached = { it <= 31 })
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(34, rebaseTo = null), d2)
    }

    @Test fun strayProbe_doesNotRebase() {
        val c = coord(initial = 40)
        c.route(index = 40, frontier = 44, lowWatermark = 38, isCached = { it <= 44 }) // playhead 40
        // lone seg0 probe (far below window) -> one-off, run=1
        val d = c.route(index = 0, frontier = 44, lowWatermark = 38, isCached = { it in 38..44 })
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(0, rebaseTo = null), d)
        // receiver resumes near playhead (cached) -> relocation run reset, never rebased
        val d2 = c.route(index = 45, frontier = 49, lowWatermark = 43, isCached = { it in 43..49 })
        assertEquals(HlsSegmentCoordinator.Decision.WaitForProduction(45), d2)
    }

    @Test fun backwardRewindBelowWindow_rebasesWhenSustained() {
        val c = coord(initial = 100)
        c.route(index = 100, frontier = 104, lowWatermark = 98, isCached = { it in 98..104 })
        // rewind to 90 (below lowWatermark, not cached) -> one-off, run=1
        val d1 = c.route(index = 90, frontier = 104, lowWatermark = 98, isCached = { it in 98..104 })
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(90, rebaseTo = null), d1)
        // adjacent 91 confirms -> rebase to 90 (first non-cached >= 90)
        val d2 = c.route(index = 91, frontier = 104, lowWatermark = 98, isCached = { it in 98..104 || it == 90 })
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(91, rebaseTo = 90), d2)
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
            rebaseTo = firstNonCached(relocAnchor!!, isCached)
            relocRun = 0; relocAnchor = null
        }
        prevIndex = index
        return Decision.OneOff(index, rebaseTo)
    }

    private fun firstNonCached(from: Int, isCached: (Int) -> Boolean): Int {
        var i = from
        while (isCached(i)) i++
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

- [ ] **Step 1: Add a `committedConfig` parameter to `transcodeRange`**

Change the signature (L47) to accept the config and the effective copy flag computed by the session:

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

- [ ] **Step 2: Build the video encoder `MediaFormat` from the committed config** (replace the hardwired block at L104–122)

```kotlin
val format = MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME, committedConfig.width, committedConfig.height).apply {
    setInteger(MediaFormat.KEY_BIT_RATE, committedConfig.bitrate)
    setInteger(MediaFormat.KEY_FRAME_RATE, committedConfig.frameRate)
    // 6s GOP (was 1 = all-IDR). The first frame of THIS segment is forced to an IDR below.
    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, committedConfig.iFrameIntervalSec)
    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    if (committedConfig.profile != CommittedEncoderConfig.UNSET) {
        setInteger(MediaFormat.KEY_PROFILE, committedConfig.profile)
        setInteger(MediaFormat.KEY_LEVEL, committedConfig.level)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) setInteger(MediaFormat.KEY_LATENCY, 1)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
}
```

- [ ] **Step 3: Force an IDR only at the first emitted segment frame.** Because `KEY_I_FRAME_INTERVAL` is now 6 (not 1), a fresh encoder still emits its first frame as an IDR (segment-start invariant), but request it explicitly to be safe — after `encoder.start()` and the input surface is connected, set:

```kotlin
encoder.setParameters(android.os.Bundle().apply {
    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
})
```

- [ ] **Step 4: Create the encoder by the committed name when present** (modify `createVideoEncoder()`, L394)

```kotlin
private fun createVideoEncoder(committedConfig: CommittedEncoderConfig): MediaCodec {
    committedConfig.encoderName?.let { name ->
        runCatching { return MediaCodec.createByCodecName(name) }
    }
    // fall back to the existing hardware-by-type selection
    ...existing body...
}
```
Pass `committedConfig` from `transcodeVideoRange`.

- [ ] **Step 5: Replace the lossy AAC copy with no-PCM-loss feeding** (replace L265–272). Mirror the live path's `AudioEncoderFeeder` semantics: queue all decoded PCM and feed it across multiple encoder input buffers; timestamp encoded output as `basePtsUs + framesSent * 1_000_000L / sampleRate`, where `basePtsUs` is the first decoded PCM frame's **absolute source PTS** (so segments are audio-interchangeable). Port the `AudioEncoderFeeder` loop from [TranscodeStreamer.kt L582–652](../../../app/src/main/java/com/storagecast/media/TranscodeStreamer.kt) into a private helper in `HlsSegmentTranscoder`, replacing the single-buffer `minOf` copy. Keep `KEY_MAX_OUTPUT_CHANNEL_COUNT = 2` on the decoder for downmix.

- [ ] **Step 6: Confirm it compiles and existing tests still pass**

Run: `.\gradlew.bat assembleDebug --console=plain` then `.\gradlew.bat testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL; all existing tests PASS (`HlsTranscodeSessionTest` may need its `transcodeRange` call updated — pass a default `CommittedEncoderConfig.derive(...)`; if `HlsTranscodeSession` calls it, that updates in Task 9).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/storagecast/media/HlsSegmentTranscoder.kt
git commit -m "feat(hls): parameterize one-off builder from committed config; no-PCM-loss AAC"
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
    /** Called with each finished segment (index, bytes, videoInit, audioInit, avcMatchesCommitted). */
    private val onSegment: (SegmentResult) -> Unit,
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
  - Configure the encoder from `committedConfig` (same code path as Task 7 — extract a shared `EncoderConfigurator` or duplicate deliberately to keep the pipeline self-contained; per spec non-goal, prefer a small private helper inside the pipeline). Create encoder → input surface → decoder configured with that surface (encoder-before-decoder, as `TranscodeStreamer` does).
  - For each decoded output frame at `ptsUs`: if `HlsTranscodeMath.crossesBoundary(prevPts, ptsUs, nextBoundaryUs)`, call `encoder.setParameters(REQUEST_SYNC_FRAME)` **before** `decoder.releaseOutputBuffer(idx, true)`, then advance `nextBoundaryUs += segDurUs`.
  - Accumulate encoded video samples (AVCC via `HlsMp4Builder.ensureAvcc`) and AAC samples into per-segment buckets keyed by `HlsTranscodeMath.segmentIndexForPts(samplePts, segDurUs)`.
  - When `HlsTranscodeMath.segmentDrained(videoMaxPts, audioMaxPts, (segIndex+1)*segDurUs)` for the current `segIndex`, assemble `HlsMp4Builder.buildMediaSegment(sequenceNumber = segIndex + 1, videoSamples, audioSamples, 33_333L, 21_333L)`, capture `videoInit`/`audioInit` on the first segment, set `frontier = segIndex`, and invoke `onSegment(...)`.
  - **Boundary-IDR miss recovery:** when a segment is assembled, verify its first video sample is a keyframe. If not, drop that segment's pipeline bytes (do **not** publish), log a miss, and let the session serve that index via the one-off builder (it forces an IDR). Continue the pipeline.
  - **Back-pressure:** before producing `segIndex`, if `segIndex > playhead() + lead`, park briefly (`Thread.sleep(20)` loop) until the playhead advances or cancelled.
  - Audio uses the same no-PCM-loss / absolute-source-PTS feeder as Task 7 (share the private helper).
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
    private val committedConfig: CommittedEncoderConfig,
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
```
Bump `MAX_CACHED_SEGMENTS` to `10`. Compute `audioCodecAttr` via `HlsTranscodeMath.hlsAudioCodecAttr()` (always `mp4a.40.2`).

- [ ] **Step 3: Add `prepare(initialSegmentIndex)`** — builds the pipeline, primes `PREBUFFER` segments, captures init. (Called from `castHls` on a background thread.)

```kotlin
fun prepare(initialSegmentIndex: Int) {
    val coord = HlsSegmentCoordinator(initialSegmentIndex, LEAD, READAHEAD, WAIT_MARGIN, BACK_BUFFER, RELOCATE_AFTER)
    val pipe = HlsSegmentPipeline(
        inputPath, probeResult, selectedAudioTrack,
        copyAudio = HlsTranscodeMath.effectiveCopyAudio(copyAudio, (selectedAudioTrack ?: probeResult.primaryAudio)?.mime, (selectedAudioTrack ?: probeResult.primaryAudio)?.channelCount ?: 2),
        committedConfig, SEGMENT_DURATION_US, LEAD,
        onSegment = { putSegment(it) },
        playhead = { lock.withLock { coordinator?.prevIndex ?: initialSegmentIndex } },
    )
    lock.withLock { coordinator = coord; pipeline = pipe }
    pipe.start(initialSegmentIndex)
    // wait until PREBUFFER segments from initialSegmentIndex are cached (bounded; cancel on timeout)
    waitForFrontier(initialSegmentIndex + PREBUFFER - 1, timeoutMs = 20_000)
}
```
`putSegment(result)` (under `lock`): cache the bytes, validate `result.videoInit.avcC` against the committed init's avcC via `HlsTranscodeMath.avcConfigsMatch`; on first segment build `initSegment`; on a genuine mismatch, raise `onNeedsRecast(NeedsRecast("avcc-mismatch", ...))` and do NOT publish; signal `produced`.

- [ ] **Step 4: Rewrite `segmentBytes(index)` to route via the coordinator**

```kotlin
fun segmentBytes(index: Int): ByteArray? {
    if (index < 0 || index >= segmentCount) return null
    lock.lock()
    try {
        val coord = coordinator ?: return buildOneOff(index)   // no pipeline (fallback) -> per-segment
        val frontier = pipeline?.frontier ?: -1
        val low = coord.prevIndex - BACK_BUFFER
        when (val d = coord.route(index, frontier, low) { i -> segmentCache.containsKey(i) }) {
            HlsSegmentCoordinator.Decision.ServeCached -> return segmentCache[index]
            is HlsSegmentCoordinator.Decision.WaitForProduction -> {
                val deadline = System.nanoTime() + 15_000_000_000L
                while (!segmentCache.containsKey(index) && System.nanoTime() < deadline) {
                    produced.await(500, java.util.concurrent.TimeUnit.MILLISECONDS) // releases lock
                }
                return segmentCache[index] ?: buildOneOffUnlocked(index)
            }
            is HlsSegmentCoordinator.Decision.OneOff -> {
                d.rebaseTo?.let { reBase(it) }
                return buildOneOffUnlocked(index)
            }
        }
    } finally {
        if (lock.isHeldByCurrentThread) lock.unlock()
    }
}
```
- `buildOneOffUnlocked(index)`: temporarily `lock.unlock()`, serialize one-off builds behind a separate `oneOffLock`, call `transcoder.transcodeRange(..., committedConfig)`, build the segment via `HlsMp4Builder`, validate avcC vs committed init (raise `NeedsRecast` on mismatch), cache + signal `produced`, re-`lock.lock()`, return bytes. Coalesce duplicate in-flight builds per index (a `ConcurrentHashMap<Int, FutureTask<ByteArray>>`).
- `reBase(baseIndex)`: `pipeline?.cancel()`, build a new `HlsSegmentPipeline` at `baseIndex` (decode base = `baseIndex` which the coordinator already set to the first non-cached index), `start(baseIndex)`.

- [ ] **Step 5: `initBytes()` returns the committed init (built in `prepare`); `release()` cancels the pipeline**

```kotlin
fun initBytes(): ByteArray = lock.withLock { initSegment ?: error("init not ready; prepare() must run first") }
fun release() { pipeline?.cancel(); lock.withLock { segmentCache.clear(); initSegment = null; coordinator = null; pipeline = null } }
```

- [ ] **Step 6: Confirm compile + existing tests**

Run: `.\gradlew.bat assembleDebug --console=plain` then `.\gradlew.bat testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL. Update `HlsTranscodeSessionTest.kt` constructor calls to pass a `CommittedEncoderConfig.derive(...)` and (where it asserted on `initBytes()`/`segmentBytes()` building lazily) to call `prepare(0)` first — keep these tests focused on the pure surface (playlist text, bounds), since codec paths can't run in JVM. If a test required actual transcoding in JVM (it cannot), convert it to assert the routing contract via `HlsSegmentCoordinator` instead.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/storagecast/media/HlsTranscodeSession.kt app/src/test/java/com/storagecast/media/HlsTranscodeSessionTest.kt
git commit -m "feat(hls): wire HlsTranscodeSession to pipeline + coordinator + one-off"
```

---

## Task 10: Prepare-before-load in `castHls` + `setStreamDuration` unit fix

Run `prepare()` on a background worker before `remoteMediaClient.load(...)`, build the committed config (incl. resolved profile/level/encoder-name), register a `NeedsRecast` listener, and fix the duration unit bug.

**Files:**
- Modify: `app/src/main/java/com/storagecast/ui/VideoDetailActivity.kt`

- [ ] **Step 1: Resolve the committed config (Android side) before building the session.** Add a small Android helper (e.g. in a new private method or `CastCompatibility`) that picks the hardware AVC encoder name and queries its supported `CodecProfileLevel` for the derived size, returning a `CommittedEncoderConfig` with `profile`/`level`/`encoderName` filled:

```kotlin
val v = probeResult.primaryVideo!!
val quality = CastQuality.fromPref(prefs.getString("cast_quality", "auto"))
val baseConfig = CommittedEncoderConfig.derive(v.width, v.height, v.bitrate, v.frameRate.toInt(), quality)
val committed = resolveEncoderProfileLevel(baseConfig)   // fills profile/level/encoderName (AVCProfileHigh + level for size)
```

- [ ] **Step 2: Build the session with the committed config + a `NeedsRecast` listener, run `prepare()` off-main, then `load()` on-main.** Replace the body of `castHls` (L1853+) so that after constructing `hlsSession`:

```kotlin
val hlsSession = HlsTranscodeSession(
    video.path, probeResult, selectedAudioTrack, copyAudio, subtitleVtt,
    committedConfig = committed,
    onNeedsRecast = { recast -> runOnUiThread { handleNeedsRecast(video, probeResult, recast) } },
)
showPreparingUi()
backgroundExecutor.execute {
    try {
        val initialSegmentIndex = (pendingSeekPositionMs / (HlsTranscodeSession.SEGMENT_DURATION_US / 1000)).toInt()
        hlsSession.prepare(initialSegmentIndex)
        runOnUiThread {
            hidePreparingUi()
            val hlsBasePath = service.registerHlsSession(video.title, hlsSession)
            loadHlsOnReceiver(hlsBasePath, probeResult)   // builds MediaInfo + load(...)
        }
    } catch (t: Throwable) {
        runOnUiThread { hidePreparingUi(); /* surface error / fall back */ }
    }
}
```
`loadHlsOnReceiver` contains the existing `MediaInfo`/`MediaLoadRequestData` build (L1896–1919).

- [ ] **Step 3: Fix the `setStreamDuration` unit bug** (L1910) — the SDK expects **milliseconds**:

```kotlin
if (probeResult.durationMs > 0) setStreamDuration(probeResult.durationMs)   // was: durationMs * 1000
```

- [ ] **Step 4: `handleNeedsRecast`** (minimal — Task 12 expands): cancel the old session via `service` and re-call `castHls` with the recast's start position + quality.

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

- [ ] **Step 1:** Add a 4-option control (Auto / 1080p / 720p / 540p) writing SharedPreferences key `cast_quality` ∈ `auto`/`1080`/`720`/`540` (default `auto`). Read it in `castHls` (Task 10, Step 1). Match the existing Settings UI style (spinner or radio group).
- [ ] **Step 2:** `.\gradlew.bat assembleDebug --console=plain` → BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit `feat(hls): cast quality preference (auto/1080/720/540)`.

### Task 11b: Measured fallback inside `prepare()`

**Files:**
- Modify: `app/src/main/java/com/storagecast/media/HlsTranscodeSession.kt`

- [ ] **Step 1:** In `prepare()`, when `quality == AUTO`, drive a `ResolutionFallback(CastQuality.autoRungs(), RATIO_THRESHOLD)`:
  - Build the pipeline at the current rung; measure the **steady-state** build ratio = wall time for the frontier to advance from the *second* produced segment to the third (excludes the pre-roll-inflated first segment).
  - `fallback.evaluate(ratio)`: if it stepped down, `pipeline.cancel()`, rebuild the committed config at the new rung (`CommittedEncoderConfig.derive(..., newRung)` + resolve profile/level/name), and re-measure.
  - On commit, continue priming `PREBUFFER` segments and capture init.
  - If a viability gate disabled the pipeline (avcC mismatch / `getMaxSupportedInstances() < 2` is *not* a disable per spec — only frame-exact-IDR/avcC are), choose the rung from a cold per-segment ratio or a conservative default (P720).
  - A manual quality uses `ResolutionFallback(listOf(thatRung))` (always commits, skips measurement).
- [ ] **Step 2:** `.\gradlew.bat assembleDebug --console=plain` then `.\gradlew.bat testDebugUnitTest --console=plain` → all PASS.
- [ ] **Step 3:** Commit `feat(hls): measured resolution fallback in prepare()`.

---

## Task 12: `NeedsRecast` listener → re-cast (minimal)

The avcC-mismatch / collapse trigger. `segmentBytes` (NanoHTTPD thread) can't call `load(...)`; the session publishes to the activity-registered listener.

**Files:**
- Modify: `app/src/main/java/com/storagecast/server/MediaServerService.kt`, `app/src/main/java/com/storagecast/ui/VideoDetailActivity.kt`

- [ ] **Step 1:** When a `NeedsRecast` is in flight for a session, `serveHls` returns **`503`** with a `Retry-After: 1` header for that session's in-flight segment request (instead of 200/500), so the receiver backs off while the swap happens. Add a per-session `@Volatile recasting` flag set when `onNeedsRecast` fires.
- [ ] **Step 2:** `handleNeedsRecast` (Task 10 Step 4) on the main thread: cancel/evict the old session and re-run `castHls(video, probeResult, copyAudio)` with `pendingSeekPositionMs = recast.startMs` and the recast quality. (This is the simple **evict-and-recast**; the receiver re-requests `init.mp4`/segments against the new session id.)
- [ ] **Step 3:** `.\gradlew.bat assembleDebug --console=plain` → BUILD SUCCESSFUL.
- [ ] **Step 4:** Commit `feat(hls): NeedsRecast listener + evict-and-recast; 503 backoff`.

### Task 12b (OPTIONAL — only if device testing shows mid-transition 404s)

- [ ] Add a `registerHlsSessionWithoutEvict(...)` variant + a TTL-bounded **draining** state: the old session id keeps returning safe cached bytes / `503` until a swap-complete signal or TTL, then it is evicted. Wire the in-session re-cast (config change / collapse) to use it instead of hard-evict. Commit `feat(hls): draining in-session re-cast lifecycle`. Skip unless the request-ordering trace / device test shows a real mid-transition 404.

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
