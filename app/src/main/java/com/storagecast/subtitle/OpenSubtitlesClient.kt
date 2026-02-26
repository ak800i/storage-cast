package com.storagecast.subtitle

import com.storagecast.log.AppLogger
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Client for the OpenSubtitles REST API (api.opensubtitles.com/api/v1).
 * Uses the same subtitle search approach as MPC-HC: hash-based lookup
 * followed by optional text-based search.
 */
class OpenSubtitlesClient(
    private val apiKey: String,
    private val username: String,
    private val password: String
) {

    companion object {
        private const val TAG = "OpenSubtitles"
        private const val BASE_URL = "https://api.opensubtitles.com/api/v1"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    data class SubtitleResult(
        val fileId: Int,
        val fileName: String,
        val language: String,
        val release: String,
        val downloadCount: Int
    )

    private var authToken: String? = null

    /**
     * Logs in to OpenSubtitles and stores the auth token.
     * @return true if login succeeded
     */
    fun login(): Boolean {
        return try {
            val body = JSONObject().apply {
                put("username", username)
                put("password", password)
            }

            val conn = createConnection("$BASE_URL/login", "POST")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                authToken = json.optString("token").takeIf { it.isNotEmpty() }
                AppLogger.info(TAG, "Login successful")
                true
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                AppLogger.error(TAG, "Login failed ($responseCode): $error")
                false
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Login error: ${e.message}")
            false
        }
    }

    /**
     * Searches for subtitles by file hash and size.
     * This is the primary search method used by MPC-HC.
     */
    fun searchByHash(movieHash: String, languages: String = "en"): List<SubtitleResult> {
        return try {
            val encodedHash = URLEncoder.encode(movieHash, "UTF-8")
            val encodedLang = URLEncoder.encode(languages, "UTF-8")
            val url = "$BASE_URL/subtitles?moviehash=$encodedHash&languages=$encodedLang"

            val conn = createConnection(url, "GET")
            val responseCode = conn.responseCode

            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                parseSearchResults(response)
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                AppLogger.error(TAG, "Hash search failed ($responseCode): $error")
                emptyList()
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Hash search error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Searches for subtitles by text query (typically the file name).
     * Used as a fallback when hash-based search returns no results.
     */
    fun searchByQuery(query: String, languages: String = "en"): List<SubtitleResult> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val encodedLang = URLEncoder.encode(languages, "UTF-8")
            val url = "$BASE_URL/subtitles?query=$encodedQuery&languages=$encodedLang"

            val conn = createConnection(url, "GET")
            val responseCode = conn.responseCode

            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                parseSearchResults(response)
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                AppLogger.error(TAG, "Query search failed ($responseCode): $error")
                emptyList()
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Query search error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Downloads a subtitle file by its file_id.
     * Requires a valid auth token (call [login] first).
     * @return the downloaded subtitle file, or null on failure
     */
    fun download(fileId: Int, outputDir: File): File? {
        if (authToken == null) {
            AppLogger.error(TAG, "Cannot download: not logged in")
            return null
        }

        return try {
            // Step 1: Request the download link
            val body = JSONObject().apply {
                put("file_id", fileId)
            }

            val conn = createConnection("$BASE_URL/download", "POST")
            conn.setRequestProperty("Authorization", "Bearer $authToken")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                AppLogger.error(TAG, "Download request failed ($responseCode): $error")
                return null
            }

            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val downloadLink = json.optString("link").takeIf { it.isNotEmpty() } ?: run {
                AppLogger.error(TAG, "No download link in response")
                return null
            }
            val fileName = json.optString("file_name").takeIf { it.isNotEmpty() } ?: "subtitle.srt"

            // Step 2: Download the actual subtitle file
            AppLogger.info(TAG, "Downloading subtitle: $fileName")
            val downloadConn = URL(downloadLink).openConnection() as HttpURLConnection
            downloadConn.connectTimeout = CONNECT_TIMEOUT_MS
            downloadConn.readTimeout = READ_TIMEOUT_MS

            outputDir.mkdirs()
            val outputFile = File(outputDir, "opensubtitles_$fileName")

            val inputStream = if (downloadConn.contentEncoding == "gzip") {
                GZIPInputStream(BufferedInputStream(downloadConn.inputStream))
            } else {
                BufferedInputStream(downloadConn.inputStream)
            }

            inputStream.use { input ->
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

    private fun parseSearchResults(json: String): List<SubtitleResult> {
        val results = mutableListOf<SubtitleResult>()
        try {
            val root = JSONObject(json)
            val data = root.optJSONArray("data") ?: return results

            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val attrs = item.optJSONObject("attributes") ?: continue
                val language = attrs.optString("language", "unknown")
                val release = attrs.optString("release", "")
                val downloadCount = attrs.optInt("download_count", 0)
                val files = attrs.optJSONArray("files")
                if (files != null && files.length() > 0) {
                    val file = files.getJSONObject(0)
                    val fileId = file.optInt("file_id", -1)
                    val fileName = file.optString("file_name", "")
                    if (fileId > 0) {
                        results.add(SubtitleResult(fileId, fileName, language, release, downloadCount))
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to parse search results: ${e.message}")
        }
        return results
    }

    private fun createConnection(urlString: String, method: String): HttpURLConnection {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Api-Key", apiKey)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "StorageCast")
        if (method == "POST") {
            conn.doOutput = true
        }
        return conn
    }
}
