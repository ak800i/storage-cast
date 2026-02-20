package com.storagecast.server

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
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
        if (server?.isAlive == true) return server!!.listeningPort
        server = MediaServer(port)
        server?.start()
        return server!!.listeningPort
    }

    fun stopServer() {
        server?.stop()
        server = null
    }

    fun registerFile(path: String, mimeType: String): String {
        val file = File(path)
        val id = file.name.hashCode().toUInt().toString()
        server?.registerFile(id, file, mimeType)
        return "/media/$id"
    }

    fun registerSubtitle(subtitleFile: File): String {
        val id = subtitleFile.name.hashCode().toUInt().toString()
        server?.registerFile(id, subtitleFile, "text/vtt")
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

            if (uri.startsWith("/media/")) {
                val id = uri.removePrefix("/media/")
                val (file, mimeType) = fileMap[id] ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"
                )

                if (!file.exists()) {
                    return newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found"
                    )
                }

                val rangeHeader = session.headers["range"]
                return if (rangeHeader != null) {
                    servePartialContent(file, mimeType, rangeHeader)
                } else {
                    serveFullContent(file, mimeType)
                }
            }

            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"
            )
        }

        private fun serveFullContent(file: File, mimeType: String): Response {
            val fis = FileInputStream(file)
            val response = newFixedLengthResponse(
                Response.Status.OK, mimeType, fis, file.length()
            )
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Access-Control-Allow-Origin", "*")
            return response
        }

        private fun servePartialContent(
            file: File, mimeType: String, rangeHeader: String
        ): Response {
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
            return response
        }
    }
}
