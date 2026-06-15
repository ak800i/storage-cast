package com.storagecast.media

import java.io.ByteArrayOutputStream

/**
 * Builds the `dac3` (AC-3) and `dec3` (E-AC-3) codec configuration box payloads that
 * an MP4 `ac-3`/`ec-3` sample entry requires, by parsing the bit-stream sync frame.
 *
 * References: ETSI TS 102 366 — Annex E (E-AC-3 sync frame), Annex F (MP4 mapping,
 * `dac3`/`dec3` box layouts).
 *
 * All builders return `null` when the frame can't be parsed (wrong codec, no sync
 * word, reserved values), so callers can fall back to transcoding.
 */
object DolbyAudioConfig {

    private const val SYNC_WORD_0 = 0x0B
    private const val SYNC_WORD_1 = 0x77

    /** AC-3 sample-rate codes → Hz (fscod). */
    private val AC3_SAMPLE_RATES = intArrayOf(48000, 44100, 32000)

    /** E-AC-3 number-of-audio-blocks per frame (numblkscod). */
    private val EAC3_NUM_BLOCKS = intArrayOf(1, 2, 3, 6)

    /**
     * Parses an AC-3 sync frame and returns the 3-byte `dac3` box contents, or null.
     */
    fun buildDac3(frame: ByteArray): ByteArray? {
        val sync = findSyncWord(frame)
        if (sync < 0) return null
        val r = BitReader(frame, (sync + 2) * 8) // skip the 16-bit sync word
        return try {
            r.read(16)                       // crc1
            val fscod = r.read(2)
            if (fscod == 3) return null      // reserved sample rate
            val frmsizecod = r.read(6)
            val bsid = r.read(5)
            if (bsid > 8) return null        // not plain AC-3
            val bsmod = r.read(3)
            val acmod = r.read(3)
            if (acmod and 0x1 != 0 && acmod != 0x1) r.read(2) // cmixlev
            if (acmod and 0x4 != 0) r.read(2)                 // surmixlev
            if (acmod == 0x2) r.read(2)                       // dsurmod
            val lfeon = r.read(1)
            val bitRateCode = frmsizecod ushr 1

            val w = BitWriter()
            w.write(fscod, 2)
            w.write(bsid, 5)
            w.write(bsmod, 3)
            w.write(acmod, 3)
            w.write(lfeon, 1)
            w.write(bitRateCode, 5)
            w.write(0, 5)                    // reserved
            w.bytes()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses an E-AC-3 (independent) sync frame and returns the 5-byte `dec3` box
     * contents for a single independent substream with no dependent substreams, or null.
     */
    fun buildDec3(frame: ByteArray): ByteArray? {
        val sync = findSyncWord(frame)
        if (sync < 0) return null
        val r = BitReader(frame, (sync + 2) * 8) // skip the 16-bit sync word
        return try {
            val strmtyp = r.read(2)
            if (strmtyp == 3) return null    // reserved
            r.read(3)                        // substreamid
            val frmsiz = r.read(11)
            val fscod = r.read(2)
            val fscod2: Int
            val numblkscod: Int
            if (fscod == 3) {
                fscod2 = r.read(2)
                numblkscod = 3               // 6 blocks at the reduced sample rate
            } else {
                fscod2 = -1
                numblkscod = r.read(2)
            }
            val acmod = r.read(3)
            val lfeon = r.read(1)
            val bsid = r.read(5)
            if (bsid < 11 || bsid > 16) return null // not E-AC-3

            // Nominal data rate (kbps) computed from this frame.
            val sampleRate = if (fscod == 3) {
                when (fscod2) { 0 -> 24000; 1 -> 22050; 2 -> 16000; else -> return null }
            } else AC3_SAMPLE_RATES[fscod]
            val numBlocks = EAC3_NUM_BLOCKS[numblkscod]
            val frameBytes = (frmsiz + 1) * 2
            val samplesPerFrame = numBlocks * 256
            val dataRateKbps = (frameBytes.toLong() * 8 * sampleRate / samplesPerFrame / 1000)
                .toInt().coerceIn(0, 8191)

            val w = BitWriter()
            w.write(dataRateKbps, 13)
            w.write(0, 3)                    // num_ind_sub = 0 (one independent substream)
            // independent substream descriptor
            w.write(fscod, 2)
            w.write(bsid, 5)
            w.write(0, 1)                    // reserved
            w.write(0, 1)                    // asvc
            w.write(0, 3)                    // bsmod (assume main, complete)
            w.write(acmod, 3)
            w.write(lfeon, 1)
            w.write(0, 3)                    // reserved
            w.write(0, 4)                    // num_dep_sub = 0
            w.write(0, 1)                    // reserved (no chan_loc since num_dep_sub == 0)
            w.bytes()
        } catch (e: Exception) {
            null
        }
    }

    private fun findSyncWord(data: ByteArray): Int {
        var i = 0
        while (i < data.size - 1) {
            if ((data[i].toInt() and 0xFF) == SYNC_WORD_0 &&
                (data[i + 1].toInt() and 0xFF) == SYNC_WORD_1
            ) return i
            i++
        }
        return -1
    }

    /** MSB-first bit reader over a byte array. */
    private class BitReader(private val data: ByteArray, private var bitPos: Int) {
        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val bytePos = bitPos ushr 3
                val bit = 7 - (bitPos and 7)
                val b = if (bytePos < data.size) (data[bytePos].toInt() ushr bit) and 1 else 0
                v = (v shl 1) or b
                bitPos++
            }
            return v
        }
    }

    /** MSB-first bit writer; pads the final byte with zero bits. */
    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var cur = 0
        private var nbits = 0

        fun write(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                cur = (cur shl 1) or ((value ushr i) and 1)
                nbits++
                if (nbits == 8) { out.write(cur); cur = 0; nbits = 0 }
            }
        }

        fun bytes(): ByteArray {
            if (nbits > 0) { out.write(cur shl (8 - nbits)); cur = 0; nbits = 0 }
            return out.toByteArray()
        }
    }
}
