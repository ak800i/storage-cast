package com.storagecast.media

import com.storagecast.log.AppLogger
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Filters Matroska (MKV) container files to keep only selected tracks.
 * Operates at the binary EBML level — zero transcoding, pure byte copy.
 * Produces a valid MKV stream to an OutputStream for streaming to HTTP clients.
 */
class MkvTrackFilter {

    companion object {
        private const val TAG = "MkvTrackFilter"
        private const val PIPE_BUFFER_SIZE = 2 * 1024 * 1024 // 2 MB

        // EBML Element IDs
        private const val EBML_HEADER = 0x1A45DFA3L
        private const val SEGMENT = 0x18538067L
        private const val SEEK_HEAD = 0x114D9B74L
        private const val INFO = 0x1549A966L
        private const val TRACKS = 0x1654AE6BL
        private const val TRACK_ENTRY = 0xAEL
        private const val TRACK_NUMBER = 0xD7L
        private const val TRACK_TYPE = 0x83L
        private const val CLUSTER = 0x1F43B675L
        private const val TIMECODE = 0xE7L
        private const val SIMPLE_BLOCK = 0xA3L
        private const val BLOCK_GROUP = 0xA0L
        private const val BLOCK = 0xA1L
        private const val CUES = 0x1C53BB6BL
        private const val TAGS = 0x1254C367L
        private const val CHAPTERS = 0x1043A770L
        private const val ATTACHMENTS = 0x1941A469L

        // Track types
        private const val TRACK_TYPE_VIDEO = 1
        private const val TRACK_TYPE_AUDIO = 2

        // Master (container) elements that should be recursed into
        private val MASTER_ELEMENTS = setOf(
            EBML_HEADER, SEGMENT, SEEK_HEAD, INFO, TRACKS, TRACK_ENTRY,
            CLUSTER, BLOCK_GROUP, CUES, TAGS, CHAPTERS, ATTACHMENTS,
            // Common sub-elements that are master elements
            0x1549A966L, // Info
            0xAEL, // TrackEntry
            0xE0L, // Video settings
            0xE1L, // Audio settings
            0x6D80L, // ContentEncodings
            0x6240L, // ContentEncoding
            0xBBL, // CuePoint
            0xB7L, // CueTrackPositions
            0x6924L, // ChapterAtom
            0x80L,   // ChapterDisplay
            0x7373L, // Tag
            0x67C8L, // SimpleTag
            0x61A7L, // Attached file
        )
    }

    /**
     * Creates a PipedInputStream that streams the filtered MKV data.
     * Starts a background thread to read the source and write filtered output.
     *
     * @param sourceStream InputStream of the original MKV file
     * @param keepTrackNumbers MKV track numbers to keep (1-based)
     * @return InputStream that produces the filtered MKV data
     */
    fun createFilteredStream(
        sourceStream: InputStream,
        keepTrackNumbers: Set<Int>
    ): InputStream {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, PIPE_BUFFER_SIZE)

        Thread {
            try {
                filter(sourceStream, pipedOut, keepTrackNumbers)
            } catch (e: IOException) {
                // Pipe broken = reader closed (Cast device disconnected) — expected
                AppLogger.info(TAG, "Stream ended: ${e.message}")
            } catch (e: Exception) {
                AppLogger.error(TAG, "Filter error: ${e.message}")
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
                try { sourceStream.close() } catch (_: Exception) {}
            }
        }.apply { name = "MkvTrackFilter" }.start()

