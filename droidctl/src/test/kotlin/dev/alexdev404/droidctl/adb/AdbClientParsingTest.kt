package dev.alexdev404.droidctl.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbClientParsingTest {

    @Test
    fun `parses adb devices -l output`() {
        val devices = AdbClient.parseDevices(
            listOf(
                "List of devices attached",
                "192.168.1.42:37129      device product:raven model:Pixel_6_Pro device:raven transport_id:3",
                "",
            )
        )
        val device = devices.single()
        assertEquals("192.168.1.42:37129", device.serial)
        assertEquals(AdbDeviceState.Device, device.state)
        assertEquals("Pixel_6_Pro", device.model)
        assertEquals("Pixel 6 Pro", device.displayName)
        assertEquals("3", device.transportId)
        assertNull("a ready device needs no remediation", device.remediation)
    }

    @Test
    fun `distinguishes unauthorized, offline and authorizing`() {
        val devices = AdbClient.parseDevices(
            listOf(
                "List of devices attached",
                "192.168.1.42:37129      unauthorized",
                "192.168.1.43:37130      offline",
                "192.168.1.44:37131      authorizing",
            )
        )
        assertEquals(
            listOf(AdbDeviceState.Unauthorized, AdbDeviceState.Offline, AdbDeviceState.Authorizing),
            devices.map { it.state },
        )
        // Each state needs a different action from the user, so each must carry
        // its own remediation rather than a shared "not connected".
        assertEquals(3, devices.mapNotNull { it.remediation }.distinct().size)
        assertTrue(devices.none { it.state.isUsable })
    }

    @Test
    fun `ignores daemon chatter and the header`() {
        val devices = AdbClient.parseDevices(
            listOf(
                "* daemon not running; starting now at tcp:5037",
                "* daemon started successfully",
                "List of devices attached",
                "192.168.1.42:37129      device",
            )
        )
        assertEquals(1, devices.size)
    }

    @Test
    fun `an unrecognised state is surfaced rather than dropped`() {
        val device = AdbClient.parseDevices(listOf("serial   something-new")).single()
        assertEquals(AdbDeviceState.Unknown, device.state)
        assertFalse(device.state.isUsable)
        assertNotNull(device.remediation)
    }

    @Test
    fun `reads the Target's physical display size`() {
        val size = AdbClient.parseDisplaySize(listOf("Physical size: 1080x2400"))
        assertEquals(DisplaySize(1080, 2400), size)
        assertEquals(2400, size!!.longerSide)
    }

    @Test
    fun `an override size wins over the physical one`() {
        // The override is the resolution the Target is actually running at, so
        // it is what scrcpy captures and what a quality rung is a fraction of.
        val size = AdbClient.parseDisplaySize(
            listOf("Physical size: 1440x3120", "Override size: 1080x2340")
        )
        assertEquals(DisplaySize(1080, 2340), size)
    }

    @Test
    fun `a landscape Target reports its longer side correctly`() {
        val size = AdbClient.parseDisplaySize(listOf("Physical size: 2560x1600"))!!
        assertEquals(2560, size.longerSide)
    }

    @Test
    fun `unparseable wm size output is reported as unknown`() {
        assertNull(AdbClient.parseDisplaySize(listOf("")))
        assertNull(AdbClient.parseDisplaySize(listOf("Physical size: unknown")))
        assertNull(AdbClient.parseDisplaySize(listOf("Physical size: 0x0")))
    }

    @Test
    fun `the pairing code is stripped from adb's own output`() {
        // adb echoes the code back in some failure messages; it must not reach
        // a log line, an error message or the debug pane.
        val redacted = AdbClient.redactCode("failed to pair with code 123456", "123456")
        assertFalse(redacted.contains("123456"))
        assertTrue(redacted.contains("<redacted>"))
    }
}
