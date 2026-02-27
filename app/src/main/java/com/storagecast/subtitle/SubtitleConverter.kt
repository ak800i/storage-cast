package com.storagecast.subtitle

import com.storagecast.log.AppLogger
import java.io.File
import java.io.InputStream

/**
 * Converts subtitle files from various formats (SRT, SSA/ASS) to WebVTT
 * for Chromecast compatibility.
 */
class SubtitleConverter {

    companion object {
        private const val TAG = "SubtitleConverter"

        private val SRT_TIMESTAMP_REGEX = Regex(
            "(\\d{2}:\\d{2}:\\d{2},\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2},\\d{3})"
        )

        private val VTT_TIMESTAMP_REGEX = Regex(
            "(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})"
        )
    }

    /**
     * Converts a subtitle file to VTT format.
     * Returns the converted VTT file, or null on failure.
     * If the input is already VTT, it is copied as-is.
     */
    fun convertToVtt(inputStream: InputStream, fileName: String, outputDir: File): File? {
        return try {
            outputDir.mkdirs()
            val content = inputStream.bufferedReader(Charsets.UTF_8).readText()
            val outputFile = File(outputDir, "local_subtitle.vtt")

            val vttContent = when {
                fileName.endsWith(".vtt", ignoreCase = true) -> content
                fileName.endsWith(".srt", ignoreCase = true) -> convertSrtToVtt(content)
                fileName.endsWith(".ssa", ignoreCase = true) ||
                fileName.endsWith(".ass", ignoreCase = true) -> convertAssToVtt(content)
                else -> {
                    AppLogger.warn(TAG, "Unsupported subtitle format: $fileName")
                    null
                }
            }

            if (vttContent != null) {
                outputFile.writeText(vttContent)
                AppLogger.info(TAG, "Converted $fileName to VTT (${outputFile.length()} bytes)")
                outputFile
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to convert subtitle $fileName: ${e.message}")
            null
        }
    }

    private fun convertSrtToVtt(srtContent: String): String {
        val vtt = buildString {
            appendLine("WEBVTT")
            appendLine()

            val lines = srtContent.replace("\r\n", "\n").replace("\r", "\n")
            val replaced = SRT_TIMESTAMP_REGEX.replace(lines) { match ->
                val start = match.groupValues[1].replace(',', '.')
                val end = match.groupValues[2].replace(',', '.')
                "$start --> $end"
            }
            append(replaced.trimStart())
        }
        return vtt
    }

    private fun convertAssToVtt(assContent: String): String {
        val lines = assContent.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        var inEvents = false
        var formatLine: List<String>? = null

        val cues = mutableListOf<Triple<String, String, String>>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.equals("[Events]", ignoreCase = true)) {
                inEvents = true
                continue
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inEvents = false
                continue
            }
            if (!inEvents) continue

            if (trimmed.startsWith("Format:", ignoreCase = true)) {
                formatLine = trimmed.substringAfter(":").split(",").map { it.trim() }
                continue
            }

            if (trimmed.startsWith("Dialogue:", ignoreCase = true) && formatLine != null) {
                val values = trimmed.substringAfter(":").split(",", limit = formatLine.size)
                if (values.size < formatLine.size) continue

                val fieldMap = formatLine.zip(values.map { it.trim() }).toMap()
                val start = fieldMap["Start"] ?: continue
                val end = fieldMap["End"] ?: continue
                val text = fieldMap["Text"] ?: continue

                val cleanText = text
                    .replace(Regex("\\{\\\\[^}]*\\}"), "")
                    .replace("\\N", "\n")
                    .replace("\\n", "\n")
                    .trim()

                if (cleanText.isNotEmpty()) {
                    cues.add(Triple(convertAssTime(start), convertAssTime(end), cleanText))
                }
            }
        }

        return buildString {
            appendLine("WEBVTT")
            appendLine()
            cues.forEachIndexed { index, (start, end, text) ->
                appendLine("${index + 1}")
                appendLine("$start --> $end")
                appendLine(text)
                appendLine()
            }
        }
    }

    /**
     * Convert ASS time format (H:MM:SS.cc) to VTT format (HH:MM:SS.mmm)
     */
    private fun convertAssTime(time: String): String {
        val parts = time.trim().split(":", ".")
        if (parts.size < 4) return time
        val h = parts[0].toIntOrNull() ?: 0
        val m = parts[1].toIntOrNull() ?: 0
        val s = parts[2].toIntOrNull() ?: 0
        val cs = parts[3].toIntOrNull() ?: 0
        return String.format("%02d:%02d:%02d.%03d", h, m, s, minOf(cs * 10, 999))
    }

    /**
     * Reads a VTT file, shifts all cue timestamps by [offsetMs] milliseconds,
     * and writes the result to [outputFile]. Negative values shift earlier,
     * positive values shift later. Timestamps are clamped to 00:00:00.000.
     * Returns the output file, or null on failure.
     */
    fun applySubtitleOffset(vttFile: File, offsetMs: Long, outputFile: File): File? {
        return try {
            val content = vttFile.readText(Charsets.UTF_8)
            val shifted = VTT_TIMESTAMP_REGEX.replace(content) { match ->
                val start = shiftVttTimestamp(match.groupValues[1], offsetMs)
                val end = shiftVttTimestamp(match.groupValues[2], offsetMs)
                "$start --> $end"
            }
            outputFile.writeText(shifted)
            AppLogger.info(TAG, "Applied subtitle offset ${offsetMs}ms -> ${outputFile.name}")
            outputFile
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to apply subtitle offset: ${e.message}")
            null
        }
    }

    private fun shiftVttTimestamp(timestamp: String, offsetMs: Long): String {
        val parts = timestamp.split(":", ".")
        if (parts.size < 4) return timestamp
        val h = parts[0].toLongOrNull() ?: 0
        val m = parts[1].toLongOrNull() ?: 0
        val s = parts[2].toLongOrNull() ?: 0
        val ms = parts[3].toLongOrNull() ?: 0
        val totalMs = (h * 3_600_000 + m * 60_000 + s * 1_000 + ms + offsetMs).coerceAtLeast(0)
        val newH = totalMs / 3_600_000
        val newM = (totalMs % 3_600_000) / 60_000
        val newS = (totalMs % 60_000) / 1_000
        val newMs = totalMs % 1_000
        return String.format("%02d:%02d:%02d.%03d", newH, newM, newS, newMs)
    }
}