        return pipedIn
    }

    /**
     * Filters the MKV source, writing only the selected tracks to the output.
     */
    fun filter(source: InputStream, output: OutputStream, keepTrackNumbers: Set<Int>) {
        val input = DataInputStream(source.buffered(65536))
        val out = output.buffered(65536)

        AppLogger.info(TAG, "Starting MKV filter, keeping tracks: $keepTrackNumbers")

        // Read and copy EBML header
        val (headerId, _) = readElementId(input)
        if (headerId != EBML_HEADER) {
            throw IOException("Not an MKV file (expected EBML header, got 0x${headerId.toString(16)})")
        }
        val headerSize = readElementSize(input)
        val headerData = ByteArray(headerSize.toInt())
        input.readFully(headerData)

        // Write EBML header as-is
        writeElementId(out, EBML_HEADER)
        writeElementSize(out, headerSize)
        out.write(headerData)

        // Read Segment
        val (segId, _) = readElementId(input)
        if (segId != SEGMENT) {
            throw IOException("Expected Segment element, got 0x${segId.toString(16)}")
        }
        val segmentSize = readElementSize(input)

        // Write Segment with unknown size (for streaming)
        writeElementId(out, SEGMENT)
        writeUnknownSize(out)

        // Process Segment children
        val isUnknownSize = segmentSize < 0
        var bytesRead = 0L
        val segmentEnd = if (isUnknownSize) Long.MAX_VALUE else segmentSize

        while (bytesRead < segmentEnd) {
            val (elemId, idBytes) = try {
                readElementId(input)
            } catch (e: Exception) {
                break // EOF
            }
            bytesRead += idBytes

            val elemSize = readElementSize(input)
            bytesRead += vintEncodedLength(elemSize)

            when (elemId) {
                SEEK_HEAD, CUES -> {
                    // Skip SeekHead and Cues — positions are invalid after filtering
                    skipBytes(input, elemSize)
                    bytesRead += elemSize
                }
                TRACKS -> {
                    // Filter track entries to keep only selected tracks
                    val tracksData = ByteArray(elemSize.toInt())
                    input.readFully(tracksData)
                    bytesRead += elemSize

                    val filteredTracks = filterTracks(tracksData, keepTrackNumbers)
                    writeElementId(out, TRACKS)
                    writeElementSize(out, filteredTracks.size.toLong())
                    out.write(filteredTracks)
                }
                CLUSTER -> {
                    // Filter blocks within cluster
                    val clusterData = ByteArray(elemSize.toInt())
                    input.readFully(clusterData)
                    bytesRead += elemSize

                    val filteredCluster = filterCluster(clusterData, keepTrackNumbers)
                    if (filteredCluster.isNotEmpty()) {
                        writeElementId(out, CLUSTER)
                        writeElementSize(out, filteredCluster.size.toLong())
                        out.write(filteredCluster)
                        out.flush()
                    }
                }
                else -> {
                    // Copy other elements as-is (Info, Tags, Chapters, etc.)
                    writeElementId(out, elemId)
                    writeElementSize(out, elemSize)
                    copyBytes(input, out, elemSize)
                    bytesRead += elemSize
                }
            }
        }

        out.flush()
        AppLogger.info(TAG, "MKV filter complete, processed $bytesRead bytes")
    }

    /**
     * Filters TrackEntry elements within the Tracks element.
     * Returns only track entries whose TrackNumber is in keepTrackNumbers.
     */
    private fun filterTracks(data: ByteArray, keepTrackNumbers: Set<Int>): ByteArray {
        val input = DataInputStream(data.inputStream())
        val output = java.io.ByteArrayOutputStream()
        var pos = 0

        while (pos < data.size) {
            val (elemId, idBytes) = readElementId(input)
            pos += idBytes
            val elemSize = readElementSize(input)
            pos += vintEncodedLength(elemSize)

            if (elemId == TRACK_ENTRY) {
                val entryData = ByteArray(elemSize.toInt())
                input.readFully(entryData)
                pos += elemSize.toInt()

                val trackNumber = extractTrackNumber(entryData)
                if (trackNumber != null && trackNumber in keepTrackNumbers) {
                    writeElementId(output, TRACK_ENTRY)
                    writeElementSize(output, elemSize)
                    output.write(entryData)
                }
            } else {
                // Copy non-TrackEntry elements
                val elemData = ByteArray(elemSize.toInt())
                input.readFully(elemData)
                pos += elemSize.toInt()
                writeElementId(output, elemId)
                writeElementSize(output, elemSize)
                output.write(elemData)
            }
        }

        return output.toByteArray()
    }

    /**
     * Extracts the TrackNumber from a TrackEntry's raw data.
     */
    private fun extractTrackNumber(data: ByteArray): Int? {
        val input = DataInputStream(data.inputStream())
        var pos = 0

        while (pos < data.size) {
            val (elemId, idBytes) = try {
                readElementId(input)
            } catch (e: Exception) { break }
            pos += idBytes
            val elemSize = try {
                readElementSize(input)
            } catch (e: Exception) { break }
            pos += vintEncodedLength(elemSize)

            if (elemId == TRACK_NUMBER) {
                // TrackNumber is a uint, read its value
                var value = 0L
                for (i in 0 until elemSize.toInt()) {
                    value = (value shl 8) or (input.readUnsignedByte().toLong())
                }
                return value.toInt()
            } else {
                skipBytes(input, elemSize)
            }
            pos += elemSize.toInt()
        }

        return null
    }

    /**
     * Filters blocks within a Cluster, keeping only blocks for selected tracks.
     */
    private fun filterCluster(data: ByteArray, keepTrackNumbers: Set<Int>): ByteArray {
        val input = DataInputStream(data.inputStream())
        val output = java.io.ByteArrayOutputStream()
        var pos = 0

        while (pos < data.size) {
            val (elemId, idBytes) = try {
                readElementId(input)
            } catch (e: Exception) { break }
            pos += idBytes
            val elemSize = try {
                readElementSize(input)
            } catch (e: Exception) { break }
            pos += vintEncodedLength(elemSize)

            when (elemId) {
                SIMPLE_BLOCK -> {
                    val blockData = ByteArray(elemSize.toInt())
                    input.readFully(blockData)
                    pos += elemSize.toInt()

                    val trackNum = readBlockTrackNumber(blockData)
                    if (trackNum in keepTrackNumbers) {
                        writeElementId(output, SIMPLE_BLOCK)
                        writeElementSize(output, elemSize)
                        output.write(blockData)
                    }
                }
                BLOCK_GROUP -> {
                    val groupData = ByteArray(elemSize.toInt())
                    input.readFully(groupData)
                    pos += elemSize.toInt()

                    val trackNum = extractBlockGroupTrackNumber(groupData)
                    if (trackNum != null && trackNum in keepTrackNumbers) {
                        writeElementId(output, BLOCK_GROUP)
                        writeElementSize(output, elemSize)
                        output.write(groupData)
                    }
                }
                else -> {
                    // Copy Timecode, Position, PrevSize, etc.
                    val elemData = ByteArray(elemSize.toInt())
                    input.readFully(elemData)
                    pos += elemSize.toInt()
                    writeElementId(output, elemId)
                    writeElementSize(output, elemSize)
                    output.write(elemData)
                }
            }
        }

        return output.toByteArray()
    }

    /**
     * Reads the track number from the beginning of a SimpleBlock/Block data.
     * The track number is encoded as an EBML variable-length integer.
     */
    private fun readBlockTrackNumber(data: ByteArray): Int {
        if (data.isEmpty()) return -1
        val firstByte = data[0].toInt() and 0xFF
        return when {
            firstByte and 0x80 != 0 -> firstByte and 0x7F
            firstByte and 0x40 != 0 -> {
                if (data.size < 2) return -1
                ((firstByte and 0x3F) shl 8) or (data[1].toInt() and 0xFF)
            }
            else -> -1 // Track numbers > 16383 are extremely rare
        }
    }

    /**
     * Extracts the track number from a BlockGroup by finding the Block element inside.
     */
    private fun extractBlockGroupTrackNumber(data: ByteArray): Int? {
        val input = DataInputStream(data.inputStream())
        var pos = 0

        while (pos < data.size) {
            val (elemId, idBytes) = try {
                readElementId(input)
            } catch (e: Exception) { break }
            pos += idBytes
            val elemSize = try {
                readElementSize(input)
            } catch (e: Exception) { break }
            pos += vintEncodedLength(elemSize)

            if (elemId == BLOCK) {
                val blockData = ByteArray(minOf(elemSize.toInt(), 4))
                input.readFully(blockData)
                return readBlockTrackNumber(blockData)
            } else {
                skipBytes(input, elemSize)
            }
            pos += elemSize.toInt()
        }
        return null
    }

    // ──── EBML I/O Utilities ────

    /**
     * Reads an EBML Element ID (variable-length, marker bit is part of ID).
     * Returns (elementId, numberOfBytesRead).
     */
    private fun readElementId(input: DataInputStream): Pair<Long, Int> {
        val first = input.readUnsignedByte()
        val numBytes = when {
            first and 0x80 != 0 -> 1
            first and 0x40 != 0 -> 2
            first and 0x20 != 0 -> 3
            first and 0x10 != 0 -> 4
            else -> throw IOException("Invalid EBML element ID leading byte: 0x${first.toString(16)}")
        }
        var value = first.toLong()
        for (i in 1 until numBytes) {
            value = (value shl 8) or input.readUnsignedByte().toLong()
        }
        return Pair(value, numBytes)
    }

    /**
     * Reads an EBML element data size (variable-length, marker bit is removed).
     * Returns the data size, or -1 for unknown/indeterminate size.
     */
    private fun readElementSize(input: DataInputStream): Long {
        val first = input.readUnsignedByte()
        val numBytes: Int
        val mask: Int

        when {
            first and 0x80 != 0 -> { numBytes = 1; mask = 0x7F }
            first and 0x40 != 0 -> { numBytes = 2; mask = 0x3F }
            first and 0x20 != 0 -> { numBytes = 3; mask = 0x1F }
            first and 0x10 != 0 -> { numBytes = 4; mask = 0x0F }
            first and 0x08 != 0 -> { numBytes = 5; mask = 0x07 }
            first and 0x04 != 0 -> { numBytes = 6; mask = 0x03 }
            first and 0x02 != 0 -> { numBytes = 7; mask = 0x01 }
            first and 0x01 != 0 -> { numBytes = 8; mask = 0x00 }
            else -> throw IOException("Invalid EBML size leading byte: 0x${first.toString(16)}")
        }

        var value = (first and mask).toLong()
        var allOnes = (first and mask) == mask
        for (i in 1 until numBytes) {
            val b = input.readUnsignedByte()
            value = (value shl 8) or b.toLong()
            if (b != 0xFF) allOnes = false
        }

        // All data bits set = unknown size
        return if (allOnes) -1 else value
    }

    /**
     * Returns how many bytes an EBML size value would take to encode.
     */
    private fun vintEncodedLength(size: Long): Int {
        if (size < 0) return 8 // unknown size
        return when {
            size < 0x7FL -> 1
            size < 0x3FFFL -> 2
            size < 0x1FFFFFL -> 3
            size < 0x0FFFFFFFL -> 4
            size < 0x07FFFFFFFFL -> 5
            size < 0x03FFFFFFFFFFL -> 6
            size < 0x01FFFFFFFFFFFFL -> 7
            else -> 8
        }
    }

    /**
     * Writes an EBML Element ID to the output.
     */
    private fun writeElementId(out: OutputStream, id: Long) {
        val bytes = when {
            id < 0x100L -> 1
            id < 0x10000L -> 2
            id < 0x1000000L -> 3
            else -> 4
        }
        for (i in bytes - 1 downTo 0) {
            out.write(((id shr (i * 8)) and 0xFF).toInt())
        }
    }

    /**
     * Writes an EBML element data size to the output.
     */
    private fun writeElementSize(out: OutputStream, size: Long) {
        if (size < 0) {
            writeUnknownSize(out)
            return
        }
        val numBytes = vintEncodedLength(size)
        val marker = 1L shl (7 * numBytes) // marker bit position
        val value = marker or size

        for (i in numBytes - 1 downTo 0) {
            out.write(((value shr (i * 8)) and 0xFF).toInt())
        }
    }

    /**
     * Writes an 8-byte unknown/indeterminate size marker.
     */
    private fun writeUnknownSize(out: OutputStream) {
        out.write(0x01)
        for (i in 0 until 7) out.write(0xFF)
    }

    private fun skipBytes(input: DataInputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skipBytes(minOf(remaining, Int.MAX_VALUE.toLong()).toInt()).toLong()
            if (skipped <= 0) {
                // skipBytes may return 0, fall back to read
                if (input.read() < 0) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun copyBytes(input: DataInputStream, output: OutputStream, count: Long) {
        val buf = ByteArray(65536)
        var remaining = count
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val read = input.read(buf, 0, toRead)
            if (read <= 0) break
            output.write(buf, 0, read)
            remaining -= read
        }
    }
}
