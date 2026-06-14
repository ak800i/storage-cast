# Manual Realtime Transcoding Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a manual, global, sticky "Realtime transcoding" toggle (a checkable overflow menu item on the video detail screen) that forces full transcoding via the existing `TranscodeStreamer` when on, while leaving default playback unchanged when off.

**Architecture:** Persist a single boolean in the existing `storagecast_settings` SharedPreferences (companion get/set helpers on `SettingsActivity`, mirroring the `filter_short_videos` pattern). Surface it as a checkable item in `video_detail_menu.xml`, synced in `onPrepareOptionsMenu` and flipped in `onOptionsItemSelected`. Add one early branch in `checkCompatibilityAndCast` that, when the flag is on, calls the already-existing `startTranscoding(video, probeResult)` and returns before the compatibility check. No new server endpoints, wire formats, or transcode engine changes.

**Tech Stack:** Kotlin, Android (AppCompat, ViewBinding), Android `MediaCodec`-based `TranscodeStreamer`, Google Cast framework, NanoHTTPD.

---

## Reference: Spec

Approved spec: [docs/superpowers/specs/2026-06-14-manual-realtime-transcoding-design.md](../specs/2026-06-14-manual-realtime-transcoding-design.md)

## Reference: Existing code touchpoints (verified)

- `SettingsActivity` companion holds `PREFS_NAME = "storagecast_settings"`, `KEY_FILTER_SHORT_VIDEOS`, and a static helper `getMinDurationMs(context)` — the pattern to copy. See [SettingsActivity.kt](../../../app/src/main/java/com/storagecast/ui/SettingsActivity.kt) lines 15-30.
- `checkCompatibilityAndCast(video)` probes, returns early via `castVideo` on null probe, sets `cachedProbeResult = probeResult`, then calls `castCompatibility.checkCompatibility(...)`. Insertion point is **between** `cachedProbeResult = probeResult` and the `val result = castCompatibility.checkCompatibility(probeResult)` line. See [VideoDetailActivity.kt](../../../app/src/main/java/com/storagecast/ui/VideoDetailActivity.kt) lines 1547-1571.
- `startTranscoding(video: VideoItem, probeResult: MediaProbeResult)` already exists and runs the full-transcode → live-MKV path; it reads the `selectedAudioTrack` field internally. See [VideoDetailActivity.kt](../../../app/src/main/java/com/storagecast/ui/VideoDetailActivity.kt) line 1601.
- `onPrepareOptionsMenu` and `onOptionsItemSelected` exist and use the `menu.findItem(...)` / `when (item.itemId)` patterns. See [VideoDetailActivity.kt](../../../app/src/main/java/com/storagecast/ui/VideoDetailActivity.kt) lines 2037-2068.
- `video_detail_menu.xml` holds overflow items with `app:showAsAction="never"`. See [video_detail_menu.xml](../../../app/src/main/res/menu/video_detail_menu.xml).
- `strings.xml` already defines `transcode`, `transcode_failed`, etc. See [strings.xml](../../../app/src/main/res/values/strings.xml) lines 53-57.

## File Structure

- **Modify** `app/src/main/java/com/storagecast/ui/SettingsActivity.kt` — add `KEY_REALTIME_TRANSCODE` / `DEFAULT_REALTIME_TRANSCODE` constants and `getRealtimeTranscode` / `setRealtimeTranscode` companion helpers.
- **Modify** `app/src/main/res/values/strings.xml` — add menu label + two toast strings.
- **Modify** `app/src/main/res/menu/video_detail_menu.xml` — add the checkable `action_realtime_transcode` item.
- **Modify** `app/src/main/java/com/storagecast/ui/VideoDetailActivity.kt` — sync checked state in `onPrepareOptionsMenu`, handle the item in `onOptionsItemSelected`, and add the early transcode branch in `checkCompatibilityAndCast`.

## Testing Note

This project has no unit-test harness wired for these UI/Cast paths (verification in the spec is build + manual on-device). There is no `src/test` or `src/androidTest` source set in use for this flow, and the transcode/Cast paths require hardware codecs and a live Cast session. Therefore each task's verification is a **compile check via `./gradlew assembleDebug`** plus the manual on-device checklist in the final task — not automated tests. Do not invent a test framework; follow the existing project convention.

Run the build from the repo root on Windows with `.\gradlew.bat assembleDebug` (or `./gradlew assembleDebug` in a POSIX shell).

---

### Task 1: Add persisted flag helpers to SettingsActivity

**Files:**
- Modify: `app/src/main/java/com/storagecast/ui/SettingsActivity.kt` (companion object, lines 15-30)

- [ ] **Step 1: Add constants and companion helpers**

In the `companion object`, add the two constants next to the existing `KEY_*` constants and add the two helper functions next to `getMinDurationMs`. The resulting companion object should read:

