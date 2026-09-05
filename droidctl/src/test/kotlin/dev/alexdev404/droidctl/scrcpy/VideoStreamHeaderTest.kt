package dev.alexdev404.droidctl.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden vectors for the 12-byte video stream header.
 *
 * Computed from the pinned server's `Streamer.java` (which writes them) and the
 * reference client's `demuxer.c` (which documents the layout):
 *
 * ```
 * PACKET_FLAG_SESSION   = 1 << 63
 * PACKET_FLAG_CONFIG    = 1 << 62
 * PACKET_FLAG_KEY_FRAME = 1 << 61
 * ```
 *
 * Note these are *not* the bit positions the implementation spec's reference
 * table gives (config at 63, key frame at 62). See docs/PROTOCOL.md.
 */
class VideoStreamHeaderTest {

    private fun header(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `a session record carries the video size`() {
        val record = VideoStream.parseHeader(
            header(
                0x80, 0x00, 0x00, 0x00, // session flag, client-resized clear
                0x00, 0x00, 0x04, 0x38, // width  = 1080
                0x00, 0x00, 0x09, 0x60, // height = 2400
            )
        )
        assertEquals(StreamRecord.Session(width = 1080, height = 2400, clientResized = false), record)
    }

    @Test
    fun `a session record reports the client-resized flag`() {
        val record = VideoStream.parseHeader(
            header(0x80, 0x00, 0x00, 0x01, 0x00, 0x00, 0x02, 0xD0, 0x00, 0x00, 0x05, 0x00)
        ) as StreamRecord.Session
        assertTrue(record.clientResized)
        assertEquals(720, record.width)
        assertEquals(1280, record.height)
    }

    @Test
    fun `a config packet has no timestamp`() {
        val record = VideoStream.parseHeader(
            header(
                0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // FLAG_CONFIG only
                0x00, 0x00, 0x00, 0x2A,                         // 42 bytes of SPS/PPS
            )
        ) as StreamRecord.Frame
        assertTrue(record.isConfig)
        assertFalse(record.isKeyFrame)
        assertEquals(0L, record.ptsUs)
        assertEquals(42, record.payloadSize)
    }

    @Test
    fun `a key frame keeps its timestamp`() {
        val record = VideoStream.parseHeader(
            header(
                0x20, 0x00, 0x00, 0x00, 0x00, 0x01, 0xE2, 0x40, // FLAG_KEY_FRAME | 123456
                0x00, 0x01, 0x00, 0x00,                         // 65536 bytes
            )
        ) as StreamRecord.Frame
        assertFalse(record.isConfig)
        assertTrue(record.isKeyFrame)
        assertEquals(123_456L, record.ptsUs)
        assertEquals(65_536, record.payloadSize)
    }

    @Test
    fun `a plain media packet has neither flag`() {
        val record = VideoStream.parseHeader(
            header(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01)
        ) as StreamRecord.Frame
        assertFalse(record.isConfig)
        assertFalse(record.isKeyFrame)
        assertEquals(1L, record.ptsUs)
        assertEquals(1, record.payloadSize)
    }

    @Test
    fun `the largest possible timestamp does not spill into the flag bits`() {
        // Every bit below FLAG_KEY_FRAME set: the PTS mask must keep all 61.
        val record = VideoStream.parseHeader(
            header(
                0x1F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
                0x00, 0x00, 0x00, 0x10,
            )
        ) as StreamRecord.Frame
        assertFalse(record.isConfig)
        assertFalse(record.isKeyFrame)
        assertEquals((1L shl 61) - 1, record.ptsUs)
    }

    @Test
    fun `the session flag wins over the other bits`() {
        // 0xE0 has session, config and key-frame bits set. demuxer.c tests the
        // MSB alone, so this must parse as a session record.
        val record = VideoStream.parseHeader(
            header(0xE0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00)
        )
        assertTrue(record is StreamRecord.Session)
    }

    @Test
    fun `payload sizes above two gigabytes are rejected by the reader not the parser`() {
        // The parser is deliberately dumb about the value; VideoStreamReader is
        // what refuses a nonsensical length.
        val record = VideoStream.parseHeader(
            header(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF)
        ) as StreamRecord.Frame
        assertEquals(-1, record.payloadSize)
    }

    @Test
    fun `codec ids match the four-byte ASCII names`() {
        assertEquals(0x68323634, VideoStream.CODEC_ID_H264)
        assertEquals("h264", VideoStream.codecName(VideoStream.CODEC_ID_H264))
        assertEquals("h265", VideoStream.codecName(VideoStream.CODEC_ID_H265))
        assertEquals("0x00000005", VideoStream.codecName(5))
    }
}
