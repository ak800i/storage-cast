package com.storagecast.server

import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.storagecast.R
import com.storagecast.StorageCastApp
import com.storagecast.log.AppLogger
import com.storagecast.ui.VideoDetailActivity
import fi.iki.elonen.NanoFixedLengthResponse
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

class MediaServerService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val TAG = "MediaServerService"
        private const val HLS_DRAIN_TTL_MS = 5_000L
        /** Stable ID so the subtitle URL never changes between offset adjustments. */
        private const val ACTIVE_SUBTITLE_ID = "active_subtitle"

        @Volatile
        private var logHandlerInstalled = false
    }

    private val binder = LocalBinder()
    private var server: MediaServer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): MediaServerService = this@MediaServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun startServer(port: Int = 8080): Int {
        installNanoHttpdLogHandler()
        if (server?.isAlive == true) {
            AppLogger.info(TAG, "Server already running on port ${server!!.listeningPort}")
            return server!!.listeningPort
        }
        return try {
            server = MediaServer(port, contentResolver)
            server?.start()
            val actualPort = server!!.listeningPort
            AppLogger.info(TAG, "Server started on port $actualPort")
            promoteToForeground()
            acquireWifiLock()
            actualPort
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to start server on port $port: ${e.message}")
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
                            val cause = thrown.cause
                            if (cause != null) {
                                AppLogger.warn("NanoHTTPD", "  caused by: ${cause.javaClass.simpleName}: ${cause.message}")
                            }
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
        releaseWifiLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        AppLogger.info(TAG, "Server stopped")
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
        val id = ACTIVE_SUBTITLE_ID
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

    /**
     * Registers an on-demand HLS VOD session and returns its base path (`/hls/{id}`).
     * The receiver fetches `master.m3u8` / `playlist.m3u8`, `init.mp4`, `segN.m4s`, and
     * (when subtitles are present) `subs.m3u8` / `subs.vtt` under that prefix.
     */
    fun registerHlsSession(label: String, session: com.storagecast.media.HlsTranscodeSession): String {
        val id = "hls_${label.hashCode().toUInt()}_${System.currentTimeMillis()}"
        server?.registerHlsSession(id, session)
        AppLogger.info("MediaServer", "Register HLS session: label=$label, id=$id")
        return "/hls/$id"
    }

    fun registerHlsSessionWithoutEvict(label: String, session: com.storagecast.media.HlsTranscodeSession): String {
        val id = "hls_${label.hashCode().toUInt()}_${System.currentTimeMillis()}"
        // Register without evicting; capture the SPECIFIC prior session ids to drain. Evicting by an
        // explicit id list (not "all except newest") means a second recast within the TTL window can
        // never release the newest session the receiver just loaded.
        val drained = server?.registerHlsSessionWithoutEvict(id, session) ?: emptyList()
        AppLogger.info("MediaServer", "Register HLS session (no-evict): label=$label, id=$id")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            server?.evictHlsSessions(drained)
        }, HLS_DRAIN_TTL_MS)
        return "/hls/$id"
    }

    fun getServerPort(): Int = server?.listeningPort ?: 8080

    private fun promoteToForeground() {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, VideoDetailActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, StorageCastApp.CHANNEL_MEDIA_SERVER)
            .setContentTitle(getString(R.string.media_server_notification_title))
            .setContentText(getString(R.string.media_server_notification_text))
            .setSmallIcon(R.drawable.ic_play)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        AppLogger.info(TAG, "Promoted to foreground service")
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "StorageCast:MediaServer")
        }
        wifiLock?.takeIf { !it.isHeld }?.acquire()
        AppLogger.info(TAG, "WiFi lock acquired")
    }

    private fun releaseWifiLock() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        AppLogger.info(TAG, "WiFi lock released")
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        releaseWifiLock()
        AppLogger.info(TAG, "Service destroyed")
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
        companion object {
            private const val PROGRESS_LOG_INTERVAL_MS = 5000L
        }

        private var totalBytesRead = 0L
        private var firstBytesLogged = false
        private var lastProgressLog = System.currentTimeMillis()
        private var readCount = 0

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) totalBytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val result = try {
                delegate.read(b, off, len)
            } catch (e: IOException) {
                AppLogger.error("MediaServer", "READ ERROR for $fileName after $totalBytesRead bytes ($readCount reads): ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }

            if (result > 0) {
                totalBytesRead += result
                readCount++
                if (!firstBytesLogged) {
                    firstBytesLogged = true
                    val previewLen = minOf(result, 16)
                    val hex = b.slice(off until off + previewLen)
                        .joinToString(" ") { "%02x".format(it) }
                    AppLogger.info("MediaServer", "First bytes of $fileName: $hex (read $result bytes)")
                }
                // Log progress every 5 seconds during active transfer
                val now = System.currentTimeMillis()
                if (now - lastProgressLog >= PROGRESS_LOG_INTERVAL_MS) {
                    val mb = totalBytesRead / (1024.0 * 1024.0)
                    AppLogger.info("MediaServer", "Transfer progress for $fileName: %.1f MB sent ($readCount reads)".format(mb))
                    lastProgressLog = now
                }
            }

            if (result <= 0) {
                val mb = totalBytesRead / (1024.0 * 1024.0)
                AppLogger.info("MediaServer", "Stream ended for $fileName: read()=$result, totalBytesRead=$totalBytesRead (%.1f MB, $readCount reads)".format(mb))
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
            val mb = totalBytesRead / (1024.0 * 1024.0)
            AppLogger.info("MediaServer", "Stream closed for $fileName, totalBytesRead=$totalBytesRead (%.1f MB, $readCount reads)".format(mb))
            delegate.close()
        }
    }

    private class MediaServer(port: Int, private val resolver: ContentResolver) : NanoHTTPD(port) {
        private val fileMap = ConcurrentHashMap<String, FileEntry>()
        private val streamMap = ConcurrentHashMap<String, StreamEntry>()
        private val hlsMap = ConcurrentHashMap<String, com.storagecast.media.HlsTranscodeSession>()

        fun registerFile(id: String, file: File, mimeType: String, uri: Uri?) {
            fileMap[id] = FileEntry(file, mimeType, uri)
        }

        fun registerStreamFactory(id: String, mimeType: String, factory: () -> InputStream) {
            streamMap[id] = StreamEntry(mimeType, factory)
        }

        fun registerHlsSession(id: String, session: com.storagecast.media.HlsTranscodeSession) {
            // Only one HLS cast is active at a time; evict and free any prior sessions so
            // their cached transcoded segments (several MB each) don't accumulate.
            if (hlsMap.isNotEmpty()) {
                val stale = hlsMap.keys.toList()
                stale.forEach { key -> hlsMap.remove(key)?.release() }
                AppLogger.info("MediaServer", "Evicted ${stale.size} stale HLS session(s)")
            }
            hlsMap[id] = session
        }

        /** Register without evicting; returns the prior session ids (to be drained after a TTL). */
        fun registerHlsSessionWithoutEvict(id: String, session: com.storagecast.media.HlsTranscodeSession): List<String> {
            val prior = hlsMap.keys.toList()
            hlsMap[id] = session
            return prior
        }

        fun evictHlsSessions(ids: List<String>) {
            var n = 0
            ids.forEach { key -> hlsMap.remove(key)?.let { it.release(); n++ } }
            if (n > 0) AppLogger.info("MediaServer", "Drained/evicted $n old HLS session(s)")
        }

        private fun addCorsHeaders(response: Response) {
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Range")
            response.addHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")
        }

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            AppLogger.info("MediaServer", "HTTP ${session.method} $uri")
            AppLogger.info("MediaServer", "  Request headers: ${session.headers}")

            // Handle CORS preflight requests
            if (session.method == Method.OPTIONS) {
                val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                addCorsHeaders(response)
                response.addHeader("Access-Control-Max-Age", "86400")
                return response
            }

            if (uri.startsWith("/hls/")) {
                return serveHls(uri, session.headers["range"])
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
                val monitoredStream = MonitoredInputStream(stream, "streaming-source")
                AppLogger.info("MediaServer", "Serving streaming source, mimeType=${entry.mimeType}")
                val response = newChunkedResponse(Response.Status.OK, entry.mimeType, monitoredStream)
                response.addHeader("Accept-Ranges", "none")
                addCorsHeaders(response)
                response
            } catch (e: Exception) {
                AppLogger.error("MediaServer", "Error creating stream: ${e.message}")
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}"
                )
            }
        }

        /**
         * Serves an HLS VOD resource: media playlist, init segment, or media segment.
         * Segments/init are transcoded on demand and cached by the session.
         * Paths: /hls/{id}/playlist.m3u8, /hls/{id}/init.mp4, /hls/{id}/seg{n}.m4s
         */
        private fun serveHls(uri: String, rangeHeader: String?): Response {
            val rest = uri.removePrefix("/hls/")
            val slash = rest.indexOf('/')
            if (slash <= 0) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Bad HLS path")
            }
            val id = rest.substring(0, slash)
            val resource = rest.substring(slash + 1)
            val hls = hlsMap[id]
            if (hls == null) {
                AppLogger.warn("MediaServer", "404 HLS session not found: id=$id")
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No HLS session")
            }
            val basePath = "/hls/$id"
            return try {
                when {
                    resource == "master.m3u8" -> {
                        val playlist = hls.masterPlaylist(basePath)
                        val resp = newFixedLengthResponse(
                            Response.Status.OK, "application/vnd.apple.mpegurl", playlist
                        )
                        addCorsHeaders(resp)
                        resp.addHeader("Cache-Control", "no-store")
                        AppLogger.info("MediaServer", "Served HLS master ($id, ${playlist.length} chars)")
                        resp
                    }
                    resource == "playlist.m3u8" -> {
                        val playlist = hls.playlist(basePath)
                        val resp = newFixedLengthResponse(
                            Response.Status.OK, "application/vnd.apple.mpegurl", playlist
                        )
                        addCorsHeaders(resp)
                        resp.addHeader("Cache-Control", "no-store")
                        AppLogger.info("MediaServer", "Served HLS playlist ($id, ${playlist.length} chars)")
                        resp
                    }
                    resource == "subs.m3u8" -> {
                        val playlist = hls.subtitlePlaylist(basePath)
                        val resp = newFixedLengthResponse(
                            Response.Status.OK, "application/vnd.apple.mpegurl", playlist
                        )
                        addCorsHeaders(resp)
                        resp.addHeader("Cache-Control", "no-store")
                        AppLogger.info("MediaServer", "Served HLS subtitle playlist ($id)")
                        resp
                    }
                    resource == "subs.vtt" -> {
                        val vtt = hls.subtitleVttBytes()
                        if (vtt == null) {
                            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No subtitles")
                        } else {
                            val resp = newResponse(
                                Response.Status.OK, "text/vtt", java.io.ByteArrayInputStream(vtt), vtt.size.toLong()
                            )
                            addCorsHeaders(resp)
                            resp.addHeader("Cache-Control", "no-store")
                            resp
                        }
                    }
                    resource == "init.mp4" -> {
                        if (hls.draining) return drainingResponse()
                        serveBytes(hls.initBytes(), "video/mp4", rangeHeader)
                    }
                    resource.startsWith("seg") && resource.endsWith(".m4s") -> {
                        val index = resource.removePrefix("seg").removeSuffix(".m4s").toIntOrNull()
                        if (index != null && hls.draining) return drainingResponse()
                        val bytes = if (index != null) hls.segmentBytes(index) else null
                        if (bytes == null) {
                            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No segment")
                        } else {
                            serveBytes(bytes, "video/mp4", rangeHeader)
                        }
                    }
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown HLS resource")
                }
            } catch (e: Exception) {
                AppLogger.error("MediaServer", "HLS serve error for $uri: ${e.javaClass.simpleName}: ${e.message}")
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
            }
        }

        private fun drainingResponse(): Response {
            val resp = newFixedLengthResponse(
                object : Response.IStatus {
                    override fun getDescription() = "503 Service Unavailable"
                    override fun getRequestStatus() = 503
                },
                MIME_PLAINTEXT, "draining"
            )
            resp.addHeader("Retry-After", "1")
            addCorsHeaders(resp)
            return resp
        }

        /** Serves an in-memory byte array, honoring a single Range request if present. */
        private fun serveBytes(data: ByteArray, mimeType: String, rangeHeader: String?): Response {
            if (rangeHeader == null) {
                val resp = newResponse(
                    Response.Status.OK, mimeType, java.io.ByteArrayInputStream(data), data.size.toLong()
                )
                resp.addHeader("Accept-Ranges", "bytes")
                addCorsHeaders(resp)
                return resp
            }
            val range = rangeHeader.replace("bytes=", "")
            val parts = range.split("-")
            val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (data.size - 1L)
            val s = start.coerceIn(0, (data.size - 1).toLong())
            val e = end.coerceIn(s, (data.size - 1).toLong())
            val len = (e - s + 1)
            val stream = java.io.ByteArrayInputStream(data, s.toInt(), len.toInt())
            val resp = newResponse(Response.Status.PARTIAL_CONTENT, mimeType, stream, len)
            resp.addHeader("Content-Range", "bytes $s-$e/${data.size}")
            resp.addHeader("Accept-Ranges", "bytes")
            addCorsHeaders(resp)
            return resp
        }

        private fun openFileStream(entry: FileEntry): InputStream {
            return openFileStreamAtOffset(entry, 0)
        }

        /**
         * Opens a file stream positioned at the given byte offset.
         * Uses FileChannel.position() for O(1) seeking instead of InputStream.skip()
         * which may degrade to O(n) on some Android storage implementations (e.g. FUSE).
         */
        private fun openFileStreamAtOffset(entry: FileEntry, offset: Long): InputStream {
            // Try ContentResolver first (handles scoped storage on Android 10+)
            if (entry.uri != null) {
                try {
                    val pfd = resolver.openFileDescriptor(entry.uri, "r")
                    if (pfd != null) {
                        val fis = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                        if (offset > 0) {
                            fis.channel.position(offset)
                        }
                        AppLogger.info("MediaServer", "Opened file via ContentResolver at offset $offset: ${entry.file.name}")
                        return MonitoredInputStream(fis, entry.file.name)
                    }
                } catch (e: Exception) {
                    AppLogger.warn("MediaServer", "ContentResolver failed for ${entry.file.name}: ${e.message}, falling back to FileInputStream")
                }
            }
            // Fall back to direct file access
            val fis = FileInputStream(entry.file)
            if (offset > 0) {
                fis.channel.position(offset)
            }
            AppLogger.info("MediaServer", "Opened file via FileInputStream at offset $offset: ${entry.file.name}")
            return MonitoredInputStream(fis, entry.file.name)
        }

        /**
         * Creates a fixed-length response that avoids NanoHTTPD's duplicate
         * Content-Length header bug.  NanoHTTPD.newFixedLengthResponse() adds
         * Content-Length to the header map, but Response.send() unconditionally
         * emits another Content-Length via sendContentLengthHeaderIfNotAlreadyPresent(),
         * producing duplicate headers that some HTTP clients reject (notably
         * CrKey/Chrome 92 Cast receivers).  NanoFixedLengthResponse uses the
         * protected Response constructor which sets contentLength internally
         * without adding to the header map, so send() emits exactly one
         * Content-Length.
         */
        private fun newResponse(
            status: Response.IStatus,
            mimeType: String,
            data: java.io.InputStream,
            totalBytes: Long
        ): Response = NanoFixedLengthResponse.create(status, mimeType, data, totalBytes)

        private fun serveFullContent(entry: FileEntry): Response {
            return try {
                val fis = openFileStream(entry)
                val response = newResponse(
                    Response.Status.OK, entry.mimeType, fis, entry.file.length()
                )
                response.addHeader("Accept-Ranges", "bytes")
                addCorsHeaders(response)
                // Prevent the Cast receiver from caching subtitle content so
                // that offset changes served from the same URL are always fresh.
                if (entry.mimeType == "text/vtt") {
                    response.addHeader("Cache-Control", "no-store")
                }
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

                AppLogger.info("MediaServer", "Parsed range: start=$start, end=$end, fileLength=$fileLength")

                val contentLength = end - start + 1
                val fis = openFileStreamAtOffset(entry, start)

                val contentRange = "bytes $start-$end/$fileLength"
                val response = newResponse(
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
