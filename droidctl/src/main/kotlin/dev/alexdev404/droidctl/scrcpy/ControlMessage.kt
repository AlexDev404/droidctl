package dev.alexdev404.droidctl.scrcpy

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Wire format of the scrcpy control channel.
 *
 * Every constant and field width here was read out of the pinned server
 * sources in this repository (scrcpy [ScrcpyProtocol.VERSION]):
 *
 *  - `server/src/main/java/com/genymobile/scrcpy/control/ControlMessage.java`
 *    for the message type ids;
 *  - `server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java`
 *    for the field order and widths (it is the authoritative reader);
 *  - `server/src/main/java/com/genymobile/scrcpy/util/Binary.java` for the
 *    fixed-point encodings;
 *  - `app/src/control_msg.c` for the reference client's byte layout.
 *
 * See `docs/PROTOCOL.md` for where this deviates from the spec's reference
 * tables. All multi-byte fields are big-endian.
 */
object ScrcpyProtocol {

    /**
     * The pinned scrcpy version. The server aborts unless the client announces
     * exactly this string, so it is checked against `:server`'s versionName at
     * build time by `:droidctl:checkScrcpyVersionPin`.
     */
    const val VERSION = dev.alexdev404.droidctl.BuildConfig.SCRCPY_VERSION

    // --- Control message types (ControlMessage.java) ---
    const val TYPE_INJECT_KEYCODE = 0
    const val TYPE_INJECT_TEXT = 1
    const val TYPE_INJECT_TOUCH_EVENT = 2
    const val TYPE_INJECT_SCROLL_EVENT = 3
    const val TYPE_BACK_OR_SCREEN_ON = 4
    const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
    const val TYPE_EXPAND_SETTINGS_PANEL = 6
    const val TYPE_COLLAPSE_PANELS = 7
    const val TYPE_SET_DISPLAY_POWER = 10
    const val TYPE_ROTATE_DEVICE = 11

    /** `ControlMessageReader.INJECT_TEXT_MAX_LENGTH`, in UTF-8 bytes. */
    const val INJECT_TEXT_MAX_LENGTH = 300

    // --- android.view.KeyEvent actions, as the server re-injects them ---
    const val KEY_ACTION_DOWN = 0
    const val KEY_ACTION_UP = 1

    // --- android.view.MotionEvent actions the control channel may carry ---
    //
    // Only DOWN, UP, MOVE and CANCEL are ever sent. Controller.injectTouch()
    // derives ACTION_POINTER_DOWN / ACTION_POINTER_UP itself, from its own
    // pointer table and the index it assigned to our 64-bit pointer id. Sending
    // a pre-ORed ACTION_POINTER_* would be interpreted as an unknown action.
    const val MOTION_ACTION_DOWN = 0
    const val MOTION_ACTION_UP = 1
    const val MOTION_ACTION_MOVE = 2
    const val MOTION_ACTION_CANCEL = 3

    /** Pointer id the server treats as a real mouse (`Controller.POINTER_ID_MOUSE`). */
    const val POINTER_ID_MOUSE = -1L

    /**
     * Encodes a float in [0, 1] as an unsigned 16-bit fixed-point value.
     *
     * Mirrors `sc_float_to_u16fp` / `Binary.u16FixedPointToFloat`: 1.0 must
     * encode as 0xFFFF rather than overflowing to 0.
     */
    fun floatToU16FixedPoint(value: Float): Int {
        require(value in 0f..1f) { "pressure out of range: $value" }
        val scaled = (value * 65536f).toLong()
        return if (scaled >= 0xFFFF) 0xFFFF else scaled.toInt()
    }

    /**
     * Encodes a float in [-1, 1] as a signed 16-bit fixed-point value.
     *
     * Mirrors `sc_float_to_i16fp` / `Binary.i16FixedPointToFloat`.
     */
    fun floatToI16FixedPoint(value: Float): Int {
        require(value in -1f..1f) { "scroll out of range: $value" }
        val scaled = (value * 32768f).toLong()
        return if (scaled >= 0x7FFF) 0x7FFF else scaled.toInt()
    }

    /**
     * Largest prefix of [utf8] not longer than [maxLength] bytes that does not
     * cut a UTF-8 code point in half.
     *
     * Port of `StringUtils.getUtf8TruncationIndex` from the pinned server.
     */
    fun utf8TruncationIndex(utf8: ByteArray, maxLength: Int): Int {
        if (utf8.size <= maxLength) return utf8.size
        var len = maxLength
        while (len > 0 &&
            (utf8[len].toInt() and 0x80) != 0 &&
            (utf8[len].toInt() and 0xc0) != 0xc0
        ) {
            len--
        }
        return len
    }
}

/**
 * A message sent from the Host to the scrcpy server on the Target.
 *
 * The v1 scope covers exactly the messages the UI can produce. Clipboard, UHID
 * and app-launch messages exist in the protocol but are deliberately not
 * modelled here: adding one later means adding one subclass, nothing more.
 */
sealed class ControlMessage {

    /** The exact bytes to write to the control socket. */
    abstract fun serialize(): ByteArray

    /** `INJECT_KEYCODE`: action(1) keycode(4) repeat(4) metaState(4). */
    data class InjectKeycode(
        val action: Int,
        val keycode: Int,
        val repeat: Int = 0,
        val metaState: Int = 0,
    ) : ControlMessage() {
        override fun serialize(): ByteArray = build(14) {
            writeByte(ScrcpyProtocol.TYPE_INJECT_KEYCODE)
            writeByte(action)
            writeInt(keycode)
            writeInt(repeat)
            writeInt(metaState)
        }
    }

