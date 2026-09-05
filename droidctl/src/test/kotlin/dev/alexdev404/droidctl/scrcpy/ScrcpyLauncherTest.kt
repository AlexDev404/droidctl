package dev.alexdev404.droidctl.scrcpy

import dev.alexdev404.droidctl.transport.FakeTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The launch sequence, over a transport that is neither adb nor SSH.
 *
 * That is the point: pushing the jar, deciding whether it needs pushing at all,
 * opening a tunnel and starting `app_process` are the same four steps in both
 * modes, and this pins them without a device on either end.
 */
class ScrcpyLauncherTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val digest = "a".repeat(64)

    private fun launcher(bytes: Int = 4_096): ScrcpyLauncher {
        val jar = temp.newFile("scrcpy-server.jar").apply { writeBytes(ByteArray(bytes)) }
        return ScrcpyLauncher(FakeJar(jar, digest))
    }

    private fun sha256Reply(value: String) =
        mapOf("sha256sum ${ScrcpyOptions.DEVICE_SERVER_PATH} 2>/dev/null" to
            "$value  ${ScrcpyOptions.DEVICE_SERVER_PATH}\n")

    @Test
    fun `pushes the server and reports what the transfer measured`() {
        val transport = FakeTransport()
        val delivery = runBlocking {
            launcher().ensureServerOnTarget(transport, allowSkip = false)
        }.getOrThrow()

        assertTrue(delivery is ServerDelivery.Pushed)
        assertEquals(
            listOf("scrcpy-server.jar" to ScrcpyOptions.DEVICE_SERVER_PATH),
            transport.pushed,
        )
        assertEquals(4_096, (delivery as ServerDelivery.Pushed).measurement.bytes)
        // allowSkip=false must not even ask, or a slow `sha256sum` on the
        // Target would cost time on the path that was always going to push.
        assertTrue(transport.commands.isEmpty())
    }

    @Test
    fun `skips the push when the Target already has this exact jar`() {
        val transport = FakeTransport(responses = sha256Reply(digest))
        val delivery = runBlocking {
            launcher().ensureServerOnTarget(transport, allowSkip = true)
        }.getOrThrow()

        assertEquals(ServerDelivery.AlreadyPresent, delivery)
        assertTrue(transport.pushed.isEmpty())
    }

    @Test
    fun `pushes anyway when the Target's jar is a different build`() {
        // A digest rather than a size, so a jar from another scrcpy version is
        // replaced. A mismatched server aborts at startup with an error that
        // reads like a protocol bug, which is the worst thing to have to debug.
        val transport = FakeTransport(responses = sha256Reply("b".repeat(64)))
        val delivery = runBlocking {
            launcher().ensureServerOnTarget(transport, allowSkip = true)
        }.getOrThrow()

        assertTrue(delivery is ServerDelivery.Pushed)
        assertEquals(1, transport.pushed.size)
    }

    @Test
    fun `pushes when the Target cannot answer the digest question`() {
        // No sha256sum, no such file, a garbled reply: all of them answer "no",
        // so the worst case is the push that would have happened anyway.
        val transport = FakeTransport(
            failingCommands = setOf("sha256sum ${ScrcpyOptions.DEVICE_SERVER_PATH} 2>/dev/null"),
        )
        val delivery = runBlocking {
            launcher().ensureServerOnTarget(transport, allowSkip = true)
        }.getOrThrow()

        assertTrue(delivery is ServerDelivery.Pushed)
    }

    @Test
    fun `launch opens a tunnel and runs app_process against it`() {
        val transport = FakeTransport()
        val options = ScrcpyOptions(scid = 0x0a1b2c3d, maxSize = 1080, videoBitRate = 2_000_000)

        val handle = runBlocking { launcher().launch(transport, options) }.getOrThrow()

        assertEquals(FakeTransport.TUNNEL_PORT, handle.hostPort)
        assertEquals(listOf(options.socketName), transport.tunnelsOpened)
        val command = transport.streamed.single()
        assertTrue(command.startsWith("CLASSPATH=${ScrcpyOptions.DEVICE_SERVER_PATH} app_process /"))
        // The version is positional and first; the server aborts if it does not
        // match its own BuildConfig.VERSION_NAME.
        assertTrue(command.contains("com.genymobile.scrcpy.Server ${ScrcpyProtocol.VERSION} "))
    }

    @Test
    fun `a server that will not start does not leak the tunnel`() {
        // The tunnel is opened before the server is launched, so the failure
        // path is the only place it can be released. An adb forward left behind
        // here outlives the app and collides with a later session's port.
        val transport = FakeTransport(streamingFails = true)
        val options = ScrcpyOptions(scid = 0x0a1b2c3d)

        val error = runBlocking { launcher().launch(transport, options) }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertEquals(listOf(FakeTransport.TUNNEL_PORT), transport.tunnelsClosed)
    }

    @Test
    fun `no tunnel means no server process`() {
        // Starting the server with nothing to reach it on would leave an
        // app_process running on the Target that nobody can talk to or stop.
        val transport = FakeTransport(tunnelFails = true)

        val error = runBlocking {
            launcher().launch(transport, ScrcpyOptions(scid = 0x0a1b2c3d))
        }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertTrue(transport.streamed.isEmpty())
        assertFalse(transport.closed)
    }

    private class FakeJar(private val jar: File, private val digest: String) : ServerJar {
        override suspend fun expectedSha256() = digest
        override suspend fun extract() = Result.success(jar)
    }
}
