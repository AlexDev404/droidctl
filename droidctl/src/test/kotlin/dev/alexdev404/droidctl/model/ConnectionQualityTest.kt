package dev.alexdev404.droidctl.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionQualityTest {

    @Test
    fun `a rung's max_size is a fraction of the Target's longer side`() {
        // 1080x2400 phone: the longer side is what scrcpy's max_size caps.
        assertEquals(600, ConnectionQuality.Lowest.maxSizeFor(2400))   // 25%
        assertEquals(840, ConnectionQuality.Low.maxSizeFor(2400))      // 35%
        assertEquals(1200, ConnectionQuality.Medium.maxSizeFor(2400))  // 50%
        assertEquals(1680, ConnectionQuality.High.maxSizeFor(2400))    // 70%
        assertEquals(2040, ConnectionQuality.Higher.maxSizeFor(2400))  // 85%
    }

    @Test
    fun `full resolution asks for no cap at all`() {
        // Passing a max_size equal to the Target's own size would still make the
        // server scale; 0 means "send it as it is".
        assertEquals(ConnectionQuality.NO_LIMIT, ConnectionQuality.Full.maxSizeFor(2400))
        assertEquals(ConnectionQuality.NO_LIMIT, ConnectionQuality.Full.maxSizeFor(null))
    }

    @Test
    fun `every max_size is a multiple of eight`() {
        for (side in listOf(1280, 1920, 2280, 2400, 2560, 3120)) {
            for (quality in ConnectionQuality.entries) {
                val size = quality.maxSizeFor(side)
                if (size != ConnectionQuality.NO_LIMIT) {
                    assertEquals("$quality at $side must align", 0, size % 8)
                }
            }
        }
    }

    @Test
    fun `an unknown Target size falls back rather than producing zero`() {
        // Zero would mean "uncapped", which is the opposite of what the lowest
        // rung is asking for.
        val size = ConnectionQuality.Lowest.maxSizeFor(null)
        assertTrue(size > 0)
        assertEquals(
            ConnectionQuality.Lowest.maxSizeFor(ConnectionQuality.FALLBACK_LONGER_SIDE),
            size,
        )
    }

    @Test
    fun `a tiny Target still gets a usable size`() {
        // 25% of a small screen would round to something no encoder will accept.
        assertTrue(ConnectionQuality.Lowest.maxSizeFor(480) >= 320)
    }

    @Test
    fun `measured bandwidth picks the best rung that fits with headroom`() {
        // Each rung needs its bit rate to fit inside HEADROOM of the measurement,
        // so 1 Mbps of link does not get you the 1 Mbps rung.
        assertEquals(ConnectionQuality.Lowest, ConnectionQuality.forMeasuredBandwidth(500_000))
        assertEquals(ConnectionQuality.Low, ConnectionQuality.forMeasuredBandwidth(1_000_000))
        assertEquals(ConnectionQuality.Medium, ConnectionQuality.forMeasuredBandwidth(2_000_000))
        assertEquals(ConnectionQuality.High, ConnectionQuality.forMeasuredBandwidth(5_000_000))
        assertEquals(ConnectionQuality.Higher, ConnectionQuality.forMeasuredBandwidth(10_000_000))
        assertEquals(ConnectionQuality.Full, ConnectionQuality.forMeasuredBandwidth(50_000_000))
    }

    @Test
    fun `a hopeless link still gets the lowest rung rather than nothing`() {
        assertEquals(ConnectionQuality.Lowest, ConnectionQuality.forMeasuredBandwidth(1_000))
        assertEquals(ConnectionQuality.Lowest, ConnectionQuality.forMeasuredBandwidth(0))
    }

    @Test
    fun `the chosen rung never asks for more than the link measured`() {
        for (kbps in listOf(64L, 256L, 700L, 1_500L, 3_000L, 6_000L, 12_000L, 40_000L)) {
            val bps = kbps * 1000
            val chosen = ConnectionQuality.forMeasuredBandwidth(bps)
            if (chosen != ConnectionQuality.entries.first()) {
                assertTrue(
                    "$chosen asks for ${chosen.bitRate} on a $kbps kbps link",
                    chosen.bitRate <= bps * ConnectionQuality.HEADROOM,
                )
            }
        }
    }

    @Test
    fun `bandwidth selection is monotonic`() {
        // More bandwidth must never select a lower rung.
        var previous = ConnectionQuality.forMeasuredBandwidth(0)
        var bps = 100_000L
        while (bps < 100_000_000L) {
            val current = ConnectionQuality.forMeasuredBandwidth(bps)
            assertTrue(
                "$bps bps chose $current after $previous",
                current.ordinal >= previous.ordinal,
            )
            previous = current
            bps = (bps * 1.2).toLong()
        }
    }

    @Test
    fun `every rung is labelled with both what it costs and what it gives up`() {
        for (quality in ConnectionQuality.entries) {
            assertTrue("${quality.name} should name a bit rate", quality.label.contains("bps"))
            assertTrue("${quality.name} should name a resolution", quality.label.contains("resolution"))
        }
    }
}

class QualityModeTest {

    @Test
    fun `modes round-trip through storage`() {
        for (mode in QualityMode.all()) {
            assertEquals(mode, QualityMode.decode(mode.encode()))
        }
    }

    @Test
    fun `unset and unrecognised storage both mean Automatic`() {
        // A rung removed in a later version must not leave the app unable to start.
        assertEquals(QualityMode.Automatic, QualityMode.decode(null))
        assertEquals(QualityMode.Automatic, QualityMode.decode("SomeRungThatNoLongerExists"))
    }

    @Test
    fun `the picker offers Automatic first and the best rung next`() {
        val all = QualityMode.all()
        assertEquals(QualityMode.Automatic, all.first())
        assertEquals(QualityMode.Fixed(ConnectionQuality.Full), all[1])
        assertEquals(ConnectionQuality.entries.size + 1, all.size)
    }

    @Test
    fun `only Automatic measures`() {
        assertTrue(QualityMode.Automatic.isAutomatic)
        assertNotNull(QualityMode.Fixed(ConnectionQuality.Low))
        assertTrue(!QualityMode.Fixed(ConnectionQuality.Low).isAutomatic)
    }
}
