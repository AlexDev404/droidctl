package dev.alexdev404.droidctl.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fake server's packetiser, against the stream it actually ships.
 *
 * This is the harness the whole video path is exercised through when no Target
 * is available, so a fault here would masquerade as a fault in the code under
 * test.
 */
class AnnexBPacketizerTest {

    private val sample = File("src/debug/assets/sample-stream.h264")

    @Test
    fun `the shipped sample is packetised one packet per frame`() {
        val packets = AnnexBPacketizer.packetize(sample.readBytes())

        // 90 frames at 30 fps with keyint=30: three IDRs, and one config packet
        // carrying the parameter sets that precede the first of them.
        assertEquals(91, packets.size)
        assertEquals(1, packets.count { it.isConfig })
        assertEquals(90, packets.count { !it.isConfig })
        assertEquals(3, packets.count { it.isKeyFrame })
    }

    @Test
    fun `the config packet comes first and carries no timestamped payload`() {
        val packets = AnnexBPacketizer.packetize(sample.readBytes())
        val config = packets.first()
        assertTrue(config.isConfig)
        assertFalse(config.isKeyFrame)
        // SPS is NAL type 7, and it is the first thing in the stream.
        assertEquals(7, config.data[config.data.indexOfStartCodeEnd()].toInt() and 0x1F)
        assertTrue("the first frame after the config must be a key frame", packets[1].isKeyFrame)
    }

    @Test
    fun `every byte of the stream ends up in exactly one packet`() {
        val bytes = sample.readBytes()
        val packets = AnnexBPacketizer.packetize(bytes)
        // Dropping or duplicating a NAL here would corrupt playback in a way
        // that looks like a decoder bug.
        assertEquals(bytes.size, packets.sumOf { it.data.size })
    }

    @Test
    fun `slices of one frame are recombined into a single packet`() {
        // Two slices of the same frame: the second has first_mb_in_slice != 0,
        // so its top bit is clear and it must not open a new access unit.
        val sliceOne = byteArrayOf(0, 0, 0, 1, 0x65.toByte(), 0x88.toByte(), 0x11, 0x22)
        val sliceTwo = byteArrayOf(0, 0, 0, 1, 0x65.toByte(), 0x08, 0x33, 0x44)
        val nextFrame = byteArrayOf(0, 0, 0, 1, 0x41.toByte(), 0x9A.toByte(), 0x55)

        val packets = AnnexBPacketizer.packetize(sliceOne + sliceTwo + nextFrame)

        assertEquals(2, packets.size)
        assertEquals(sliceOne.size + sliceTwo.size, packets[0].data.size)
        assertTrue(packets[0].isKeyFrame)
        assertEquals(nextFrame.size, packets[1].data.size)
        assertFalse(packets[1].isKeyFrame)
    }

    @Test
    fun `three and four byte start codes are both recognised`() {
        val threeByte = byteArrayOf(0, 0, 1, 0x67.toByte(), 0x42)
        val fourByte = byteArrayOf(0, 0, 0, 1, 0x65.toByte(), 0x88.toByte(), 0x01)
        val nals = AnnexBPacketizer.splitAnnexB(threeByte + fourByte)
        assertEquals(2, nals.size)
        assertEquals(threeByte.size, nals[0].size)
        assertEquals(fourByte.size, nals[1].size)
    }

    /** Index of the first byte after this NAL's start code. */
    private fun ByteArray.indexOfStartCodeEnd(): Int = if (this[2].toInt() == 1) 3 else 4
}
