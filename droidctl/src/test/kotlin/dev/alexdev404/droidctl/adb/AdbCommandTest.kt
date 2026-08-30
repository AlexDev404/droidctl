package dev.alexdev404.droidctl.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbCommandTest {

    private val binary = AdbBinary(path = "/system/xbin/adb", isAdbNdkWrapper = true)

    @Test
    fun `quoting survives a shell metacharacter`() {
        assertEquals("'a b'", AdbCommand.quote("a b"))
        assertEquals("'a;rm -rf /'", AdbCommand.quote("a;rm -rf /"))
        // A single quote inside is closed, escaped and reopened.
        assertEquals("'it'\\''s'", AdbCommand.quote("it's"))
    }

    @Test
    fun `the shell line exports HOME and TMPDIR`() {
        val line = AdbCommand.of("devices", "-l").toShellLine(binary)
        // Without a writable HOME, adb regenerates its key every run and the
        // Target re-prompts for authorization every session.
        assertTrue(line.startsWith("HOME='${AdbBinary.DEFAULT_HOME}' TMPDIR='${AdbBinary.DEFAULT_TMP}'"))
        assertTrue(line.endsWith("'/system/xbin/adb' 'devices' '-l'"))
    }

    @Test
    fun `a secret argument never appears in the rendered command`() {
        val command = AdbCommand.of("pair", "192.168.1.42:41234", "123456").redactingArg(2)
        assertFalse(command.toString().contains("123456"))
        assertEquals("adb pair 192.168.1.42:41234 <redacted>", command.toString())
        // ...but it is still passed to adb itself.
        assertTrue(command.toShellLine(binary).contains("'123456'"))
    }

    @Test
    fun `a failed result prefers stderr for diagnostics`() {
        val result = AdbResult(
            command = AdbCommand.of("connect", "x"),
            exitCode = 1,
            stdout = listOf("some noise"),
            stderr = listOf("failed to connect to 'x': Connection refused"),
        )
        assertTrue(result.diagnosticText.contains("Connection refused"))
        assertTrue(result.asException("Could not connect").message!!.contains("exit 1"))
    }

    @Test
    fun `a failed result falls back to stdout when stderr is empty`() {
        // `adb connect` reports its failures on stdout, exit code 1.
        val result = AdbResult(
            command = AdbCommand.of("connect", "x"),
            exitCode = 1,
            stdout = listOf("failed to connect to 'x'"),
            stderr = emptyList(),
        )
        assertEquals("failed to connect to 'x'", result.diagnosticText)
    }
}
