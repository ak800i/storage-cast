# UX/UI Improvement Opportunities

A comprehensive analysis of the StorageCast codebase, identifying key areas for user
experience and interface enhancement.

---

## Accessibility & Internationalization

### 1. Hardcoded content descriptions in layouts
Several layout files use hardcoded English strings instead of string resources for
`contentDescription` and `text` attributes. This breaks accessibility for non-English
users and prevents proper localization.

**Files:** `item_video.xml` (`"Video thumbnail"`, `"Subtitles"`), `item_folder.xml`
(`"Folder"`), `dialog_subtitle_tracks.xml` (`"Select subtitle track"`)

### 2. Folder video count uses hardcoded English pluralization
`BrowseAdapter.kt` constructs `"$count video"` / `"$count videos"` via string
concatenation instead of Android's `<plurals>` resource, which handles
language-specific plural rules (e.g. Russian has three plural forms).

**File:** `BrowseAdapter.kt:68`

---

## Visual Design & Material Consistency

### 3. Settings screen uses plain EditText instead of TextInputLayout
The Settings and OpenSubtitles credential inputs use bare `EditText` widgets without
`TextInputLayout` wrappers. This means no floating hint labels, no proper Material
styling, and inconsistent look compared to other Material components in the app.

**File:** `activity_settings.xml`

### 4. Save credentials button label is "OK"
The OpenSubtitles save button uses `@android:string/ok` which is ambiguous. A
descriptive label like "Save" would better communicate the action.

**File:** `activity_settings.xml:133`

### 5. Empty state is text-only
The "No videos found" empty state in `activity_main.xml` is a plain `TextView` with no
icon or guidance. Adding an icon and a helpful message ("Grant storage permission" or
"No video files on this device") would improve first-run experience.

**File:** `activity_main.xml:30–40`

### 6. No video thumbnail placeholder
When thumbnail loading fails (e.g., corrupt file, no thumbnail generated), the
`ImageView` shows nothing — just the dark background. A default placeholder icon
(movie/film icon) would provide visual feedback that the item is a video.

**File:** `BrowseAdapter.kt:107–109`, `VideoDetailActivity.kt:432–434`

---

## Interaction & Feedback

### 7. Transport controls enabled when not connected to Cast
Play, Pause, Stop, Rewind, Forward buttons are always visually enabled. Pressing them
when not casting shows a "Not connected" toast, but nothing communicates the disabled
state visually. The controls should appear dimmed/disabled when no Cast session is
active.

**File:** `VideoDetailActivity.kt:437–490`

### 8. Battery optimization dialog appears on every launch
`checkBatteryOptimization()` runs on every `onCreate()` with no memory of the user
choosing "Later". Users who intentionally dismiss the dialog are repeatedly prompted.
The preference should be saved when "Later" is chosen.

**File:** `MainActivity.kt:255–281`

### 9. Excessive Toast usage
Nearly every action (Play, Pause, Stop, subtitle change, audio switch) fires a Toast.
Toasts stack and obscure the UI. Modern Android apps use Snackbars for in-context
feedback, which are dismissible and don't stack.

**Files:** `VideoDetailActivity.kt` (multiple locations)

### 10. Subtitle dialog section headers are tappable but non-functional
The `── Embedded tracks ──` and `── Sidecar files ──` headers in the subtitle
selection dialog are rendered as regular list items. Tapping them does nothing but
looks like a bug. They should be styled as non-interactive section headers.

**File:** `VideoDetailActivity.kt:656–726`

---

## Navigation & Discoverability

### 11. No pull-to-refresh for video list
If a user adds new video files to their device, there's no way to refresh the list
without leaving and re-entering the app. A `SwipeRefreshLayout` would solve this.

**File:** `activity_main.xml`, `MainActivity.kt`

### 12. No sort options for video list
Videos within a folder are always sorted alphabetically by title. Users might want to
sort by date, size, or duration. Adding a sort toggle in the toolbar or overflow menu
would improve browsability.

**File:** `MainViewModel.kt:154`

### 13. No visual indication of currently casting video
When returning from `VideoDetailActivity` to the main list, there's no indicator of
which video is actively being cast. A small cast icon or highlight on the currently
playing item would help.

**Files:** `MainActivity.kt`, `BrowseAdapter.kt`, `item_video.xml`

---

## Settings & Configuration

### 14. OpenSubtitles credentials dialog built programmatically
`showOpenSubtitlesCredentialsDialog()` in `VideoDetailActivity.kt` creates `EditText`
views programmatically. This is inconsistent with the rest of the app, which uses
inflated XML layouts for dialogs (e.g., `dialog_subtitle_offset.xml`).

**File:** `VideoDetailActivity.kt:858–914`

### 15. Min duration input has no unit label
The "Minimum duration" input in Settings shows just a number with no visible "minutes"
suffix. Adding a suffix hint or label would clarify the unit.

**File:** `activity_settings.xml:80–87`

---

## Performance & Polish

### 16. Thumbnail loading on main thread in BrowseAdapter
`loadThumbnail()` in `BrowseAdapter.VideoViewHolder.bind()` loads bitmaps
synchronously in `onBindViewHolder`, which runs on the main thread. For large lists,
this can cause frame drops during scrolling. Consider using an async image loader
(Coil, Glide) or loading on a background thread.

**File:** `BrowseAdapter.kt:89–110`

### 17. No animation for cast status bar
The cast status bar at the bottom of `VideoDetailActivity` toggles between `VISIBLE`
and `GONE` instantly. A slide-up/slide-down animation would be more polished.

**File:** `VideoDetailActivity.kt:1775–1787`

### 18. SeekBar container visibility change is abrupt
The seek bar section switches from `GONE` to `VISIBLE` without animation when video
info loads.

**File:** `VideoDetailActivity.kt:407–413`

---

## Summary of Implemented Fixes

The following improvements from the list above have been implemented in this PR:

- **#1** — Hardcoded strings replaced with string resources
- **#2** — Folder count now uses `<plurals>` resource for proper localization
- **#3** — Settings inputs upgraded to Material `TextInputLayout` with floating labels
- **#4** — Save button label changed to "Save"
- **#6** — Added placeholder icon for video thumbnails
