package com.storagecast.subtitle

import android.media.MediaExtractor
import android.media.MediaFormat
import com.storagecast.media.MkvSubtitleExtractor
import com.storagecast.model.SubtitleTrack
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit

class SubtitleExtractor {

    private val mkvSubtitleExtractor = MkvSubtitleExtractor()

    companion object {
        private const val BUFFER_SIZE_BYTES = 1024 * 1024

        // Embedded subtitle samples carry no end time that MediaExtractor exposes (the
        // Matroska BlockDuration is dropped), so a cue runs until the next real cue. This
        // caps how long an isolated line lingers before a long gap.
        private const val MAX_CUE_DURATION_US = 7_000_000L

        private val SUBTITLE_MIME_TYPES = setOf(
            "text/vtt",
            "application/x-subrip",
            "text/x-ssa",
            "text/x-ass",
            "application/ttml+xml"
        )

        val SIDECAR_EXTENSIONS = listOf(".srt", ".vtt", ".ass", ".ssa")
    }

    /**
     * Finds subtitle files adjacent to the video file that share the same base name.
     * For example, for "movie.mkv", finds "movie.srt", "movie.en.srt", etc.
     */
    fun findSidecarSubtitles(videoPath: String): List<File> {
        val videoFile = File(videoPath)
        val parentDir = videoFile.parentFile ?: return emptyList()
        val baseName = videoFile.nameWithoutExtension

        if (!parentDir.canRead()) return emptyList()

        return try {
            parentDir.listFiles()
                ?.filter { file ->
                    file.isFile && SIDECAR_EXTENSIONS.any { ext ->
                        file.name.endsWith(ext, ignoreCase = true)
                    } && (file.nameWithoutExtension.equals(baseName, ignoreCase = true) ||
                          file.name.startsWith("$baseName.", ignoreCase = true))
                }
                ?.sortedBy { it.name }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getSubtitleTracks(videoPath: String): List<SubtitleTrack> {
        val tracks = mutableListOf<SubtitleTrack>()
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(videoPath)

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                if (mime in SUBTITLE_MIME_TYPES) {
                    val language = format.getStringOrDefault(MediaFormat.KEY_LANGUAGE, "und")
                    val codec = mime.substringAfterLast("/")
                    val title = "Track ${tracks.size + 1}"
                    tracks.add(SubtitleTrack(i, language, title, codec))
                }
            }
        } catch (e: Exception) {
            // Failed to probe subtitle tracks
        } finally {
            extractor.release()
        }

        // Fallback: some devices' MediaExtractor doesn't expose MKV subtitle tracks (e.g.
        // Xiaomi/HyperOS, which also hides AC-3/E-AC-3 audio). Parse the MKV at the EBML
        // level so embedded text subtitles are still available.
        if (tracks.isEmpty() && isMkv(videoPath)) {
            mkvSubtitleExtractor.listTracks(File(videoPath)).forEachIndexed { i, t ->
                val codec = when {
                    t.codecId.contains("UTF8", true) -> "subrip"
                    t.codecId.contains("ASS", true) || t.codecId.contains("SSA", true) -> "ass"
                    t.codecId.contains("WEBVTT", true) -> "vtt"
                    else -> t.codecId
                }
                val title = t.name.ifBlank { "Track ${i + 1}" }
                tracks.add(
                    SubtitleTrack(
                        index = t.trackNumber, language = t.language, title = title,
                        codec = codec, mkvTrackNumber = t.trackNumber
                    )
                )
            }
        }

        return tracks
    }

    private fun isMkv(videoPath: String): Boolean {
        val ext = File(videoPath).extension.lowercase()
        return ext == "mkv" || ext == "webm"
    }

    fun extractSubtitleAsVtt(
        videoPath: String,
        track: SubtitleTrack,
        outputDir: File,
        onProgress: ((Float) -> Unit)? = null
    ): File? {
        // Extracting an embedded subtitle scans the whole file (subtitles aren't indexed in
        // Matroska), which is slow for large files. Cache the result keyed by the source's
        // identity (path + size + mtime + track) so re-selecting the same track is instant.
        val source = File(videoPath)
        val cacheKey = "${videoPath.hashCode().toUInt()}_${source.length()}_${source.lastModified()}_${track.index}"
        val outputFile = File(outputDir, "sub_$cacheKey.vtt")
        if (outputFile.exists() && outputFile.length() > 0) {
            onProgress?.invoke(1f)
            return outputFile
        }
        return if (track.mkvTrackNumber != null) {
            extractViaEbml(videoPath, track.mkvTrackNumber, outputFile, onProgress)
        } else {
            extractViaMediaExtractor(videoPath, track.index, outputFile)
        }
    }

