package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Test

class CastQualityTest {
    @Test fun auto_capsAt1080() {
        assertEquals(1920 to 1080, CastQuality.AUTO.maxDimensions())
    }

    @Test fun p720_capsAt720() {
        assertEquals(1280 to 720, CastQuality.P720.maxDimensions())
    }

    @Test fun p540_capsAt540() {
        assertEquals(960 to 540, CastQuality.P540.maxDimensions())
    }

    @Test fun fromPref_mapsKnownValues_defaultsToAuto() {
        assertEquals(CastQuality.AUTO, CastQuality.fromPref("auto"))
        assertEquals(CastQuality.P1080, CastQuality.fromPref("1080"))
        assertEquals(CastQuality.P720, CastQuality.fromPref("720"))
        assertEquals(CastQuality.P540, CastQuality.fromPref("540"))
        assertEquals(CastQuality.AUTO, CastQuality.fromPref(null))
        assertEquals(CastQuality.AUTO, CastQuality.fromPref("garbage"))
    }

    @Test fun autoFallbackRungs_startAt1080_descend() {
        assertEquals(listOf(CastQuality.P1080, CastQuality.P720, CastQuality.P540), CastQuality.autoRungs())
    }
}