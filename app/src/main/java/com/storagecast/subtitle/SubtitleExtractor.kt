package com.storagecast.subtitle

import android.media.MediaExtractor
import android.media.MediaFormat
import com.storagecast.model.SubtitleTrack
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit

class SubtitleExtractor {

    companion object {
        private const val BUFFER_SIZE_BYTES = 1024 * 1024
        private const val DEFAULT_CUE_DURATION_US = 3_000_000L

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

        return tracks
    }

    fun extractSubtitleAsVtt(videoPath: String, trackIndex: Int, outputDir: File): File? {
        val outputFile = File(outputDir, "subtitle_${trackIndex}.vtt")
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(videoPath)
            val format = extractor.getTrackFormat(trackIndex)
            format.getString(MediaFormat.KEY_MIME) ?: return null

            extractor.selectTrack(trackIndex)

            val timestamps = mutableListOf<Long>()
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
                val text = String(bytes, Charsets.UTF_8)

                timestamps.add(timeUs)
                texts.add(text.trim())

                extractor.advance()
            }

            val cues = mutableListOf<SubtitleCue>()
            for (i in timestamps.indices) {
                val startUs = timestamps[i]
                val endUs = if (i + 1 < timestamps.size) {
                    timestamps[i + 1]
                } else {
                    startUs + DEFAULT_CUE_DURATION_US
                }
                cues.add(SubtitleCue(startUs, endUs, texts[i]))
            }

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
