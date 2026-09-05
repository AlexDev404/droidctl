package dev.alexdev404.droidctl.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScrcpyOptionsTest {

    /**
     * Every key DroidCtl can emit, checked against the `case` labels in the
     * pinned server's `Options.parse`. The server throws on an unrecognised key,
     * so an invented option is a hard failure at launch, not a no-op.
     */
    private val serverKnownKeys = setOf(
        "scid", "log_level", "video", "audio", "video_codec", "audio_codec", "video_source",
        "audio_source", "audio_dup", "max_size", "min_size_alignment", "video_bit_rate",
        "audio_bit_rate", "max_fps", "angle", "tunnel_forward", "crop", "control", "display_id",
        "show_touches", "stay_awake", "screen_off_timeout", "video_codec_options",
        "audio_codec_options", "video_encoder", "audio_encoder", "power_off_on_close",
        "clipboard_autosync", "downsize_on_error", "cleanup", "power_on", "list_encoders",
        "list_displays", "list_cameras", "list_camera_sizes", "list_apps", "camera_id",
        "camera_size", "camera_facing", "camera_ar", "camera_zoom", "camera_fps",
        "camera_high_speed", "camera_torch", "new_display", "vd_destroy_content",
        "vd_system_decorations", "flex_display", "capture_orientation", "display_ime_policy",
        "keep_active", "ignore_video_encoder_constraints", "send_device_meta", "send_frame_meta",
        "send_dummy_byte", "send_stream_meta", "raw_stream",
    )

    @Test
    fun `every emitted option exists in the pinned server`() {
        val options = ScrcpyOptions(
            scid = 1,
            maxSize = 1080,
            maxFps = 60,
            stayAwake = true,
            showTouches = true,
            powerOn = false,
        )
        for (argument in options.toArguments()) {
            val key = argument.substringBefore('=')
            assertTrue("Options.parse has no case for \"$key\"", key in serverKnownKeys)
        }
    }

    @Test
    fun `optional options are omitted when unset`() {
        val arguments = ScrcpyOptions(scid = 1).toArguments()
        assertFalse(arguments.any { it.startsWith("max_size=") })
        assertFalse(arguments.any { it.startsWith("max_fps=") })
        assertFalse(arguments.any { it.startsWith("stay_awake=") })
        assertFalse(arguments.any { it.startsWith("show_touches=") })
        assertFalse(arguments.any { it.startsWith("power_on=") })
    }

    @Test
    fun `the scid is eight lower-case hex digits`() {
        val options = ScrcpyOptions(scid = 0x0A1B2C3D)
        assertEquals("scid=0a1b2c3d", options.toArguments().first())
        assertEquals("scrcpy_0a1b2c3d", options.socketName)
    }

    @Test
    fun `a small scid keeps its leading zeros`() {
        // DesktopConnection.getSocketName formats with %08x, so the client must
        // pad identically or the two names will not match.
        assertEquals("scrcpy_00000001", ScrcpyOptions(scid = 1).socketName)
    }

    @Test
    fun `generated session ids never set the sign bit`() {
        // Options.parse reads the scid with Integer.parseInt(value, 16), which
        // overflows on a value with bit 31 set and is then rejected as negative.
        val random = Random(1234)
        repeat(1000) {
            val scid = ScrcpyOptions.generateScid(random)
            assertTrue("scid must be non-negative, got $scid", scid >= 0)
            assertEquals(8, "%08x".format(scid).length)
        }
    }

    @Test
    fun `audio is off and control is on`() {
        val arguments = ScrcpyOptions(scid = 1).toArguments()
        assertTrue("audio=false" in arguments)
        assertTrue("control=true" in arguments)
        assertTrue("tunnel_forward=true" in arguments)
        assertTrue("send_dummy_byte=true" in arguments)
        assertTrue("send_device_meta=true" in arguments)
        assertTrue("send_frame_meta=true" in arguments)
        assertTrue("send_stream_meta=true" in arguments)
    }
}