    /**
     * `INJECT_TEXT`: length(4) then UTF-8 bytes.
     *
     * Text is truncated to [ScrcpyProtocol.INJECT_TEXT_MAX_LENGTH] bytes on a
     * code point boundary, exactly as the reference client does; the server
     * rejects anything longer.
     */
    data class InjectText(val text: String) : ControlMessage() {
        override fun serialize(): ByteArray {
            val utf8 = text.toByteArray(StandardCharsets.UTF_8)
            val len = ScrcpyProtocol.utf8TruncationIndex(utf8, ScrcpyProtocol.INJECT_TEXT_MAX_LENGTH)
            return build(5 + len) {
                writeByte(ScrcpyProtocol.TYPE_INJECT_TEXT)
                writeInt(len)
                write(utf8, 0, len)
            }
        }
    }

    /**
     * `INJECT_TOUCH_EVENT`: action(1) pointerId(8) position(12) pressure(2)
     * actionButton(4) buttons(4) -- 32 bytes on the wire.
     *
     * [screenWidth]/[screenHeight] are the Target's *current* video dimensions.
     * The server rescales our coordinates by them, so a stale pair after a
     * Target rotation silently lands every tap in the wrong place.
     */
    data class InjectTouch(
        val action: Int,
        val pointerId: Long,
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val pressure: Float,
        val actionButton: Int = 0,
        val buttons: Int = 0,
    ) : ControlMessage() {
        override fun serialize(): ByteArray = build(32) {
            writeByte(ScrcpyProtocol.TYPE_INJECT_TOUCH_EVENT)
            writeByte(action)
            writeLong(pointerId)
            writePosition(x, y, screenWidth, screenHeight)
            writeShort(ScrcpyProtocol.floatToU16FixedPoint(pressure))
            writeInt(actionButton)
            writeInt(buttons)
        }
    }

    /**
     * `INJECT_SCROLL_EVENT`: position(12) hscroll(2) vscroll(2) buttons(4)
     * -- 21 bytes on the wire.
     *
     * Scroll amounts are in [-16, 16]: the reference client normalises by 16
     * before the signed fixed-point encoding, and the server multiplies by 16
     * again after decoding.
     */
    data class InjectScroll(
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val hScroll: Float,
        val vScroll: Float,
        val buttons: Int = 0,
    ) : ControlMessage() {
        override fun serialize(): ByteArray = build(21) {
            writeByte(ScrcpyProtocol.TYPE_INJECT_SCROLL_EVENT)
            writePosition(x, y, screenWidth, screenHeight)
            writeShort(ScrcpyProtocol.floatToI16FixedPoint((hScroll / 16f).coerceIn(-1f, 1f)))
            writeShort(ScrcpyProtocol.floatToI16FixedPoint((vScroll / 16f).coerceIn(-1f, 1f)))
            writeInt(buttons)
        }
    }

    /**
     * `BACK_OR_SCREEN_ON`: action(1).
     *
     * Presses BACK, or turns the Target's screen on if it is off.
     */
    data class BackOrScreenOn(val action: Int) : ControlMessage() {
        override fun serialize(): ByteArray = build(2) {
            writeByte(ScrcpyProtocol.TYPE_BACK_OR_SCREEN_ON)
            writeByte(action)
        }
    }

    /** `SET_DISPLAY_POWER`: on(1). Turns the Target's display off while mirroring. */
    data class SetDisplayPower(val on: Boolean) : ControlMessage() {
        override fun serialize(): ByteArray = build(2) {
            writeByte(ScrcpyProtocol.TYPE_SET_DISPLAY_POWER)
            writeByte(if (on) 1 else 0)
        }
    }

    /** A message with no payload at all: just its type byte. */
    data class Empty(val type: Int) : ControlMessage() {
        override fun serialize(): ByteArray = byteArrayOf(type.toByte())

        companion object {
            val ExpandNotificationPanel = Empty(ScrcpyProtocol.TYPE_EXPAND_NOTIFICATION_PANEL)
            val ExpandSettingsPanel = Empty(ScrcpyProtocol.TYPE_EXPAND_SETTINGS_PANEL)
            val CollapsePanels = Empty(ScrcpyProtocol.TYPE_COLLAPSE_PANELS)
            val RotateDevice = Empty(ScrcpyProtocol.TYPE_ROTATE_DEVICE)
        }
    }

}

/**
 * Builds a message of exactly [size] bytes.
 *
 * The size is asserted rather than trusted: a message that is one byte off
 * desynchronises the server's reader for the rest of the session, and the
 * symptom (input silently stops working some seconds later) is far away from
 * the cause.
 */
private fun build(size: Int, block: DataOutputStream.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream(size)
    DataOutputStream(bytes).use { it.block() }
    val result = bytes.toByteArray()
    check(result.size == size) { "serialized ${result.size} bytes, expected $size" }
    return result
}

/** `position`: x(4) y(4) screenWidth(2) screenHeight(2), big-endian. */
private fun DataOutputStream.writePosition(x: Int, y: Int, width: Int, height: Int) {
    writeInt(x)
    writeInt(y)
    writeShort(width)
    writeShort(height)
}
