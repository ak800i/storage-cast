package com.storagecast.subtitle

import com.storagecast.log.AppLogger
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for the Gestdown subtitle API (api.gestdown.info).
 * Gestdown is a free proxy for Addic7ed subtitles that requires
 * no authentication. Subtitles are looked up by show name,
 * season and episode number.
 */
class GestdownClient {

    companion object {
        private const val TAG = "Gestdown"
        private const val BASE_URL = "https://api.gestdown.info"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    data class ShowResult(
        val id: String,
        val name: String,
        val nbSeasons: Int
    )

    data class SubtitleResult(
        val subtitleId: String,
        val version: String,
        val language: String,
        val completed: Boolean,
        val hearingImpaired: Boolean,
        val downloadCount: Int
    )

    /**
     * Searches for TV shows by name.
     */
    fun searchShows(query: String): List<ShowResult> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val conn = createConnection("$BASE_URL/shows/search/$encoded")
            val responseCode = conn.responseCode

            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                parseShowResults(response)
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                AppLogger.error(TAG, "Show search failed ($responseCode): $error")
                emptyList()
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Show search error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Gets available subtitles for a specific episode.
     */
    fun getSubtitles(
        showId: String,
        season: Int,
        episode: Int,
        language: String = "English"
    ): List<SubtitleResult> {
        return try {
            val encodedLang = URLEncoder.encode(language, "UTF-8")
            val url = "$BASE_URL/subtitles/get/$showId/$season/$episode/$encodedLang"
            val conn = createConnection(url)
            val responseCode = conn.responseCode

            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                parseSubtitleResults(response)
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                AppLogger.error(TAG, "Subtitle search failed ($responseCode): $error")
                emptyList()
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Subtitle search error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Downloads a subtitle file by its ID.
     * @return the downloaded subtitle file, or null on failure
     */
    fun download(subtitleId: String, outputDir: File): File? {
        return try {
            val url = "$BASE_URL/subtitles/download/$subtitleId"
            val conn = createConnection(url)
            val responseCode = conn.responseCode

            if (responseCode != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                AppLogger.error(TAG, "Download failed ($responseCode): $error")
                return null
            }

            outputDir.mkdirs()
            val outputFile = File(outputDir, "gestdown_$subtitleId.srt")

            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }

            AppLogger.info(TAG, "Downloaded subtitle: ${outputFile.name} (${outputFile.length()} bytes)")
            outputFile
        } catch (e: Exception) {
            AppLogger.error(TAG, "Download error: ${e.message}")
            null
        }
    }

    private fun parseShowResults(json: String): List<ShowResult> {
        val results = mutableListOf<ShowResult>()
        try {
            val root = JSONObject(json)
            val shows = root.optJSONArray("shows") ?: return results

            for (i in 0 until shows.length()) {
                val show = shows.getJSONObject(i)
                val id = show.optString("id", "")
                val name = show.optString("name", "")
                val nbSeasons = show.optInt("nbSeasons", 0)
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    results.add(ShowResult(id, name, nbSeasons))
                }
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to parse show results: ${e.message}")
        }
        return results
    }

    private fun parseSubtitleResults(json: String): List<SubtitleResult> {
        val results = mutableListOf<SubtitleResult>()
        try {
            val root = JSONObject(json)
            val subs = root.optJSONArray("matchingSubtitles") ?: return results

            for (i in 0 until subs.length()) {
                val sub = subs.getJSONObject(i)
                val subtitleId = sub.optString("subtitleId", "")
                val version = sub.optString("version", "")
                val language = sub.optString("language", "")
                val completed = sub.optBoolean("completed", false)
                val hearingImpaired = sub.optBoolean("hearingImpaired", false)
                val downloadCount = sub.optInt("downloadCount", 0)
                if (subtitleId.isNotEmpty()) {
                    results.add(
                        SubtitleResult(
                            subtitleId, version, language,
                            completed, hearingImpaired, downloadCount
                        )
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to parse subtitle results: ${e.message}")
        }
        return results
    }

    private fun createConnection(urlString: String): HttpURLConnection {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "StorageCast")
        return conn
    }
}
