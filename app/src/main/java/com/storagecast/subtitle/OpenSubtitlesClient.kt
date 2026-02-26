package com.storagecast.subtitle

import com.storagecast.log.AppLogger
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Client for the OpenSubtitles.org REST API (v1).
 * Supports login, subtitle search by movie hash / query, and download.
 */
class OpenSubtitlesClient {

    companion object {
        private const val TAG = "OpenSubtitles"
        private const val BASE_URL = "https://api.opensubtitles.com/api/v1"
        private const val HASH_CHUNK_SIZE = 65536L // 64 KB
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 30_000
    }

    private var token: String? = null

    /**
     * Authenticate with OpenSubtitles and obtain a session token.
     */
    fun login(apiKey: String, username: String, password: String): Boolean {
        return try {
            val body = JSONObject().apply {
                put("username", username)
                put("password", password)
            }
            val conn = openConnection("$BASE_URL/login", "POST", apiKey)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                token = json.optString("token", "").ifEmpty { null }
                AppLogger.info(TAG, "Login successful")
                true
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                AppLogger.error(TAG, "Login failed: $error")
                false
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Login error: ${e.message}")
            false
        }
    }

    /**
     * Search for subtitles by movie hash and/or query string.
     */
    fun search(
        apiKey: String,
        query: String? = null,
        movieHash: String? = null,
        languages: String = "en"
    ): List<OpenSubtitlesResult> {
        return try {
            val params = mutableListOf<String>()
            params.add("languages=${enc(languages)}")
            if (!movieHash.isNullOrBlank()) params.add("moviehash=${enc(movieHash)}")
            if (!query.isNullOrBlank()) params.add("query=${enc(query)}")

            val url = "$BASE_URL/subtitles?${params.joinToString("&")}"
            val conn = openConnection(url, "GET", apiKey)
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }

            val code = conn.responseCode
            if (code != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                AppLogger.error(TAG, "Search failed: $error")
                return emptyList()
            }

            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val data = json.optJSONArray("data") ?: return emptyList()

            val results = mutableListOf<OpenSubtitlesResult>()
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val attrs = item.optJSONObject("attributes") ?: continue
                val files = attrs.optJSONArray("files")
                if (files == null || files.length() == 0) continue

                val file = files.getJSONObject(0)
                val fileId = file.optInt("file_id", -1)
                if (fileId < 0) continue

                val fileName = file.optString("file_name", "unknown")
                val language = attrs.optString("language", "?")
                val release = attrs.optString("release", fileName)
                val downloadCount = attrs.optInt("download_count", 0)

                results.add(
                    OpenSubtitlesResult(
                        fileId = fileId,
                        fileName = fileName,
                        language = language,
                        release = release,
                        downloadCount = downloadCount
                    )
                )
            }

            AppLogger.info(TAG, "Search returned ${results.size} results")
            results
        } catch (e: Exception) {
            AppLogger.error(TAG, "Search error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Download a subtitle by file ID and save it to [outputDir].
     * Returns the downloaded file or null on failure.
     */
    fun download(apiKey: String, fileId: Int, outputDir: File): File? {
        return try {
            val body = JSONObject().apply {
                put("file_id", fileId)
            }
            val conn = openConnection("$BASE_URL/download", "POST", apiKey)
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                AppLogger.error(TAG, "Download request failed: $error")
                return null
            }

            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val link = json.optString("link", "").ifEmpty { null }
            val downloadFileName = json.optString("file_name", "subtitle.srt")
            if (link.isNullOrBlank()) {
                AppLogger.error(TAG, "No download link in response")
                return null
            }

            // Download the actual subtitle file
            val dlConn = URL(link).openConnection() as HttpURLConnection
            dlConn.connectTimeout = CONNECT_TIMEOUT
            dlConn.readTimeout = READ_TIMEOUT
            dlConn.instanceFollowRedirects = true

            if (dlConn.responseCode != 200) {
                AppLogger.error(TAG, "Subtitle file download failed: HTTP ${dlConn.responseCode}")
                return null
            }

            outputDir.mkdirs()
            val outFile = File(outputDir, downloadFileName)
            dlConn.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            AppLogger.info(TAG, "Downloaded subtitle: ${outFile.name} (${outFile.length()} bytes)")
            outFile
        } catch (e: Exception) {
            AppLogger.error(TAG, "Download error: ${e.message}")
            null
        }
    }

    /**
     * Compute the OpenSubtitles hash for a video file.
     * Algorithm: sum of first and last 64KB as little-endian longs + file size.
     */
    fun computeHash(filePath: String): String? {
        return try {
            val file = File(filePath)
            val fileSize = file.length()
            if (fileSize < HASH_CHUNK_SIZE) return null

            var hash = fileSize
            val raf = RandomAccessFile(file, "r")
            raf.use {
                val buffer = ByteArray(HASH_CHUNK_SIZE.toInt())

                // First 64KB
                raf.readFully(buffer)
                hash = sumBytes(buffer, hash)

                // Last 64KB
                raf.seek(fileSize - HASH_CHUNK_SIZE)
                raf.readFully(buffer)
                hash = sumBytes(buffer, hash)
            }

            String.format("%016x", hash)
        } catch (e: Exception) {
            AppLogger.error(TAG, "Hash computation error: ${e.message}")
            null
        }
    }

    private fun sumBytes(buffer: ByteArray, initialValue: Long): Long {
        var sum = initialValue
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        while (bb.remaining() >= 8) {
            sum += bb.long
        }
        return sum
    }

    private fun openConnection(url: String, method: String, apiKey: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Api-Key", apiKey)
        conn.setRequestProperty("User-Agent", "StorageCast v1.0")
        return conn
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