    /** Extracts a subtitle track parsed from the MKV's EBML structure (BlockDuration gives ends). */
    private fun extractViaEbml(
        videoPath: String,
        mkvTrackNumber: Int,
        outputFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): File? {
        return try {
            val cues = mkvSubtitleExtractor.extractCues(File(videoPath), mkvTrackNumber, onProgress)
            if (cues.isEmpty()) return null
            writeVtt(cues.map { SubtitleCue(it.startMs * 1000, it.endMs * 1000, it.text) }, outputFile)
            outputFile
        } catch (e: Exception) {
            null
        }
    }

    private fun extractViaMediaExtractor(videoPath: String, trackIndex: Int, outputFile: File): File? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(videoPath)
            val format = extractor.getTrackFormat(trackIndex)
            format.getString(MediaFormat.KEY_MIME) ?: return null

            extractor.selectTrack(trackIndex)

            // Keep only samples that actually carry text. Some MKV subtitle tracks emit many
            // spurious empty samples between real cues; ending a cue at the next raw sample
            // then makes each line flash for a few milliseconds.
            val starts = mutableListOf<Long>()
            val texts = mutableListOf<String>()
            val buffer = ByteBuffer.allocate(BUFFER_SIZE_BYTES)

            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                val timeUs = extractor.sampleTime
                val bytes = ByteArray(size)
                buffer.rewind()
                buffer.get(bytes, 0, size)
                // SubRip samples from MKV are NUL-terminated and use CRLF line endings, and
                // may carry stray control bytes. trim() leaves a trailing NUL (U+0000), which
                // the Cast receiver renders as a "�" glyph, and the carriage returns are cruft
                // in WebVTT. Keep newlines/tabs and printable text; drop everything else.
                val text = String(bytes, Charsets.UTF_8)
                    .filter { it == '\n' || it == '\t' || it.code >= 0x20 }
                    .trim()

                if (text.isNotEmpty()) {
                    starts.add(timeUs)
                    texts.add(text)
                }

                extractor.advance()
            }

            // A cue runs until the next real cue (so consecutive lines don't overlap), capped
            // so an isolated line before a long gap doesn't linger. The true end (Matroska
            // BlockDuration) isn't exposed by MediaExtractor.
            val cues = mutableListOf<SubtitleCue>()
            for (i in starts.indices) {
                val startUs = starts[i]
                val nextStartUs = if (i + 1 < starts.size) starts[i + 1] else Long.MAX_VALUE
                val endUs = minOf(nextStartUs, startUs + MAX_CUE_DURATION_US)
                cues.add(SubtitleCue(startUs, endUs, texts[i]))
            }

            val vttCues = cues.map { SubtitleCue(it.startUs, it.endUs, it.text) }
            writeVtt(vttCues, outputFile)
            return outputFile
        } catch (e: Exception) {
            // Failed to extract subtitles
            return null
        } finally {
            extractor.release()
        }
    }

    private fun formatVttTime(timeUs: Long): String {
        val totalMs = timeUs / 1000
        val hours = TimeUnit.MILLISECONDS.toHours(totalMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(totalMs) % 60
        val millis = totalMs % 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    /** Writes [cues] as a styled WebVTT file (shared by the MediaExtractor and EBML paths). */
    private fun writeVtt(cues: List<SubtitleCue>, outputFile: File) {
        val vttContent = buildString {
            appendLine("WEBVTT")
            appendLine()
            cues.forEachIndexed { index, cue ->
                appendLine("${index + 1}")
                appendLine("${formatVttTime(cue.startUs)} --> ${formatVttTime(cue.endUs)}")
                appendLine(cue.text)
                appendLine()
            }
        }
        outputFile.writeText(SubtitleConverter.ensureVttStyle(vttContent))
    }

    private fun MediaFormat.getStringOrDefault(key: String, default: String): String {
        return try {
            getString(key) ?: default
        } catch (e: Exception) {
            default
        }
    }

    private data class SubtitleCue(
        val startUs: Long,
        val endUs: Long,
        val text: String
    )
}
