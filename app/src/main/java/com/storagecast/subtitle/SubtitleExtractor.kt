package com.storagecast.subtitle

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.storagecast.model.SubtitleTrack
import org.json.JSONObject
import java.io.File

class SubtitleExtractor {

    fun getSubtitleTracks(videoPath: String): List<SubtitleTrack> {
        val tracks = mutableListOf<SubtitleTrack>()

        val escapedPath = videoPath.replace("\"", "\\\"")
        val session = FFprobeKit.execute(
            "-v quiet -print_format json -show_streams -select_streams s \"$escapedPath\""
        )

        try {
            val output = session.output ?: return tracks
            val json = JSONObject(output)
            val streams = json.optJSONArray("streams") ?: return tracks

            for (i in 0 until streams.length()) {
                val stream = streams.getJSONObject(i)
                val index = stream.optInt("index", i)
                val tags = stream.optJSONObject("tags")
                val language = tags?.optString("language", "und") ?: "und"
                val title = tags?.optString("title", "Track ${i + 1}") ?: "Track ${i + 1}"
                val codec = stream.optString("codec_name", "unknown")

                tracks.add(SubtitleTrack(index, language, title, codec))
            }
        } catch (e: Exception) {
            // Failed to parse subtitle info
        }

        return tracks
    }

    fun extractSubtitleAsVtt(videoPath: String, trackIndex: Int, outputDir: File): File? {
        val outputFile = File(outputDir, "subtitle_${trackIndex}.vtt")

        if (outputFile.exists()) {
            outputFile.delete()
        }

        val subtitleStreamIndex = getSubtitleStreamRelativeIndex(videoPath, trackIndex)

        val escapedInput = videoPath.replace("\"", "\\\"")
        val escapedOutput = outputFile.absolutePath.replace("\"", "\\\"")
        val session = FFmpegKit.execute(
            "-i \"$escapedInput\" -map 0:s:$subtitleStreamIndex -c:s webvtt -y \"$escapedOutput\""
        )

        return if (session.returnCode?.isValueSuccess == true && outputFile.exists()) {
            outputFile
        } else {
            null
        }
    }

    private fun getSubtitleStreamRelativeIndex(videoPath: String, absoluteIndex: Int): Int {
        val tracks = getSubtitleTracks(videoPath)
        return tracks.indexOfFirst { it.index == absoluteIndex }.takeIf { it >= 0 } ?: 0
    }
}