```kotlin
    companion object {
        const val PREFS_NAME = "storagecast_settings"
        const val KEY_FILTER_SHORT_VIDEOS = "filter_short_videos"
        const val KEY_MIN_DURATION_MINUTES = "min_duration_minutes"
        const val DEFAULT_MIN_DURATION_MINUTES = 18
        const val KEY_REALTIME_TRANSCODE = "realtime_transcode"
        const val DEFAULT_REALTIME_TRANSCODE = false

        private const val OPENSUBTITLES_PREFS = "opensubtitles"

        fun getMinDurationMs(context: Context): Long {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_FILTER_SHORT_VIDEOS, true)) return 0L
            val minutes = prefs.getInt(KEY_MIN_DURATION_MINUTES, DEFAULT_MIN_DURATION_MINUTES)
            return minutes * 60L * 1000L
        }

        fun getRealtimeTranscode(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_REALTIME_TRANSCODE, DEFAULT_REALTIME_TRANSCODE)
        }

        fun setRealtimeTranscode(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_REALTIME_TRANSCODE, enabled)
                .apply()
        }
    }
```

- [ ] **Step 2: Compile to verify it builds**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL (no new errors in `SettingsActivity.kt`).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/storagecast/ui/SettingsActivity.kt
git commit -m "feat: add realtime transcode preference helpers"
```

---

### Task 2: Add string resources

**Files:**
- Modify: `app/src/main/res/values/strings.xml` (near the existing transcode strings, lines 53-57)

- [ ] **Step 1: Add the three strings**

Immediately after the existing `<string name="transcode_failed">Transcoding failed: %s</string>` line, add:

```xml
    <string name="realtime_transcode">Realtime transcoding</string>
    <string name="realtime_transcode_on">Realtime transcoding on</string>
    <string name="realtime_transcode_off">Realtime transcoding off</string>
```

- [ ] **Step 2: Compile to verify resources are valid**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL (resource IDs `R.string.realtime_transcode`, `R.string.realtime_transcode_on`, `R.string.realtime_transcode_off` generated).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat: add realtime transcode menu and toast strings"
```

---

### Task 3: Add the checkable menu item

**Files:**
- Modify: `app/src/main/res/menu/video_detail_menu.xml`

- [ ] **Step 1: Add a checkable overflow item**

Add the new item directly after the `media_route_menu_item` (before `action_save_subtitle`) so the resulting menu reads:

```xml
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <item
        android:id="@+id/media_route_menu_item"
        android:title="Cast"
        app:actionProviderClass="androidx.mediarouter.app.MediaRouteActionProvider"
        app:showAsAction="always" />
    <item
        android:id="@+id/action_realtime_transcode"
        android:title="@string/realtime_transcode"
        android:checkable="true"
        app:showAsAction="never" />
    <item
        android:id="@+id/action_save_subtitle"
        android:title="@string/save_subtitle"
        app:showAsAction="never" />
    <item
        android:id="@+id/action_settings"
        android:title="@string/settings"
        app:showAsAction="never" />
    <item
        android:id="@+id/action_logs"
        android:title="@string/view_logs"
        app:showAsAction="never" />
</menu>
```

- [ ] **Step 2: Compile to verify the menu inflates**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL (resource ID `R.id.action_realtime_transcode` generated).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/menu/video_detail_menu.xml
git commit -m "feat: add realtime transcode checkable menu item"
```

---

### Task 4: Sync checked state and handle the menu toggle

**Files:**
- Modify: `app/src/main/java/com/storagecast/ui/VideoDetailActivity.kt` (`onPrepareOptionsMenu` ~line 2037, `onOptionsItemSelected` ~line 2042)

- [ ] **Step 1: Sync the checked state in onPrepareOptionsMenu**

Replace the existing `onPrepareOptionsMenu` body so it also sets the checkmark from the persisted flag:

```kotlin
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_save_subtitle)?.isVisible = downloadedSubtitleFile != null
        menu.findItem(R.id.action_realtime_transcode)?.isChecked =
            SettingsActivity.getRealtimeTranscode(this)
        return super.onPrepareOptionsMenu(menu)
    }
```

- [ ] **Step 2: Handle the toggle in onOptionsItemSelected**

Add a new branch to the `when (item.itemId)` block (place it before the `else ->` branch):

```kotlin
            R.id.action_realtime_transcode -> {
                val enabled = !item.isChecked
                item.isChecked = enabled
                SettingsActivity.setRealtimeTranscode(this, enabled)
                Toast.makeText(
                    this,
                    if (enabled) R.string.realtime_transcode_on else R.string.realtime_transcode_off,
                    Toast.LENGTH_SHORT
                ).show()
                AppLogger.info(TAG, "Realtime transcoding toggled: $enabled")
                true
            }
```

- [ ] **Step 3: Compile to verify it builds**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL. (`Toast`, `AppLogger`, and `TAG` are already imported/used in this file.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/storagecast/ui/VideoDetailActivity.kt
git commit -m "feat: wire realtime transcode menu toggle state and handler"
```

---

### Task 5: Force transcode in checkCompatibilityAndCast when the flag is on

