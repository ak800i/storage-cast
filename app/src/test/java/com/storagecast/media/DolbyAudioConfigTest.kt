package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Round-trip tests for [DolbyAudioConfig]: synthesize spec-accurate AC-3 / E-AC-3
 * sync frames, run them through buildDac3 / buildDec3, then decode the returned
 * `dac3` / `dec3` box payloads and assert each field. A wrong Dolby config box is
 * exactly what would silently break 5.1 passthrough on a device, so it is validated
 * here in pure JVM.
 */
class DolbyAudioConfigTest {

    /** MSB-first bit writer matching the parser's bit order. */
    private class BW {
        private val out = ArrayList<Int>()
        private var cur = 0
        private var n = 0
        fun w(value: Int, bits: Int): BW {
            for (i in bits - 1 downTo 0) {
                cur = (cur shl 1) or ((value ushr i) and 1)
                n++
                if (n == 8) { out.add(cur); cur = 0; n = 0 }
            }
            return this
        }
        fun bytes(): ByteArray {
            if (n > 0) { out.add(cur shl (8 - n)); cur = 0; n = 0 }
            return ByteArray(out.size) { out[it].toByte() }
        }
    }

    /** MSB-first bit reader for decoding the produced config boxes. */
    private class BR(private val d: ByteArray) {
        private var pos = 0
        fun r(bits: Int): Int {
            var v = 0
            repeat(bits) {
                val b = (d[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
                v = (v shl 1) or b
                pos++
            }
            return v
        }
    }

    @Test
    fun buildDec3_roundTripsIndependentSubstream() {
        // Synthesize an E-AC-3 sync frame: 0x0B77 + bitstream fields.
        val frmsiz = 703            // frameBytes = (703+1)*2 = 1408
        val fscod = 0               // 48 kHz
        val numblkscod = 3          // 6 blocks → 1536 samples/frame
        val acmod = 7               // 3/2 (L C R Ls Rs)
        val lfeon = 1
        val bsid = 16               // E-AC-3
        val payload = BW()
            .w(0, 2)                // strmtyp = independent
            .w(0, 3)                // substreamid
            .w(frmsiz, 11)
            .w(fscod, 2)
            .w(numblkscod, 2)
            .w(acmod, 3)
            .w(lfeon, 1)
            .w(bsid, 5)
            .w(0, 16)               // trailing bits so reads don't run off the end
            .bytes()
        val frame = byteArrayOf(0x0B, 0x77) + payload

        val dec3 = DolbyAudioConfig.buildDec3(frame)
        assertNotNull("dec3 produced", dec3)
        assertEquals("dec3 is 5 bytes", 5, dec3!!.size)

        val expectedRate = (1408L * 8 * 48000 / 1536 / 1000).toInt() // 352
        val br = BR(dec3)
        assertEquals("data_rate", expectedRate, br.r(13))
        assertEquals("num_ind_sub", 0, br.r(3))
        assertEquals("fscod", fscod, br.r(2))
        assertEquals("bsid", bsid, br.r(5))
        assertEquals("reserved", 0, br.r(1))
        assertEquals("asvc", 0, br.r(1))
        assertEquals("bsmod", 0, br.r(3))
        assertEquals("acmod", acmod, br.r(3))
        assertEquals("lfeon", lfeon, br.r(1))
        assertEquals("reserved2", 0, br.r(3))
        assertEquals("num_dep_sub", 0, br.r(4))
        assertEquals("reserved3", 0, br.r(1))
    }

    @Test
    fun buildDac3_roundTripsAc3Frame() {
        // Synthesize an AC-3 sync frame with acmod=2 (stereo) so the dsurmod branch runs.
        val fscod = 0               // 48 kHz
        val frmsizecod = 16         // bitRateCode = 16 ushr 1 = 8
        val bsid = 8                // plain AC-3 (<= 8)
        val bsmod = 0
        val acmod = 2               // 2/0 stereo
        val lfeon = 0
        val payload = BW()
            .w(0, 16)               // crc1
            .w(fscod, 2)
            .w(frmsizecod, 6)
            .w(bsid, 5)
            .w(bsmod, 3)
            .w(acmod, 3)
            .w(0, 2)                // dsurmod (acmod == 2)
            .w(lfeon, 1)
            .w(0, 8)                // trailing
            .bytes()
        val frame = byteArrayOf(0x0B, 0x77) + payload

        val dac3 = DolbyAudioConfig.buildDac3(frame)
        assertNotNull("dac3 produced", dac3)
        assertEquals("dac3 is 3 bytes", 3, dac3!!.size)

        val br = BR(dac3)
        assertEquals("fscod", fscod, br.r(2))
        assertEquals("bsid", bsid, br.r(5))
        assertEquals("bsmod", bsmod, br.r(3))
        assertEquals("acmod", acmod, br.r(3))
        assertEquals("lfeon", lfeon, br.r(1))
        assertEquals("bit_rate_code", frmsizecod ushr 1, br.r(5))
        assertEquals("reserved", 0, br.r(5))
    }

    @Test
    fun buildDec3_returnsNullWithoutSyncWord() {
        val frame = ByteArray(32) { 0x55 } // no 0x0B77 anywhere
        assertNull(DolbyAudioConfig.buildDec3(frame))
        assertNull(DolbyAudioConfig.buildDac3(frame))
    }

    @Test
    fun buildDec3_rejectsNonEac3Bsid() {
        // bsid = 8 is AC-3, not E-AC-3 — buildDec3 must reject it.
        val payload = BW()
            .w(0, 2).w(0, 3).w(100, 11).w(0, 2).w(3, 2).w(7, 3).w(1, 1).w(8, 5).w(0, 16)
            .bytes()
        val frame = byteArrayOf(0x0B, 0x77) + payload
        assertNull(DolbyAudioConfig.buildDec3(frame))
    }
}
