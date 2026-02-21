package com.storagecast.server

import android.app.Service
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.storagecast.log.AppLogger
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

class MediaServerService : Service() {

    private val binder = LocalBinder()
    private var server: MediaServer? = null

    inner class LocalBinder : Binder() {
        fun getService(): MediaServerService = this@MediaServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun startServer(port: Int = 8080): Int {
        if (server?.isAlive == true) {
            AppLogger.log("MediaServer", "Server already running on port ${server!!.listeningPort}")
            return server!!.listeningPort
        }
        return try {
            server = MediaServer(port, contentResolver)
            server?.start()
            val actualPort = server!!.listeningPort
            AppLogger.log("MediaServer", "Server started on port $actualPort")
            actualPort
        } catch (e: Exception) {
            AppLogger.log("MediaServer", "Failed to start server on port $port: ${e.message}")
            throw e
        }
    }

    fun stopServer() {
        server?.stop()
        server = null
        AppLogger.log("MediaServer", "Server stopped")
    }

    fun isServerRunning(): Boolean = server?.isAlive == true

    fun registerFile(path: String, mimeType: String, uri: Uri? = null): String {
        val file = File(path)
        val id = file.name.hashCode().toUInt().toString()
        val serverActive = server != null
        server?.registerFile(id, file, mimeType, uri)
        AppLogger.log("MediaServer", "Register file: path=$path, id=$id, uri=$uri, exists=${file.exists()}, size=${file.length()}, serverActive=$serverActive")
        return "/media/$id"
    }

    fun registerSubtitle(subtitleFile: File): String {
        val id = subtitleFile.name.hashCode().toUInt().toString()
        server?.registerFile(id, subtitleFile, "text/vtt", null)
        AppLogger.log("MediaServer", "Register subtitle: ${subtitleFile.name}, id=$id, exists=${subtitleFile.exists()}")
        return "/media/$id"
    }

    fun getServerPort(): Int = server?.listeningPort ?: 8080

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private data class FileEntry(
        val file: File,
        val mimeType: String,
        val uri: Uri?
    )

    private class MediaServer(port: Int, private val resolver: ContentResolver) : NanoHTTPD(port) {
        private val fileMap = mutableMapOf<String, FileEntry>()

        fun registerFile(id: String, file: File, mimeType: String, uri: Uri?) {
            fileMap[id] = FileEntry(file, mimeType, uri)
        }

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            Log.d("MediaServer", "Request: $uri")
            AppLogger.log("MediaServer", "HTTP ${session.method} $uri")

            // Handle CORS preflight requests
            if (session.method == Method.OPTIONS) {
                val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                response.addHeader("Access-Control-Allow-Origin", "*")
                response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
                response.addHeader("Access-Control-Allow-Headers", "Content-Type, Range")
                response.addHeader("Access-Control-Max-Age", "86400")
                return response
            }

            if (uri.startsWith("/media/")) {
                val id = uri.removePrefix("/media/")
                val entry = fileMap[id]
                if (entry == null) {
                    AppLogger.log("MediaServer", "404 Not found: id=$id, registered ids=${fileMap.keys}")
                    return newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"
                    )
                }

                if (!entry.file.exists()) {
                    AppLogger.log("MediaServer", "404 File not found on disk: ${entry.file.absolutePath}")
                    return newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found"
                    )
                }

                val rangeHeader = session.headers["range"]
                return if (rangeHeader != null) {
                    AppLogger.log("MediaServer", "Serving partial: ${entry.file.name} (${entry.file.length()} bytes), range=$rangeHeader")
                    servePartialContent(entry, rangeHeader)
                } else {
                    AppLogger.log("MediaServer", "Serving full: ${entry.file.name} (${entry.file.length()} bytes)")
                    serveFullContent(entry)
                }
            }

            AppLogger.log("MediaServer", "404 Unknown path: $uri")
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"
            )
        }

        private fun openFileStream(entry: FileEntry): FileInputStream {
            // Try ContentResolver first (handles scoped storage on Android 10+)
            if (entry.uri != null) {
                try {
                    val pfd = resolver.openFileDescriptor(entry.uri, "r")
                    if (pfd != null) {
                        AppLogger.log("MediaServer", "Opened file via ContentResolver: ${entry.file.name}")
                        return ParcelFileDescriptor.AutoCloseInputStream(pfd)
                    }
                } catch (e: Exception) {
                    AppLogger.log("MediaServer", "ContentResolver failed for ${entry.file.name}: ${e.message}, falling back to FileInputStream")
                }
            }
            // Fall back to direct file access
            return FileInputStream(entry.file)
        }

        private fun serveFullContent(entry: FileEntry): Response {
            return try {
                val fis = openFileStream(entry)
                val response = newFixedLengthResponse(
                    Response.Status.OK, entry.mimeType, fis, entry.file.length()
                )
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                AppLogger.log("MediaServer", "Error serving file ${entry.file.name}: ${e.message}")
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}"
                )
            }
        }

        private fun servePartialContent(entry: FileEntry, rangeHeader: String): Response {
            return try {
                val fileLength = entry.file.length()
                val range = rangeHeader.replace("bytes=", "")
                val parts = range.split("-")
                val start = parts[0].toLongOrNull() ?: 0L
                val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                    parts[1].toLongOrNull() ?: (fileLength - 1)
                } else {
                    fileLength - 1
                }

                val contentLength = end - start + 1
                val fis = openFileStream(entry)
                if (start > 0) {
                    var remaining = start
                    while (remaining > 0) {
                        val skipped = fis.skip(remaining)
                        if (skipped <= 0) break
                        remaining -= skipped
                    }
                }

                val response = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, entry.mimeType, fis, contentLength
                )
                response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Content-Length", contentLength.toString())
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                AppLogger.log("MediaServer", "Error serving partial content ${entry.file.name}: ${e.message}")
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}"
                )
            }
        }
    }
}