**Files:**
- Modify: `app/src/main/java/com/storagecast/ui/VideoDetailActivity.kt` (`checkCompatibilityAndCast` ~lines 1547-1571)

- [ ] **Step 1: Add the early transcode branch**

In `checkCompatibilityAndCast`, insert the flag check immediately after `cachedProbeResult = probeResult` and before `val result = castCompatibility.checkCompatibility(probeResult)`. The method body should read:

```kotlin
    private fun checkCompatibilityAndCast(video: VideoItem) {
        binding.progressBar.visibility = View.VISIBLE
        activityScope.launch {
            val probeResult = cachedProbeResult ?: withContext(Dispatchers.IO) {
                mediaProber.probe(video.path)
            }
            binding.progressBar.visibility = View.GONE

            if (probeResult == null) {
                AppLogger.warn(TAG, "Media probe failed, casting directly")
                Toast.makeText(this@VideoDetailActivity, R.string.probe_failed, Toast.LENGTH_SHORT).show()
                castVideo(video, getEffectiveSubtitleFile())
                return@launch
            }
            cachedProbeResult = probeResult

            if (SettingsActivity.getRealtimeTranscode(this@VideoDetailActivity)) {
                AppLogger.info(TAG, "Realtime transcoding enabled, forcing transcode")
                startTranscoding(video, probeResult)
                return@launch
            }

            val result = castCompatibility.checkCompatibility(probeResult)
            if (result.isFullyCompatible) {
                AppLogger.info(TAG, "All codecs compatible, casting directly")
                directStreamOrRemux(video)
            } else {
                showCodecCompatibilityDialog(video, probeResult, result)
            }
        }
    }
```

- [ ] **Step 2: Compile to verify it builds**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/storagecast/ui/VideoDetailActivity.kt
git commit -m "feat: force realtime transcode on cast when toggle enabled"
```

---

### Task 6: Manual on-device verification

**Files:** none (verification only)

- [ ] **Step 1: Build and install the debug APK on a device with a Cast target available**

Run: `.\gradlew.bat installDebug`
Expected: BUILD SUCCESSFUL and app installed.

- [ ] **Step 2: Verify toggle OFF preserves default behavior**

With the menu item **unchecked** (default), cast a fully compatible file → it should direct-stream (no transcode), and cast a file with an unsupported codec → the codec-incompatibility dialog should appear. This is unchanged behavior.

- [ ] **Step 3: Verify toggle ON forces transcode**

Open the overflow menu, tap **Realtime transcoding** (confirm the "Realtime transcoding on" toast and the checkmark). Cast a normally-compatible file → it should now cast via the transcoded live MKV stream (no codec dialog), starting from position 0.

- [ ] **Step 4: Verify persistence across restart**

With the toggle ON, fully close and relaunch the app, open a video detail screen, open the overflow menu → the **Realtime transcoding** item should still be checked. Toggle it OFF, confirm the "Realtime transcoding off" toast, and confirm the checkmark state persists across another relaunch.

- [ ] **Step 5: Verify the documented EBML limitation (if such a file is available)**

If a test MKV exists whose audio is recoverable only via the EBML fallback (e.g. AC-3 that `MediaExtractor` doesn't enumerate), confirm that casting it with the toggle ON surfaces the existing "Transcoding failed" toast rather than playing — matching the Known Limitations section of the spec. (Skip if no such file is available.)

- [ ] **Step 6: Final commit (if any verification-driven tweaks were needed)**

Only if a fix was required during verification:

```bash
git add -A
git commit -m "fix: address realtime transcode verification findings"
```

---

## Self-Review

**Spec coverage:**
- Persisted global flag + get/set helpers → Task 1. ✓
- Menu label + on/off toast strings → Task 2. ✓
- Checkable menu item → Task 3. ✓
- Checked-state sync (`onPrepareOptionsMenu`) + toggle handler (`onOptionsItemSelected`) + persistence + toast → Task 4. ✓
- Early transcode branch in `checkCompatibilityAndCast` (after non-null cached probe, before compatibility check), reusing `startTranscoding` → Task 5. ✓
- Toggle evaluated on every entry into `checkCompatibilityAndCast` (incl. `applyLiveAudioTrackChange` re-cast) → satisfied automatically by the Task 5 branch location; covered in verification Step 3/Known Limitations. ✓
- Default playback unchanged when off → Task 5 keeps the original branch; verified in Task 6 Step 2. ✓
- Verification (build + manual on-device, incl. EBML limitation) → Task 6. ✓

**Placeholder scan:** No "TBD"/"TODO"/"handle edge cases"/"similar to Task N" — every code step shows the full code. ✓

**Type/name consistency:** `KEY_REALTIME_TRANSCODE`, `DEFAULT_REALTIME_TRANSCODE`, `getRealtimeTranscode`, `setRealtimeTranscode`, `R.id.action_realtime_transcode`, `R.string.realtime_transcode` / `_on` / `_off`, and `startTranscoding(video, probeResult)` are used identically across Tasks 1-5. ✓
