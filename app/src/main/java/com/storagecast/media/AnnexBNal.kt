package com.storagecast.media

/** Splits an Annex-B byte stream (3- or 4-byte start codes) into its NAL unit payloads. */
object AnnexBNal {

    fun split(data: ByteArray): List<ByteArray> {
        val codePositions = ArrayList<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                codePositions.add(i)
                i += 3
            } else {
                i++
            }
        }
        val nalus = ArrayList<ByteArray>(codePositions.size)
        for (k in codePositions.indices) {
            val start = codePositions[k] + 3
            val end = if (k + 1 < codePositions.size) codePositions[k + 1] else data.size
            if (end > start) nalus.add(data.copyOfRange(start, end))
        }
        return nalus
    }
}
