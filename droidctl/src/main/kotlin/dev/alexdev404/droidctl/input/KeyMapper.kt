package dev.alexdev404.droidctl.input

import android.view.KeyEvent
import dev.alexdev404.droidctl.scrcpy.ControlMessage
import dev.alexdev404.droidctl.scrcpy.ScrcpyProtocol

/**
 * Maps Host key events and overlay buttons to scrcpy control messages.
 *
 * Android keycodes and meta-state bits are the same namespace on both devices,
 * so forwarding is mostly a matter of deciding *what* to forward. Arbitrary
 * text is not forwarded key by key: an IME produces characters that have no
 * keycode at all, so text goes through `INJECT_TEXT` instead (see
 * `MirrorScreen`'s hidden text field).
 */
object KeyMapper {

    /** Keys the Host forwards to the Target when its own hardware keys are pressed. */
    private val FORWARDED_KEYCODES = intArrayOf(
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_DEL,
        KeyEvent.KEYCODE_FORWARD_DEL,
        KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_ESCAPE,
    )

    /**
     * Converts a Host [KeyEvent], or returns null when the key is not forwarded.
     *
     * The Host's own BACK key is *not* forwarded here: it is how the user leaves
     * the mirror screen. The on-screen BACK button sends [back] instead.
     */
    fun fromHostKeyEvent(event: KeyEvent): ControlMessage? {
        if (event.keyCode !in FORWARDED_KEYCODES) return null
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> ScrcpyProtocol.KEY_ACTION_DOWN
            KeyEvent.ACTION_UP -> ScrcpyProtocol.KEY_ACTION_UP
            else -> return null // ACTION_MULTIPLE carries text, not a key
        }
        return ControlMessage.InjectKeycode(
            action = action,
            keycode = event.keyCode,
            repeat = event.repeatCount,
            metaState = event.metaState,
        )
    }

    /** A complete press (down then up) of [keycode] on the Target. */
    fun press(keycode: Int): List<ControlMessage> = listOf(
        ControlMessage.InjectKeycode(ScrcpyProtocol.KEY_ACTION_DOWN, keycode),
        ControlMessage.InjectKeycode(ScrcpyProtocol.KEY_ACTION_UP, keycode),
    )

    /**
     * BACK, or wake the Target if its screen is off.
     *
     * `BACK_OR_SCREEN_ON` exists precisely because a plain BACK keycode does
     * nothing on a sleeping device.
     */
    fun back(): List<ControlMessage> = listOf(
        ControlMessage.BackOrScreenOn(ScrcpyProtocol.KEY_ACTION_DOWN),
        ControlMessage.BackOrScreenOn(ScrcpyProtocol.KEY_ACTION_UP),
    )

    fun home(): List<ControlMessage> = press(KeyEvent.KEYCODE_HOME)

    fun recents(): List<ControlMessage> = press(KeyEvent.KEYCODE_APP_SWITCH)

    fun power(): List<ControlMessage> = press(KeyEvent.KEYCODE_POWER)

    fun rotate(): List<ControlMessage> = listOf(ControlMessage.Empty.RotateDevice)

    /** Types [text] on the Target through `INJECT_TEXT`. */
    fun text(text: String): List<ControlMessage> =
        if (text.isEmpty()) emptyList() else listOf(ControlMessage.InjectText(text))
}
