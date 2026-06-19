package com.storagecast.media

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Extracts text subtitle tracks from a Matroska (MKV) file by parsing the EBML structure
 * directly. Used as a fallback on devices whose MediaExtractor doesn't expose MKV subtitle
 * tracks (e.g. some Xiaomi/HyperOS builds, which also hide AC-3/E-AC-3 audio). Pure JVM (no
 * Android dependencies) so it is unit-testable.
 *
 * Handles the text subtitle codecs (SubRip / SSA / ASS / WebVTT). Bitmap subtitles (VobSub,
 * PGS) carry no text and are ignored. Cue end times come from the Matroska BlockDuration
 * when present (BlockGroup), otherwise from the next cue's start (capped).
 */
class MkvSubtitleExtractor {

    /** A text subtitle track discovered in the MKV's Tracks element. */
    data class SubTrack(
        val trackNumber: Int,
        val language: String,
        val name: String,
        val codecId: String
    )

    /** A single subtitle cue with absolute millisecond timing. */
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    /** Lists the text subtitle tracks in [file], or empty if none / not an MKV. */
    fun listTracks(file: File): List<SubTrack> {
        if (!file.exists()) return emptyList()
        return try {
            reader(file).use { input ->
                if (!enterSegment(input)) return emptyList()
                // Walk the Segment's top-level children until we find Tracks (or hit data).
                while (true) {
                    val id = readId(input) ?: break
                    val size = readSize(input)
                    when (id) {
                        TRACKS -> return parseTrackEntries(readBytes(input, size))
                        CLUSTER -> break // reached media data without a Tracks element
                        else -> skip(input, size)
                    }
                }
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Extracts every cue for [trackNumber] (a Matroska track number, 1-based). Cleans stray
     * control bytes, converts SSA/ASS dialogue lines to plain text, and fills end times from
     * BlockDuration or the next cue.
     */
    fun extractCues(file: File, trackNumber: Int): List<Cue> {
        if (!file.exists()) return emptyList()
        val raw = mutableListOf<RawCue>()
        var timecodeScaleNs = DEFAULT_TIMECODE_SCALE_NS
        var codecId = ""
        try {
            reader(file).use { input ->
                if (!enterSegment(input)) return emptyList()
                while (true) {
                    val id = readId(input) ?: break
                    val size = readSize(input)
                    when (id) {
                        INFO -> timecodeScaleNs = parseTimecodeScale(readBytes(input, size))
                        TRACKS -> {
                            codecId = parseTrackEntries(readBytes(input, size))
                                .firstOrNull { it.trackNumber == trackNumber }?.codecId ?: codecId
                        }
                        CLUSTER -> parseCluster(input, size, trackNumber, timecodeScaleNs, raw)
                        else -> skip(input, size)
                    }
                }
            }
        } catch (e: Exception) {
            // Return whatever was parsed before the error.
        }
        return finishCues(raw, codecId)
    }

    // ── Cluster / block parsing ───────────────────────────────────────────────

    private fun parseCluster(
        input: DataInputStream,
        clusterSize: Long,
        trackNumber: Int,
        scaleNs: Long,
        out: MutableList<RawCue>
    ) {
        var clusterTimecode = 0L
        var remaining = clusterSize
        val unknownSize = clusterSize < 0
        while (unknownSize || remaining > 0) {
            val id = readId(input) ?: break
            // A top-level element id ends an unknown-size cluster.
            if (unknownSize && (id == CLUSTER || id == TRACKS || id == INFO || id == CUES)) break
            val size = readSize(input)
            when (id) {
                CLUSTER_TIMECODE -> clusterTimecode = readUint(input, size.toInt()).toLong()
                SIMPLE_BLOCK -> parseBlock(readBytes(input, size), clusterTimecode, scaleNs, trackNumber, null, out)
                BLOCK_GROUP -> parseBlockGroup(readBytes(input, size), clusterTimecode, scaleNs, trackNumber, out)
                else -> skip(input, size)
            }
            if (!unknownSize) remaining -= elementFootprint(id, size)
        }
    }

    private fun parseBlockGroup(
        data: ByteArray,
        clusterTimecode: Long,
        scaleNs: Long,
        trackNumber: Int,
        out: MutableList<RawCue>
    ) {
        var blockData: ByteArray? = null
        var durationTc = -1L
        val input = DataInputStream(data.inputStream())
        var pos = 0
        while (pos < data.size) {
            val (id, idLen) = readIdCounted(input) ?: break
            pos += idLen
            val (size, sizeLen) = readSizeCounted(input)
            pos += sizeLen
            when (id) {
                BLOCK -> { blockData = ByteArray(size.toInt()); input.readFully(blockData) }
                BLOCK_DURATION -> { durationTc = readUint(input, size.toInt()).toLong() }
                else -> input.skipBytesFully(size)
            }
            pos += size.toInt()
        }
        if (blockData != null) {
            parseBlock(
                blockData, clusterTimecode, scaleNs, trackNumber,
                if (durationTc >= 0) durationTc else null, out
            )
        }
    }

    private fun parseBlock(
        payload: ByteArray,
        clusterTimecode: Long,
        scaleNs: Long,
        trackNumber: Int,
        durationTc: Long?,
        out: MutableList<RawCue>
    ) {
        if (payload.size < 4) return
        val (blockTrack, vintLen) = readVint(payload, 0)
        if (blockTrack.toInt() != trackNumber) return
        var p = vintLen
        val relTime = ((payload[p].toInt() shl 8) or (payload[p + 1].toInt() and 0xFF)).toShort().toInt()
        p += 2
        val flags = payload[p].toInt() and 0xFF
        p += 1
        // Lacing (bits 0x06) packs multiple frames; subtitle blocks are normally unlaced.
        if (flags and 0x06 != 0) return
        if (p > payload.size) return
        val textBytes = payload.copyOfRange(p, payload.size)
        val text = String(textBytes, Charsets.UTF_8)
        val absTc = clusterTimecode + relTime
        val startMs = absTc * scaleNs / 1_000_000L
        val endMs = durationTc?.let { startMs + it * scaleNs / 1_000_000L }
        out.add(RawCue(startMs, endMs, text))
    }

    private fun finishCues(raw: List<RawCue>, codecId: String): List<Cue> {
        val isAss = codecId.equals("S_TEXT/ASS", true) || codecId.equals("S_TEXT/SSA", true)
        val cleaned = raw.mapNotNull { rc ->
            val text = (if (isAss) assDialogueToText(rc.text) else rc.text).cleanCueText()
            if (text.isEmpty()) null else Triple(rc.startMs, rc.endMs, text)
        }.sortedBy { it.first }

        return cleaned.mapIndexed { i, (startMs, endMs, text) ->
            val nextStart = if (i + 1 < cleaned.size) cleaned[i + 1].first else Long.MAX_VALUE
            val end = endMs ?: minOf(nextStart, startMs + MAX_CUE_DURATION_MS)
            // Never overlap the following cue, and guarantee a readable minimum.
            val clampedEnd = minOf(end, nextStart).coerceAtLeast(startMs + MIN_CUE_DURATION_MS)
            Cue(startMs, clampedEnd, text)
        }
    }

    // ── Tracks parsing ────────────────────────────────────────────────────────

    private fun parseTrackEntries(data: ByteArray): List<SubTrack> {
        val tracks = mutableListOf<SubTrack>()
        val input = DataInputStream(data.inputStream())
        var pos = 0
        while (pos < data.size) {
            val (id, idLen) = readIdCounted(input) ?: break
            pos += idLen
            val (size, sizeLen) = readSizeCounted(input)
            pos += sizeLen
            if (id == TRACK_ENTRY) {
                val entry = ByteArray(size.toInt()); input.readFully(entry)
                parseTrackEntry(entry)?.let { tracks.add(it) }
            } else {
                input.skipBytesFully(size)
            }
            pos += size.toInt()
        }
        return tracks
    }

    private fun parseTrackEntry(data: ByteArray): SubTrack? {
        val input = DataInputStream(data.inputStream())
        var pos = 0
        var trackNumber = 0
        var trackType = 0
        var codecId = ""
        var language = "und"
        var name = ""
        while (pos < data.size) {
            val (id, idLen) = readIdCounted(input) ?: break
            pos += idLen
            val (size, sizeLen) = readSizeCounted(input)
            pos += sizeLen
            when (id) {
                TRACK_NUMBER -> trackNumber = readUint(input, size.toInt())
                TRACK_TYPE -> trackType = readUint(input, size.toInt())
                CODEC_ID -> codecId = readAscii(input, size.toInt())
                LANGUAGE -> language = readAscii(input, size.toInt())
                TRACK_NAME -> name = readUtf8(input, size.toInt())
                else -> input.skipBytesFully(size)
            }
            pos += size.toInt()
        }
        if (trackType != TRACK_TYPE_SUBTITLE) return null
        if (codecId.uppercase() !in TEXT_CODECS) return null
        return SubTrack(trackNumber, language.ifEmpty { "und" }, name, codecId)
    }

    private fun parseTimecodeScale(data: ByteArray): Long {
        val input = DataInputStream(data.inputStream())
        var pos = 0
        while (pos < data.size) {
            val (id, idLen) = readIdCounted(input) ?: break
            pos += idLen
            val (size, sizeLen) = readSizeCounted(input)
            pos += sizeLen
            if (id == TIMECODE_SCALE) {
                return readUint(input, size.toInt()).toLong().takeIf { it > 0 } ?: DEFAULT_TIMECODE_SCALE_NS
            }
            input.skipBytesFully(size)
            pos += size.toInt()
        }
        return DEFAULT_TIMECODE_SCALE_NS
    }

    // ── Text conversion ───────────────────────────────────────────────────────

    /**
     * Converts a Matroska SSA/ASS block (the dialogue fields, comma-separated, with the text
     * as the final field) to plain text: strips override tags `{\...}` and turns `\N`/`\n`
     * line breaks into newlines.
     */
    private fun assDialogueToText(block: String): String {
        // Fields: ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text
        val text = block.split(",", limit = 9).getOrNull(8) ?: return ""
        return text
            .replace(Regex("\\{[^}]*}"), "")
            .replace("\\N", "\n").replace("\\n", "\n")
            .replace("\\h", " ")
    }

    // ── EBML I/O ──────────────────────────────────────────────────────────────

    private fun reader(file: File): DataInputStream =
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16))

    /** Reads the EBML header + opens the Segment, leaving the stream at the Segment's body. */
    private fun enterSegment(input: DataInputStream): Boolean {
        val headerId = readId(input) ?: return false
        if (headerId != EBML_HEADER) return false
        skip(input, readSize(input))
        val segId = readId(input) ?: return false
        if (segId != SEGMENT) return false
        readSize(input) // segment size (often unknown / large) — we stop at EOF
        return true
    }

    private fun readId(input: DataInputStream): Long? = readIdCounted(input)?.first

    private fun readIdCounted(input: DataInputStream): Pair<Long, Int>? {
        val first = try { input.read() } catch (e: EOFException) { -1 }
        if (first < 0) return null
        val numBytes = when {
            first and 0x80 != 0 -> 1
            first and 0x40 != 0 -> 2
            first and 0x20 != 0 -> 3
            first and 0x10 != 0 -> 4
            else -> throw IOException("Invalid EBML id 0x${first.toString(16)}")
        }
        var value = first.toLong()
        for (i in 1 until numBytes) value = (value shl 8) or input.readUnsignedByte().toLong()
        return Pair(value, numBytes)
    }

    private fun readSize(input: DataInputStream): Long = readSizeCounted(input).first

    private fun readSizeCounted(input: DataInputStream): Pair<Long, Int> {
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
            else -> throw IOException("Invalid EBML size 0x${first.toString(16)}")
        }
        var value = (first and mask).toLong()
        var allOnes = (first and mask) == mask
        for (i in 1 until numBytes) {
            val b = input.readUnsignedByte()
            value = (value shl 8) or b.toLong()
            if (b != 0xFF) allOnes = false
        }
        return Pair(if (allOnes) -1L else value, numBytes)
    }

