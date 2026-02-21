package com.storagecast.server

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
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
            server = MediaServer(port)
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

    fun registerFile(path: String, mimeType: String): String {
        val file = File(path)
        val id = file.name.hashCode().toUInt().toString()
        val serverActive = server != null
        server?.registerFile(id, file, mimeType)
        AppLogger.log("MediaServer", "Register file: path=$path, id=$id, exists=${file.exists()}, size=${file.length()}, serverActive=$serverActive")
        return "/media/$id"
    }

    fun registerSubtitle(subtitleFile: File): String {
        val id = subtitleFile.name.hashCode().toUInt().toString()
        server?.registerFile(id, subtitleFile, "text/vtt")
        AppLogger.log("MediaServer", "Register subtitle: ${subtitleFile.name}, id=$id, exists=${subtitleFile.exists()}")
        return "/media/$id"
    }

    fun getServerPort(): Int = server?.listeningPort ?: 8080

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private class MediaServer(port: Int) : NanoHTTPD(port) {
        private val fileMap = mutableMapOf<String, Pair<File, String>>()

        fun registerFile(id: String, file: File, mimeType: String) {
            fileMap[id] = Pair(file, mimeType)
        }

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            Log.d("MediaServer", "Request: $uri")
            AppLogger.log("MediaServer", "HTTP ${session.method} $uri")

            if (uri.startsWith("/media/")) {
                val id = uri.removePrefix("/media/")
                val entry = fileMap[id]
                if (entry == null) {
                    AppLogger.log("MediaServer", "404 Not found: id=$id, registered ids=${fileMap.keys}")
                    return newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"
                    )
                }

                val (file, mimeType) = entry

                if (!file.exists()) {
                    AppLogger.log("MediaServer", "404 File not found on disk: ${file.absolutePath}")
                    return newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found"
                    )
                }

                val rangeHeader = session.headers["range"]
                return if (rangeHeader != null) {
                    AppLogger.log("MediaServer", "Serving partial: ${file.name} (${file.length()} bytes), range=$rangeHeader")
                    servePartialContent(file, mimeType, rangeHeader)
                } else {
                    AppLogger.log("MediaServer", "Serving full: ${file.name} (${file.length()} bytes)")
                    serveFullContent(file, mimeType)
                }
            }

            AppLogger.log("MediaServer", "404 Unknown path: $uri")
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"
            )
        }

        private fun serveFullContent(file: File, mimeType: String): Response {
            return try {
                val fis = FileInputStream(file)
                val response = newFixedLengthResponse(
                    Response.Status.OK, mimeType, fis, file.length()
                )
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                AppLogger.log("MediaServer", "Error serving file ${file.name}: ${e.message}")
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}"
                )
            }
        }

        private fun servePartialContent(
            file: File, mimeType: String, rangeHeader: String
        ): Response {
            return try {
                val fileLength = file.length()
                val range = rangeHeader.replace("bytes=", "")
                val parts = range.split("-")
                val start = parts[0].toLongOrNull() ?: 0L
                val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                    parts[1].toLongOrNull() ?: (fileLength - 1)
                } else {
                    fileLength - 1
                }

                val contentLength = end - start + 1
                val fis = FileInputStream(file)
                fis.skip(start)

                val response = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, mimeType, fis, contentLength
                )
                response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Content-Length", contentLength.toString())
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                AppLogger.log("MediaServer", "Error serving partial content ${file.name}: ${e.message}")
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}"
                )
            }
        }
    }
}
