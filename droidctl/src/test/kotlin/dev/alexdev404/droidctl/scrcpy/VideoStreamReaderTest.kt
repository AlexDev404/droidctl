package dev.alexdev404.droidctl.scrcpy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

/**
 * The reader over synthetic streams, including the two ways the Target can
 * refuse to send video at all.
 */
class VideoStreamReaderTest {

    private fun readerOver(vararg values: Int): VideoStreamReader =
        VideoStreamReader(
            DataInputStream(ByteArrayInputStream(ByteArray(values.size) { values[it].toByte() }))
        )

    @Test
    fun `reads the codec id`() {
        assertEquals(VideoStream.CODEC_ID_H264, readerOver(0x68, 0x32, 0x36, 0x34).readCodecId())
    }

    @Test
    fun `a codec id of zero means the Target disabled the stream`() {
        val error = runCatching { readerOver(0, 0, 0, 0).readCodecId() }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("disabled"))
    }

    @Test
    fun `a codec id of one means a configuration error on the Target`() {
        val error = runCatching { readerOver(0, 0, 0, 1).readCodecId() }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("configuration error"))
    }

    @Test
    fun `reads a session record then a config packet then a frame`() {
        val reader = readerOver(
            // session: 480x960
            0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0xE0, 0x00, 0x00, 0x03, 0xC0,
            // config packet, 3 bytes of payload
            0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03,
            0xAA, 0xBB, 0xCC,
            // key frame at pts 1000, 2 bytes of payload
            0x20, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0xE8, 0x00, 0x00, 0x00, 0x02,
            0x11, 0x22,
        )

        assertEquals(VideoStreamEvent.SizeChanged(480, 960), reader.readEvent())

        val config = reader.readEvent() as VideoStreamEvent.Packet
        assertTrue(config.packet.isConfig)
        assertEquals(0L, config.packet.ptsUs)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), config.packet.payload)

        val frame = reader.readEvent() as VideoStreamEvent.Packet
        assertTrue(frame.packet.isKeyFrame)
        assertEquals(1000L, frame.packet.ptsUs)
        assertArrayEquals(byteArrayOf(0x11, 0x22), frame.packet.payload)

        assertNull("end of stream must be reported as null", reader.readEvent())
    }

    @Test
    fun `a zero-length payload is rejected`() {
        val reader = readerOver(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
        )
        val error = runCatching { reader.readEvent() }.exceptionOrNull()
        assertTrue(error is IOException)
    }

    @Test
    fun `a zero video size is rejected`() {
        val reader = readerOver(
            0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0xC0,
        )
        val error = runCatching { reader.readEvent() }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("Invalid video size"))
    }

    @Test
    fun `a truncated payload is an error not a short packet`() {
        // Header claims four bytes; only two follow. Treating a short read as a
        // complete one is the classic source of corrupted-frame bugs, so this
        // must throw rather than return a two-byte packet.
        val reader = readerOver(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x04,
            0x01, 0x02,
        )
        val error = runCatching { reader.readEvent() }.exceptionOrNull()
        assertTrue(error is IOException)
    }
}
