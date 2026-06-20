package com.storagecast.media

import android.media.MediaCodec

/**
 * Buffers decoded PCM and feeds it into an AAC encoder without dropping data when a decoded
 * audio buffer is larger than one encoder input buffer.
 */
class HlsAudioEncoderFeeder(
    private val encoder: MediaCodec,
    private val sampleRate: Int,
    private val channels: Int
) {
    private val pcmQueue = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private val bytesPerFrame = channels * 2
    private var basePtsUs = -1L
    private var framesSent = 0L
    private var eosQueued = false

    fun enqueuePcm(data: ByteArray, ptsUs: Long) {
        if (basePtsUs < 0) basePtsUs = ptsUs
        if (data.isNotEmpty()) pcmQueue.addLast(data)
    }

    private fun ptsForNextChunk(): Long {
        val base = if (basePtsUs < 0) 0L else basePtsUs
        return base + framesSent * 1_000_000L / sampleRate
    }

    /** Drains the PCM queue into the encoder; queues EOS once drained if [decoderDone]. */
    fun pump(decoderDone: Boolean) {
        while (true) {
            if (pcmQueue.isEmpty()) {
                if (decoderDone && !eosQueued) {
                    val idx = encoder.dequeueInputBuffer(0)
                    if (idx >= 0) {
                        encoder.queueInputBuffer(
                            idx, 0, 0, ptsForNextChunk(), MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        eosQueued = true
                    }
                }
                return
            }
            val idx = encoder.dequeueInputBuffer(0)
            if (idx < 0) return
            val encBuf = encoder.getInputBuffer(idx)
            if (encBuf == null) {
                encoder.queueInputBuffer(idx, 0, 0, ptsForNextChunk(), 0)
                return
            }
            encBuf.clear()
            val cap = encBuf.remaining()
            val usableCap = cap - (cap % bytesPerFrame)
            val pts = ptsForNextChunk()
            if (usableCap <= 0) {
                encoder.queueInputBuffer(idx, 0, 0, pts, 0)
                return
            }
            var written = 0
            while (written < usableCap && pcmQueue.isNotEmpty()) {
                val head = pcmQueue.first()
                val avail = head.size - headOffset
                val toCopy = minOf(avail, usableCap - written)
                encBuf.put(head, headOffset, toCopy)
                headOffset += toCopy
                written += toCopy
                if (headOffset >= head.size) {
                    pcmQueue.removeFirst()
                    headOffset = 0
                }
            }
            if (written > 0) {
                encoder.queueInputBuffer(idx, 0, written, pts, 0)
                framesSent += (written / bytesPerFrame).toLong()
            } else {
                encoder.queueInputBuffer(idx, 0, 0, pts, 0)
                return
            }
        }
    }
}