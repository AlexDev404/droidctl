package dev.alexdev404.droidctl.scrcpy

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * The handshake, over a loopback socket that behaves the way `adb forward` and
 * the scrcpy server actually do.
 *
 * The case that matters is [connects after the tunnel refuses a few times]. With
 * a forward in place, connecting to the Host port *always* succeeds -- adb has
 * been listening there since the forward was created -- and adb only hangs up
 * afterwards, once it finds nothing on `localabstract:scrcpy_<scid>` on the
 * Target. So a client that retries only the connect never retries at all, and
 * fails on the first read with an end of stream, every time, before the server
 * has finished starting.
 */
class ScrcpyConnectionTest {

    private var tunnel: FakeTunnel? = null

    /** Short everywhere, so a test that must wait one out finishes promptly. */
    private val quick = HandshakeTimeouts(
        connectTimeoutMs = 1_000,
        dummyByteTimeoutMs = 400,
        serverStartupBudgetMs = 3_000,
        retryDelayMs = 50,
    )

    @After
    fun tearDown() {
        tunnel?.close()
    }

    @Test
    fun `reads the handshake when the server is already listening`() {
        val fake = FakeTunnel(refusalsBeforeReady = 0).also { tunnel = it; it.start() }

        val connection = runBlocking {
            ScrcpyConnection.open(fake.port, scid = "scrcpy_00000001", timeouts = quick) { "" }
        }.getOrThrow()

        assertEquals("Pixel Test", connection.meta.deviceName)
        assertEquals(VideoStream.CODEC_ID_H264, connection.meta.codecId)
        assertEquals("h264", connection.meta.codecName)
        assertEquals(1080, connection.meta.width)
        assertEquals(2400, connection.meta.height)
        connection.close()
    }

    @Test
    fun `connects after the tunnel refuses a few times`() {
        // Three accept-and-hang-up cycles, exactly what adb does while the
        // server on the Target is still starting up.
        val fake = FakeTunnel(refusalsBeforeReady = 3).also { tunnel = it; it.start() }

        val elapsed = System.nanoTime()
        val connection = runBlocking {
            ScrcpyConnection.open(fake.port, scid = "scrcpy_00000002", timeouts = quick) { "" }
        }.getOrThrow()
        val elapsedMs = (System.nanoTime() - elapsed) / 1_000_000

        assertEquals("Pixel Test", connection.meta.deviceName)
        assertEquals(1080, connection.meta.width)
        // Three retries at the configured delay apart; anything faster means the
        // loop gave up on the first end of stream instead of waiting.
        assertTrue(
            "expected at least three retries, took only ${elapsedMs}ms",
            elapsedMs >= 3 * quick.retryDelayMs,
        )
        assertEquals(4, fake.acceptedVideoConnections)
        connection.close()
    }

    @Test
    fun `a byte that is not the dummy byte fails immediately`() {
        // Something other than scrcpy on the port is not a "not ready yet"
        // signal, so retrying for ten seconds would only delay the report.
        val fake = FakeTunnel(refusalsBeforeReady = 0, firstByte = 0x7F)
            .also { tunnel = it; it.start() }

        val started = System.nanoTime()
        val error = runBlocking {
            ScrcpyConnection.open(
                fake.port,
                scid = "scrcpy_00000003",
                timeouts = quick,
            ) { "some server output" }
        }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(error is java.io.IOException)
        assertTrue(error!!.message!!.contains("0x7f"))
        // Server output is carried into the message: a socket-level symptom is
        // almost never the actual explanation.
        assertTrue(error.message!!.contains("some server output"))
        assertTrue("should not have retried, took ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun `a server that reaches us but is slow to answer is not retried`() {
        // Reaching the server means it has already spent its video accept on
        // this socket. Reconnecting would be handed the *control* accept, which
        // sends no dummy byte, so every later attempt times out too and the
        // failure ends up blaming the server for never listening -- exactly the
        // one thing that is not true by then.
        val fake = FakeTunnel(refusalsBeforeReady = 0, withholdFirstByte = true)
            .also { tunnel = it; it.start() }

        val error = runBlocking {
            ScrcpyConnection.open(fake.port, scid = "scrcpy_00000004", timeouts = quick) {
                "server was still starting"
            }
        }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertTrue(
            "should name the real problem, not blame the server for not listening: ${error!!.message}",
            error.message!!.contains("did not arrive within"),
        )
        assertTrue(error.message!!.contains("server was still starting"))
        assertEquals(
            "must not spend a second connection on a server it already reached",
            1,
            fake.acceptedVideoConnections,
        )
    }

    @Test
    fun `a server that never comes up is reported as never listening`() {
        // Nothing ever accepts on the abstract socket, so every attempt ends at
        // end of stream. That one *is* safe to retry, and the verdict is right.
        val fake = FakeTunnel(refusalsBeforeReady = Int.MAX_VALUE)
            .also { tunnel = it; it.start() }

        val error = runBlocking {
            ScrcpyConnection.open(fake.port, scid = "scrcpy_00000005", timeouts = quick) { "" }
        }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertTrue(error!!.message!!.contains("never started listening"))
        assertTrue("should have retried more than once", fake.acceptedVideoConnections > 1)
    }

    /**
     * Stands in for `adb forward` plus the scrcpy server.
     *
     * For the first [refusalsBeforeReady] connections it accepts and closes
     * straight away, which is what adb does when the Target's abstract socket
     * does not exist yet. After that it plays the server: dummy byte on the
     * video socket, then the control socket, then device name, codec id and the
     * session record carrying the video size.
     */
    private class FakeTunnel(
        private val refusalsBeforeReady: Int,
        private val firstByte: Int = 0,
        /** Accept the video socket but never send its first byte, as a slow link would. */
        private val withholdFirstByte: Boolean = false,
    ) : Closeable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val open = mutableListOf<Socket>()

        @Volatile
        var acceptedVideoConnections = 0
            private set

        val port: Int get() = server.localPort

        private val thread = Thread(::run, "FakeTunnel").apply { isDaemon = true }

        fun start() = thread.start()

        private fun run() {
            try {
                repeat(refusalsBeforeReady) {
                    server.accept().use { acceptedVideoConnections++ }
                }

                val video = server.accept().also { open += it; acceptedVideoConnections++ }
                if (withholdFirstByte) {
                    // Hold the socket open and silent; the client must not treat
                    // this as a reason to open another one.
                    while (!server.isClosed) Thread.sleep(20)
                    return
                }
                video.getOutputStream().apply {
                    write(firstByte)
                    flush()
                }
                if (firstByte != 0) return

                open += server.accept() // the control socket

                val out = DataOutputStream(video.getOutputStream())
                val name = ByteArray(64)
                "Pixel Test".toByteArray(StandardCharsets.UTF_8).copyInto(name)
                out.write(name)
                out.writeInt(VideoStream.CODEC_ID_H264)
                // Session record: the MSB of the first int marks it as one.
                out.writeInt((VideoStream.FLAG_SESSION ushr 32).toInt())
                out.writeInt(1080)
                out.writeInt(2400)
                out.flush()
            } catch (_: Exception) {
                // The socket closing during tearDown is the normal way out.
            }
        }

        override fun close() {
            open.forEach { runCatching { it.close() } }
            runCatching { server.close() }
            runCatching { thread.join(1_000) }
        }
    }
}
