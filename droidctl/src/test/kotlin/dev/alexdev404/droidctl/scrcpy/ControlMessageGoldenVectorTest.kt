package dev.alexdev404.droidctl.scrcpy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden vectors for every control message DroidCtl can send.
 *
 * Each expected vector is hand-computed from the pinned server's
 * `ControlMessageReader.java` (field order and widths), `Binary.java`
 * (fixed-point encodings) and `ControlMessage.java` (type ids), *not* from the
 * implementation under test. A test that just re-ran the encoder would confirm
 * nothing.
 */
class ControlMessageGoldenVectorTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `touch down encodes correctly`() {
        val message = ControlMessage.InjectTouch(
            action = ScrcpyProtocol.MOTION_ACTION_DOWN,
            pointerId = 0L,
            x = 100,
            y = 200,
            screenWidth = 1080,
            screenHeight = 2400,
            pressure = 1.0f,
            actionButton = 0,
            buttons = 0,
        )
        assertArrayEquals(
            bytes(
                0x02,                                           // TYPE_INJECT_TOUCH_EVENT
                0x00,                                           // ACTION_DOWN
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // pointerId = 0
                0x00, 0x00, 0x00, 0x64,                         // x = 100
                0x00, 0x00, 0x00, 0xC8,                         // y = 200
                0x04, 0x38,                                     // screenWidth = 1080
                0x09, 0x60,                                     // screenHeight = 2400
                0xFF, 0xFF,                                     // pressure = 1.0
                0x00, 0x00, 0x00, 0x00,                         // actionButton
                0x00, 0x00, 0x00, 0x00,                         // buttons
            ),
            message.serialize(),
        )
    }

    @Test
    fun `touch up encodes zero pressure and a non-zero pointer id`() {
        val message = ControlMessage.InjectTouch(
            action = ScrcpyProtocol.MOTION_ACTION_UP,
            pointerId = 0x0102030405060708L,
            x = -1,
            y = 0,
            screenWidth = 1,
            screenHeight = 65535,
            pressure = 0.0f,
        )
        assertArrayEquals(
            bytes(
                0x02,
                0x01,                                           // ACTION_UP
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, // pointerId
                0xFF, 0xFF, 0xFF, 0xFF,                         // x = -1 (signed int32)
                0x00, 0x00, 0x00, 0x00,                         // y = 0
                0x00, 0x01,                                     // screenWidth = 1
                0xFF, 0xFF,                                     // screenHeight = 65535
                0x00, 0x00,                                     // pressure = 0.0
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
            ),
            message.serialize(),
        )
    }

    @Test
    fun `pressure is 16-bit fixed point with a saturating maximum`() {
        // Binary.u16FixedPointToFloat: 0xFFFF decodes to exactly 1.0, and
        // 0.5 * 2^16 is 0x8000.
        assertEquals(0x0000, ScrcpyProtocol.floatToU16FixedPoint(0.0f))
        assertEquals(0x8000, ScrcpyProtocol.floatToU16FixedPoint(0.5f))
        assertEquals(0xFFFF, ScrcpyProtocol.floatToU16FixedPoint(1.0f))
    }

    @Test
    fun `keycode encodes correctly`() {
        val message = ControlMessage.InjectKeycode(
            action = ScrcpyProtocol.KEY_ACTION_DOWN,
            keycode = 4, // KEYCODE_BACK
            repeat = 0,
            metaState = 0,
        )
        assertArrayEquals(
            bytes(
                0x00,                   // TYPE_INJECT_KEYCODE
                0x00,                   // ACTION_DOWN
                0x00, 0x00, 0x00, 0x04, // keycode
                0x00, 0x00, 0x00, 0x00, // repeat
                0x00, 0x00, 0x00, 0x00, // metaState
            ),
            message.serialize(),
        )
    }

    @Test
    fun `keycode carries repeat and meta state`() {
        val message = ControlMessage.InjectKeycode(
            action = ScrcpyProtocol.KEY_ACTION_UP,
            keycode = 0x0000_007A,
            repeat = 3,
            metaState = 0x0000_1001,
        )
        assertArrayEquals(
            bytes(
                0x00,
                0x01,
                0x00, 0x00, 0x00, 0x7A,
                0x00, 0x00, 0x00, 0x03,
                0x00, 0x00, 0x10, 0x01,
            ),
            message.serialize(),
        )
    }

    @Test
    fun `text encodes a four-byte length and UTF-8 bytes`() {
        // "he" + U+00E9, which is 0xC3 0xA9 in UTF-8: three bytes, two chars.
        assertArrayEquals(
            bytes(0x01, 0x00, 0x00, 0x00, 0x03, 0x68, 0xC3, 0xA9),
            ControlMessage.InjectText("hé").serialize(),
        )
    }

    @Test
    fun `text is truncated on a code point boundary`() {
        // 299 ASCII bytes plus a two-byte code point is 301 bytes: one over the
        // server's 300-byte limit. Cutting at 300 would split the code point, so
        // StringUtils.getUtf8TruncationIndex backs up to 299.
        val text = "a".repeat(299) + "é"
        val encoded = ControlMessage.InjectText(text).serialize()

        assertEquals(1 + 4 + 299, encoded.size)
        assertArrayEquals(bytes(0x01, 0x00, 0x00, 0x01, 0x2B), encoded.copyOfRange(0, 5))
        assertEquals('a'.code.toByte(), encoded.last())
    }

    @Test
    fun `text exactly at the limit is not truncated`() {
        // 150 two-byte code points is exactly 300 bytes.
        val text = "é".repeat(150)
        val encoded = ControlMessage.InjectText(text).serialize()
        assertEquals(1 + 4 + 300, encoded.size)
        assertArrayEquals(bytes(0x01, 0x00, 0x00, 0x01, 0x2C), encoded.copyOfRange(0, 5))
    }

    @Test
    fun `scroll encodes signed fixed point normalised by sixteen`() {
        val message = ControlMessage.InjectScroll(
            x = 10,
            y = 20,
            screenWidth = 100,
            screenHeight = 200,
            hScroll = 0f,
            vScroll = -16f,
            buttons = 0,
        )
        assertArrayEquals(
            bytes(
                0x03,                   // TYPE_INJECT_SCROLL_EVENT
                0x00, 0x00, 0x00, 0x0A, // x
                0x00, 0x00, 0x00, 0x14, // y
                0x00, 0x64,             // screenWidth
                0x00, 0xC8,             // screenHeight
                0x00, 0x00,             // hscroll = 0
                0x80, 0x00,             // vscroll = -16 -> -1.0 -> -32768
                0x00, 0x00, 0x00, 0x00, // buttons
            ),
            message.serialize(),
        )
    }

    @Test
    fun `scroll saturates at the positive maximum`() {
        val message = ControlMessage.InjectScroll(
            x = 0, y = 0, screenWidth = 0, screenHeight = 0,
            hScroll = 16f, vScroll = 32f, buttons = 0,
        )
        val encoded = message.serialize()
        // +16 normalises to 1.0, which sc_float_to_i16fp saturates to 0x7FFF;
        // +32 is out of range and is clamped to the same value first.
        assertArrayEquals(bytes(0x7F, 0xFF), encoded.copyOfRange(13, 15))
        assertArrayEquals(bytes(0x7F, 0xFF), encoded.copyOfRange(15, 17))
    }

    @Test
    fun `back or screen on encodes an action byte`() {
        assertArrayEquals(bytes(0x04, 0x00), ControlMessage.BackOrScreenOn(0).serialize())
        assertArrayEquals(bytes(0x04, 0x01), ControlMessage.BackOrScreenOn(1).serialize())
    }

    @Test
    fun `set display power encodes a boolean`() {
        assertArrayEquals(bytes(0x0A, 0x00), ControlMessage.SetDisplayPower(false).serialize())
        assertArrayEquals(bytes(0x0A, 0x01), ControlMessage.SetDisplayPower(true).serialize())
    }

    @Test
    fun `empty messages are a single type byte`() {
        assertArrayEquals(bytes(0x05), ControlMessage.Empty.ExpandNotificationPanel.serialize())
        assertArrayEquals(bytes(0x06), ControlMessage.Empty.ExpandSettingsPanel.serialize())
        assertArrayEquals(bytes(0x07), ControlMessage.Empty.CollapsePanels.serialize())
        assertArrayEquals(bytes(0x0B), ControlMessage.Empty.RotateDevice.serialize())
    }
}
