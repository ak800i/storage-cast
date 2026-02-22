package com.storagecast.server

import android.app.Service
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.storagecast.log.AppLogger
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

class MediaServerService : Service() {

    companion object {
        @Volatile
        private var logHandlerInstalled = false
    }

    private val binder = LocalBinder()
    private var server: MediaServer? = null

    inner class LocalBinder : Binder() {
        fun getService(): MediaServerService = this@MediaServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun startServer(port: Int = 8080): Int {
        installNanoHttpdLogHandler()
        if (server?.isAlive == true) {
            AppLogger.info("MediaServer", "Server already running on port ${server!!.listeningPort}")
            return server!!.listeningPort
        }
        return try {
            server = MediaServer(port, contentResolver)
            server?.start()
            val actualPort = server!!.listeningPort
            AppLogger.info("MediaServer", "Server started on port $actualPort")
            actualPort
        } catch (e: Exception) {
            AppLogger.error("MediaServer", "Failed to start server on port $port: ${e.message}")
            throw e
        }
    }

    private fun installNanoHttpdLogHandler() {
        if (logHandlerInstalled) return
        logHandlerInstalled = true
        try {
            val logger = java.util.logging.Logger.getLogger(NanoHTTPD::class.java.name)
            logger.addHandler(object : Handler() {
                override fun publish(record: LogRecord) {
                    val msg = record.message ?: return
                    val thrown = record.thrown
                    if (thrown != null) {
                        if (thrown is java.net.SocketException) {
                            AppLogger.info("NanoHTTPD", "$msg: ${thrown.javaClass.simpleName}: ${thrown.message}")
                        } else {
                            AppLogger.warn("NanoHTTPD", "$msg: ${thrown.javaClass.simpleName}: ${thrown.message}")
                        }
                    } else {
                        AppLogger.info("NanoHTTPD", msg)
                    }
                }
                override fun flush() {}
                override fun close() {}
            })
        } catch (e: Exception) {
            AppLogger.error("MediaServer", "Failed to install NanoHTTPD log handler: ${e.message}")
        }
    }

    fun stopServer() {
        server?.stop()
        server = null
        AppLogger.info("MediaServer", "Server stopped")
    }

    fun isServerRunning(): Boolean = server?.isAlive == true

    fun registerFile(path: String, mimeType: String, uri: Uri? = null): String {
        val file = File(path)
        val id = file.name.hashCode().toUInt().toString()
        val serverActive = server != null
        server?.registerFile(id, file, mimeType, uri)
        AppLogger.info("MediaServer", "Register file: path=$path, id=$id, uri=$uri, exists=${file.exists()}, size=${file.length()}, serverActive=$serverActive")
        return "/media/$id"
    }

    fun registerSubtitle(subtitleFile: File): String {
        val id = subtitleFile.name.hashCode().toUInt().toString()
        server?.registerFile(id, subtitleFile, "text/vtt", null)
        AppLogger.info("MediaServer", "Register subtitle: ${subtitleFile.name}, id=$id, exists=${subtitleFile.exists()}")
        return "/media/$id"
    }

    /**
     * Registers a streaming source that produces data on-demand via a factory lambda.
     * Each HTTP request invokes the factory to create a fresh InputStream.
     * The response uses chunked transfer encoding (no Content-Length).
     */
    fun registerStreamingSource(label: String, mimeType: String, factory: () -> InputStream): String {
        val id = "stream_${label.hashCode().toUInt()}_${System.currentTimeMillis()}"
        server?.registerStreamFactory(id, mimeType, factory)
        AppLogger.info("MediaServer", "Register streaming source: label=$label, id=$id, mimeType=$mimeType")
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

    private data class StreamEntry(
        val mimeType: String,
        val factory: () -> InputStream
    )

    /**
     * Wraps an InputStream to monitor and log data transfer for diagnostics.
     */
    private class MonitoredInputStream(
        private val delegate: InputStream,
        private val fileName: String
    ) : InputStream() {
        private var totalBytesRead = 0L
        private var firstBytesLogged = false

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) totalBytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val result = try {
                delegate.read(b, off, len)
            } catch (e: IOException) {
                AppLogger.error("MediaServer", "READ ERROR for $fileName after $totalBytesRead bytes: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }

            if (result > 0) {
                totalBytesRead += result
                if (!firstBytesLogged) {
                    firstBytesLogged = true
                    val previewLen = minOf(result, 16)
                    val hex = b.slice(off until off + previewLen)
                        .joinToString(" ") { "%02x".format(it) }
                    AppLogger.info("MediaServer", "First bytes of $fileName: $hex (read $result bytes)")
                }
            }

            if (result <= 0) {
                AppLogger.info("MediaServer", "Stream ended for $fileName: read()=$result, totalBytesRead=$totalBytesRead")
            }

            return result
        }

        override fun available(): Int = delegate.available()

        override fun skip(n: Long): Long {
            val skipped = delegate.skip(n)
            totalBytesRead += skipped
            return skipped
        }

        override fun close() {
            AppLogger.info("MediaServer", "Stream closed for $fileName, totalBytesRead=$totalBytesRead")
            delegate.close()
        }
    }

    private class MediaServer(port: Int, private val resolver: ContentResolver) : NanoHTTPD(port) {
        private val fileMap = mutableMapOf<String, FileEntry>()
        private val streamMap = mutableMapOf<String, StreamEntry>()

        fun registerFile(id: String, file: File, mimeType: String, uri: Uri?) {
            fileMap[id] = FileEntry(file, mimeType, uri)
        }

        fun registerStreamFactory(id: String, mimeType: String, factory: () -> InputStream) {
            streamMap[id] = StreamEntry(mimeType, factory)
        }

        private fun addCorsHeaders(response: Response) {
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Range")
            response.addHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")
        }

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            AppLogger.info("MediaServer", "HTTP ${session.method} $uri (headers: ${session.headers.filterKeys { it in listOf("range", "accept", "user-agent", "connection") }})")

            // Handle CORS preflight requests
            if (session.method == Method.OPTIONS) {
                val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                addCorsHeaders(response)
                response.addHeader("Access-Control-Max-Age", "86400")
                return response
            }

            if (uri.startsWith("/media/")) {
                val id = uri.removePrefix("/media/")

                // Check for streaming source first
                val streamEntry = streamMap[id]
                if (streamEntry != null) {
                    return serveStream(streamEntry)
                }

                val entry = fileMap[id]
                if (entry == null) {
                    AppLogger.warn("MediaServer", "404 Not found: id=$id, registered ids=${fileMap.keys + streamMap.keys}")
                    return newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"
                    )
                }

                if (!entry.file.exists()) {
                    AppLogger.warn("MediaServer", "404 File not found on disk: ${entry.file.absolutePath}")
                    return newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found"
                    )
                }

                val rangeHeader = session.headers["range"]
                return if (rangeHeader != null) {
                    AppLogger.info("MediaServer", "Serving partial: ${entry.file.name} (${entry.file.length()} bytes), range=$rangeHeader")
                    servePartialContent(entry, rangeHeader)
                } else {
                    AppLogger.info("MediaServer", "Serving full: ${entry.file.name} (${entry.file.length()} bytes)")
                    serveFullContent(entry)
                }
            }

            AppLogger.warn("MediaServer", "404 Unknown path: $uri")
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"
            )
        }

        private fun serveStream(entry: StreamEntry): Response {
            return try {
                val stream = entry.factory()
                val monitoredStream = MonitoredInputStream(stream, "streaming-remux")
                AppLogger.info("MediaServer", "Serving streaming source, mimeType=${entry.mimeType}")
                val response = newChunkedResponse(Response.Status.OK, entry.mimeType, monitoredStream)
                addCorsHeaders(response)
                response
            } catch (e: Exception) {
                AppLogger.error("MediaServer", "Error creating stream: ${e.message}")
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}"
                )
            }
        }

        private fun openFileStream(entry: FileEntry): InputStream {
            // Try ContentResolver first (handles scoped storage on Android 10+)
            if (entry.uri != null) {
                try {
                    val pfd = resolver.openFileDescriptor(entry.uri, "r")
                    if (pfd != null) {
                        AppLogger.info("MediaServer", "Opened file via ContentResolver: ${entry.file.name}")
                        return MonitoredInputStream(
                            ParcelFileDescriptor.AutoCloseInputStream(pfd),
                            entry.file.name
                        )
                    }
                } catch (e: Exception) {
                    AppLogger.warn("MediaServer", "ContentResolver failed for ${entry.file.name}: ${e.message}, falling back to FileInputStream")
                }
            }
            // Fall back to direct file access
            AppLogger.info("MediaServer", "Opened file via FileInputStream: ${entry.file.name}")
            return MonitoredInputStream(FileInputStream(entry.file), entry.file.name)
        }

        private fun serveFullContent(entry: FileEntry): Response {
            return try {
                val fis = openFileStream(entry)
                val response = newFixedLengthResponse(
                    Response.Status.OK, entry.mimeType, fis, entry.file.length()
                )
                response.addHeader("Accept-Ranges", "bytes")
                addCorsHeaders(response)
                AppLogger.info("MediaServer", "Response: 200 OK, Content-Type=${entry.mimeType}, Content-Length=${entry.file.length()}")
                response
            } catch (e: Exception) {
                AppLogger.error("MediaServer", "Error serving file ${entry.file.name}: ${e.message}")
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

                val contentRange = "bytes $start-$end/$fileLength"
                val response = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, entry.mimeType, fis, contentLength
                )
                response.addHeader("Content-Range", contentRange)
                response.addHeader("Accept-Ranges", "bytes")
                addCorsHeaders(response)
                AppLogger.info("MediaServer", "Response: 206 Partial Content, Content-Type=${entry.mimeType}, Content-Range=$contentRange, Content-Length=$contentLength")
                response
            } catch (e: Exception) {
                AppLogger.error("MediaServer", "Error serving partial content ${entry.file.name}: ${e.message}")
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}"
                )
            }
        }
    }
}
