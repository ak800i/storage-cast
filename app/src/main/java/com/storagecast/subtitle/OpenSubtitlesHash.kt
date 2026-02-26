package com.storagecast.subtitle

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Computes the OpenSubtitles hash for a video file.
 * This is the same algorithm used by MPC-HC and other media players
 * that integrate with OpenSubtitles.
 *
 * The hash is computed by:
 * 1. Starting with the file size as an initial value
 * 2. Reading the first 64KB of the file as little-endian 64-bit integers and summing them
 * 3. Reading the last 64KB of the file as little-endian 64-bit integers and summing them
 * 4. Keeping only the lower 64 bits of the result
 */
object OpenSubtitlesHash {

    private const val HASH_CHUNK_SIZE = 65536L

    /**
     * Computes the OpenSubtitles hash for the given file.
     * @return the hash as a 16-character lowercase hex string, or null if the file is too small
     */
    fun computeHash(file: File): String? {
        val fileSize = file.length()
        if (fileSize < HASH_CHUNK_SIZE * 2) {
            return null
        }

        var hash = fileSize

        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(8)

            // Read first 64KB
            for (i in 0 until HASH_CHUNK_SIZE / 8) {
                raf.readFully(buffer)
                hash += ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).long
            }

            // Read last 64KB
            raf.seek(fileSize - HASH_CHUNK_SIZE)
            for (i in 0 until HASH_CHUNK_SIZE / 8) {
                raf.readFully(buffer)
                hash += ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).long
            }
        }

        return String.format("%016x", hash)
    }
}