    private fun readUint(input: DataInputStream, size: Int): Int {
        var value = 0L
        repeat(size) { value = (value shl 8) or input.readUnsignedByte().toLong() }
        return value.toInt()
    }

    private fun readAscii(input: DataInputStream, size: Int): String {
        val b = ByteArray(size); input.readFully(b)
        return String(b, Charsets.US_ASCII).trimEnd('\u0000')
    }

    private fun readUtf8(input: DataInputStream, size: Int): String {
        val b = ByteArray(size); input.readFully(b)
        return String(b, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun readBytes(input: DataInputStream, size: Long): ByteArray {
        if (size < 0) return ByteArray(0)
        val b = ByteArray(size.toInt()); input.readFully(b); return b
    }

    private fun skip(input: DataInputStream, size: Long) {
        if (size > 0) input.skipBytesFully(size)
    }

    /** Total bytes a child element occupies (id + size descriptor + body). */
    private fun elementFootprint(id: Long, size: Long): Long =
        idByteLength(id) + sizeByteLength(size) + (if (size > 0) size else 0)

    private fun idByteLength(id: Long): Int = when {
        id <= 0xFFL -> 1
        id <= 0xFFFFL -> 2
        id <= 0xFFFFFFL -> 3
        else -> 4
    }

    private fun sizeByteLength(size: Long): Int = when {
        size < 0 -> 1
        size < 0x7FL -> 1
        size < 0x3FFFL -> 2
        size < 0x1FFFFFL -> 3
        size < 0xFFFFFFFL -> 4
        else -> 8
    }

    /** Reads an EBML variable-length integer from [bytes] at [offset]: (value, byteLength). */
    private fun readVint(bytes: ByteArray, offset: Int): Pair<Long, Int> {
        val first = bytes[offset].toInt() and 0xFF
        val numBytes: Int
        val mask: Int
        when {
            first and 0x80 != 0 -> { numBytes = 1; mask = 0x7F }
            first and 0x40 != 0 -> { numBytes = 2; mask = 0x3F }
            first and 0x20 != 0 -> { numBytes = 3; mask = 0x1F }
            first and 0x10 != 0 -> { numBytes = 4; mask = 0x0F }
            else -> { numBytes = 1; mask = 0x7F }
        }
        var value = (first and mask).toLong()
        for (i in 1 until numBytes) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return Pair(value, numBytes)
    }

    private fun DataInputStream.skipBytesFully(n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() < 0) throw EOFException()
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun String.cleanCueText(): String =
        filter { it == '\n' || it == '\t' || it.code >= 0x20 }.trim()

    private data class RawCue(val startMs: Long, val endMs: Long?, val text: String)

    companion object {
        private const val DEFAULT_TIMECODE_SCALE_NS = 1_000_000L // 1 ms
        private const val MAX_CUE_DURATION_MS = 7_000L
        private const val MIN_CUE_DURATION_MS = 200L

        private const val EBML_HEADER = 0x1A45DFA3L
        private const val SEGMENT = 0x18538067L
        private const val INFO = 0x1549A966L
        private const val TIMECODE_SCALE = 0x2AD7B1L
        private const val TRACKS = 0x1654AE6BL
        private const val TRACK_ENTRY = 0xAEL
        private const val TRACK_NUMBER = 0xD7L
        private const val TRACK_TYPE = 0x83L
        private const val CODEC_ID = 0x86L
        private const val LANGUAGE = 0x22B59CL
        private const val TRACK_NAME = 0x536EL
        private const val CLUSTER = 0x1F43B675L
        private const val CLUSTER_TIMECODE = 0xE7L
        private const val SIMPLE_BLOCK = 0xA3L
        private const val BLOCK_GROUP = 0xA0L
        private const val BLOCK = 0xA1L
        private const val BLOCK_DURATION = 0x9BL
        private const val CUES = 0x1C53BB6BL

        private const val TRACK_TYPE_SUBTITLE = 0x11

        private val TEXT_CODECS = setOf("S_TEXT/UTF8", "S_TEXT/SSA", "S_TEXT/ASS", "S_TEXT/WEBVTT")
    }
}
