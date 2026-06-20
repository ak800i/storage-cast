package com.storagecast.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build

/** Single source of truth that materializes a CommittedEncoderConfig into an AVC encoder. */
object EncoderFormatFactory {
    const val OUTPUT_VIDEO_MIME = "video/avc"

    fun buildAvcEncoderFormat(c: CommittedEncoderConfig): MediaFormat =
        MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME, c.width, c.height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, c.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, c.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, c.iFrameIntervalSec) // 6s GOP (was 1 = all-IDR)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            if (c.profile != CommittedEncoderConfig.UNSET) {
                setInteger(MediaFormat.KEY_PROFILE, c.profile)
                setInteger(MediaFormat.KEY_LEVEL, c.level)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) setInteger(MediaFormat.KEY_LATENCY, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }

    /** Create the encoder by the committed name when present, else by type. */
    fun createCommittedEncoder(c: CommittedEncoderConfig): MediaCodec {
        c.encoderName?.let { name -> runCatching { return MediaCodec.createByCodecName(name) } }
        return MediaCodec.createEncoderByType(OUTPUT_VIDEO_MIME)
    }

    /** Pick a hardware AVC encoder name (so both builders use the same codec implementation). */
    fun pickHardwareAvcEncoderName(): String? {
        val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        return list.codecInfos.firstOrNull { info ->
            info.isEncoder &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || info.isHardwareAccelerated) &&
                info.supportedTypes.any { it.equals(OUTPUT_VIDEO_MIME, ignoreCase = true) }
        }?.name
    }

    /**
     * (AVCProfileHigh, AVCLevel41) — matches the playlist CODECS "avc1.640029" and covers every rung
     * (<=1080p30) — if [encoderName] advertises it; else (UNSET, UNSET) to fall back to encoder
     * defaults. Pinning these makes both builders' avcC identical (the spec invariant).
     */
    fun avcHighL41IfSupported(encoderName: String?): Pair<Int, Int> {
        val high = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        val l41 = MediaCodecInfo.CodecProfileLevel.AVCLevel41
        val unset = CommittedEncoderConfig.UNSET to CommittedEncoderConfig.UNSET
        val name = encoderName ?: return unset
        val info = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            .codecInfos.firstOrNull { it.name == name } ?: return unset
        val ok = runCatching {
            info.getCapabilitiesForType(OUTPUT_VIDEO_MIME).profileLevels
                .any { it.profile == high && it.level >= l41 }
        }.getOrDefault(false)
        return if (ok) high to l41 else unset
    }
}