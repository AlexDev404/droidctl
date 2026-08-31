package dev.alexdev404.droidctl.session

import dev.alexdev404.droidctl.scrcpy.VideoPacket
import dev.alexdev404.droidctl.video.VideoSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandwidthMonitorTest {

    private class Recording : VideoSink {
        val packets = mutableListOf<VideoPacket>()
        var ended = false
        override fun onSizeChanged(width: Int, height: Int) = Unit
        override fun onPacket(packet: VideoPacket) { packets += packet }
        override fun onEndOfStream(cause: Throwable?) { ended = true }
    }

    private var nowMs = 0L
    private val downstream = Recording()
    private val monitor = BandwidthMonitor(downstream, nowNanos = { nowMs * 1_000_000 })

    private fun packet(bytes: Int, ptsUs: Long, config: Boolean = false) = VideoPacket(
        ptsUs = ptsUs,
        isConfig = config,
        isKeyFrame = false,
        payload = ByteArray(bytes),
    )

    @Test
    fun `packets still reach the sink it wraps`() {
        repeat(3) { monitor.onPacket(packet(10, it * 1000L)); nowMs += 33 }
        monitor.onEndOfStream(null)
        assertEquals(3, downstream.packets.size)
        assertTrue(downstream.ended)
    }

    @Test
    fun `says nothing until it has seen enough`() {
        assertNull(monitor.sample())
        repeat(4) { monitor.onPacket(packet(100, it * 33_000L)); nowMs += 33 }
        assertNull("four packets over 100ms is noise, not a measurement", monitor.sample())
    }

    @Test
    fun `a few packets spread over a long span are still a measurement`() {
        // A static screen sends very little; refusing to report on that would
        // blank the debug pane exactly when someone is looking at it.
        repeat(4) { monitor.onPacket(packet(100, it * 1_000_000L)); nowMs += 1_000 }
        val sample = monitor.sample()
        assertTrue("expected a sample over a 3s span", sample != null)
    }

    @Test
    fun `measures throughput over the real elapsed span`() {
        // 10 packets of 1000 bytes, one every 100 ms: 10 KB over 900 ms of span
        // (first to last), which is 88_888 bits per second.
        repeat(10) {
            monitor.onPacket(packet(1000, it * 100_000L))
            if (it < 9) nowMs += 100
        }
        val sample = monitor.sample()!!
        assertEquals(900, sample.spanMs)
        assertEquals(10 * 1000 * 8 * 1000L / 900, sample.throughputBitsPerSecond)
    }

    @Test
    fun `a link that keeps up shows no drift`() {
        // Arrival advances exactly as fast as the Target's timestamps.
        repeat(20) {
            monitor.onPacket(packet(500, it * 33_000L))
            nowMs += 33
        }
        val sample = monitor.sample()!!
        assertEquals(0, sample.driftMs)
        assertEquals(0, sample.driftTrendMs)
    }

    @Test
    fun `a link falling behind shows rising drift`() {
        // The Target emits a frame every 33 ms but they arrive every 50 ms, so
        // the backlog grows by 17 ms per frame. This is the signal that
        // distinguishes a saturated link from a screen where nothing is
        // happening -- throughput cannot tell those apart.
        repeat(20) {
            monitor.onPacket(packet(500, it * 33_000L))
            nowMs += 50
        }
        val sample = monitor.sample()!!
        assertTrue("drift should be climbing, was ${sample.driftMs}", sample.driftMs > 100)
        assertTrue("trend should be positive, was ${sample.driftTrendMs}", sample.driftTrendMs > 0)
    }

    @Test
    fun `an idle screen is not mistaken for a slow link`() {
        // Almost no bytes, but every packet arrives on time: drift stays flat.
        repeat(20) {
            monitor.onPacket(packet(12, it * 1_000_000L))
            nowMs += 1000
        }
        val sample = monitor.sample()!!
        assertTrue("throughput is tiny", sample.throughputBitsPerSecond < 10_000)
        assertEquals("but nothing is behind", 0, sample.driftMs)
        assertEquals(0, sample.driftTrendMs)
    }

    @Test
    fun `only the window is measured`() {
        val short = BandwidthMonitor(Recording(), windowMs = 1_000, nowNanos = { nowMs * 1_000_000 })
        // Old, large packets that must be forgotten.
        repeat(10) { short.onPacket(packet(10_000, it * 10_000L)); nowMs += 10 }
        nowMs += 5_000
        // Recent, small ones.
        repeat(10) { short.onPacket(packet(100, 200_000L + it * 10_000L)); nowMs += 10 }
        val sample = short.sample()!!
        assertEquals(10, sample.packets)
        assertTrue(sample.spanMs <= 1_000)
    }

    @Test
    fun `config packets count towards bytes but not towards drift`() {
        // A config packet has no timestamp of its own; treating its zero PTS as
        // real would read as several seconds of sudden lag.
        repeat(10) { monitor.onPacket(packet(100, it * 33_000L)); nowMs += 33 }
        val before = monitor.sample()!!.driftMs
        monitor.onPacket(packet(500, 0L, config = true))
        nowMs += 33
        val after = monitor.sample()!!
        assertEquals(before, after.driftMs)
        assertEquals(11, after.packets)
    }

    @Test
    fun `reset forgets the baseline as well as the samples`() {
        repeat(10) { monitor.onPacket(packet(100, it * 33_000L)); nowMs += 50 }
        assertTrue(monitor.sample()!!.driftMs > 0)
        monitor.reset()
        assertNull(monitor.sample())
        // A new stream's timestamps start from a different base entirely.
        repeat(10) { monitor.onPacket(packet(100, 9_000_000L + it * 33_000L)); nowMs += 33 }
        assertEquals(0, monitor.sample()!!.driftMs)
    }
}
