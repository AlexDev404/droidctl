package dev.alexdev404.droidctl.scrcpy

import dev.alexdev404.droidctl.DroidCtlLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/** What the Target told us about itself during the handshake. */
data class TargetMeta(
    val deviceName: String,
    val codecId: Int,
    val width: Int,
    val height: Int,
) {
    val codecName: String get() = VideoStream.codecName(codecId)
}

/**
 * The two sockets to the scrcpy server, and the handshake over them.
 *
 * Both are ordinary unprivileged [Socket]s to `127.0.0.1`: `adb forward` has
 * already made the Target's abstract socket reachable there. Only the adb calls
 * themselves need root; the data path does not.
 */
class ScrcpyConnection private constructor(
    val videoSocket: Socket,
    val controlSocket: Socket,
    val videoInput: DataInputStream,
    val meta: TargetMeta,
) : Closeable {

    /**
     * Closes the control socket first, then the video socket.
     *
     * That order matches the reference client's teardown: closing control first
     * lets the server's controller thread finish before its encoder loses the
     * socket it is writing to, which keeps a spurious "Broken pipe" out of the
     * server log on every clean disconnect.
     */
    override fun close() {
        runCatching { controlSocket.close() }
        runCatching { videoSocket.close() }
    }

    companion object {
        private val log = DroidCtlLog.proto

        /** `DesktopConnection.DEVICE_NAME_FIELD_LENGTH`. */
        private const val DEVICE_NAME_FIELD_LENGTH = 64

        /** Generous, but bounded: the handshake must not hang the UI forever. */
        private const val HANDSHAKE_TIMEOUT_MS = 30_000

        /**
         * Connects both sockets and reads the handshake.
         *
         * Order matters and is fixed by the server: with `tunnel_forward=true`
         * `DesktopConnection.open` accepts the **video** socket first and the
         * **control** socket second, off a single listening socket. Connecting
         * in the other order silently swaps the two channels.
         *
         * @param serverDiagnostics called when the tunnel never comes up, to
         *   supply the server's own stderr. A bare "connection refused" here is
         *   almost never the real story -- the server aborting on a bad option
         *   or a version mismatch looks exactly the same from the socket.
         */
        suspend fun open(
            hostPort: Int,
            scid: String,
            timeouts: HandshakeTimeouts = HandshakeTimeouts(),
            serverDiagnostics: () -> String,
        ): Result<ScrcpyConnection> = withContext(Dispatchers.IO) {
            val scoped = log.withScid(scid)
            var video: Socket? = null
            var control: Socket? = null
            try {
                video = connectWithRetry(hostPort, scoped, timeouts, serverDiagnostics)
                    .getOrElse { return@withContext Result.failure(it) }

                control = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress("127.0.0.1", hostPort), HANDSHAKE_TIMEOUT_MS)
                }
                scoped.d("Control socket connected")

                val input = DataInputStream(BufferedInputStream(video.getInputStream()))

                val nameBytes = ByteArray(DEVICE_NAME_FIELD_LENGTH)
                input.readFully(nameBytes)
                val deviceName = nameBytes
                    .takeWhile { it.toInt() != 0 }
                    .toByteArray()
                    .toString(StandardCharsets.UTF_8)

                val reader = VideoStreamReader(input)
                val codecId = reader.readCodecId()

                // The first record after the codec id is always the session
                // record carrying the video dimensions (demuxer.c asserts this).
                val first = reader.readEvent()
                    ?: throw IOException("The Target closed the video stream before sending its size")
                val size = first as? VideoStreamEvent.SizeChanged
                    ?: throw IOException("Expected a session record first, got $first")

                // Streaming reads must block indefinitely: a quiet screen can go
                // seconds without a frame, and a read timeout would tear down a
                // perfectly healthy session.
                video.soTimeout = 0

                val meta = TargetMeta(deviceName, codecId, size.width, size.height)
                scoped.i(
                    "Target \"$deviceName\": ${meta.codecName} ${meta.width}x${meta.height}"
                )
                Result.success(ScrcpyConnection(video, control, input, meta))
            } catch (e: Throwable) {
                runCatching { control?.close() }
                runCatching { video?.close() }
                if (e is IOException) Result.failure(e) else throw e
            }
        }

        /**
         * Connects the video socket and reads the dummy byte, retrying both
         * together until the server is up.
         *
         * Connecting and reading have to be one retriable unit. With
         * `adb forward` in place the *connect* always succeeds -- adb is
         * listening on the Host port from the moment the forward is created --
         * and it is only when adb then fails to reach
         * `localabstract:scrcpy_<scid>` on the Target that it closes the
         * connection again. So a connect that "worked" proves nothing, and the
         * end of stream on the next read is the actual "not up yet" signal.
         *
         * The two ways an attempt can fail are **not** equivalent, and treating
         * them alike is what made this unreliable on a slow link:
         *
         *  - **End of stream.** adb could not reach the abstract socket, so the
         *    server has accepted nothing. Retrying is safe, and is the whole
         *    point of the loop.
         *  - **Read timeout.** The connection reached the server, which means it
         *    has *already accepted this socket as its video socket* and written
         *    the dummy byte -- the byte is merely still in flight. Abandoning
         *    the socket burns the server's one and only video accept: the next
         *    attempt is accepted as the *control* socket, which sends no dummy
         *    byte, so it times out too, and once both accepts are spent the
         *    server closes its listener and every later attempt fails. The loop
         *    then blames the server for never listening, which by then is the
         *    one thing that is definitely not true.
         *
         * So a timeout ends the handshake rather than retrying it. The caller
         * tears the session down and starts a new one with a fresh scid, which
         * is the only way back to a server whose accepts are unspent.
         */
        private suspend fun connectWithRetry(
            hostPort: Int,
            scoped: DroidCtlLog,
            timeouts: HandshakeTimeouts,
            serverDiagnostics: () -> String,
        ): Result<Socket> {
            var lastFailure = "no connection was attempted"
            var attempt = 0
            val startedAt = System.nanoTime()

            fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000

            while (elapsedMs() < timeouts.serverStartupBudgetMs) {
                coroutineContext.ensureActive()
                attempt++
                val socket = Socket()
                try {
                    socket.tcpNoDelay = true
                    socket.connect(
                        InetSocketAddress("127.0.0.1", hostPort),
                        timeouts.connectTimeoutMs,
                    )
                    socket.soTimeout = timeouts.dummyByteTimeoutMs

                    when (val dummy = socket.getInputStream().read()) {
                        0 -> {
                            scoped.d("Dummy byte received on attempt $attempt; the tunnel is live")
                            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                            return Result.success(socket)
                        }

                        -1 ->
                            // The server is not listening yet. Safe to retry.
                            lastFailure = "the adb tunnel accepted the connection and closed it " +
                                "immediately; nothing is listening on localabstract yet"

                        else -> {
                            runCatching { socket.close() }
                            return Result.failure(
                                IOException(
                                    "Expected the scrcpy dummy byte 0x00 on 127.0.0.1:$hostPort, " +
                                        "got 0x%02x. Something other than the scrcpy server is on ".format(dummy) +
                                        "that port. Server output:\n" +
                                        serverDiagnostics().ifBlank { "(the server printed nothing)" }
                                )
                            )
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    runCatching { socket.close() }
                    return Result.failure(
                        IOException(
                            "Reached the scrcpy server through the adb tunnel, but its first byte " +
                                "did not arrive within ${timeouts.dummyByteTimeoutMs} ms. On a very " +
                                "slow link the byte may still have been in flight. Retrying here " +
                                "would spend the server's video connection and desynchronise the " +
                                "rest of the handshake, so the session is restarted instead. " +
                                "Server output:\n" +
                                serverDiagnostics().ifBlank { "(the server printed nothing)" },
                            e,
                        )
                    )
                } catch (e: IOException) {
                    lastFailure = e.toString()
                }
                runCatching { socket.close() }
                if (attempt == 1 || attempt % 10 == 0) {
                    scoped.d(
                        "Waiting for the scrcpy server (attempt $attempt, " +
                            "${elapsedMs()} of ${timeouts.serverStartupBudgetMs} ms): $lastFailure"
                    )
                }
                delay(timeouts.retryDelayMs)
            }
            return Result.failure(
                IOException(
                    "The scrcpy server never started listening on 127.0.0.1:$hostPort after " +
                        "${timeouts.serverStartupBudgetMs} ms and $attempt attempts. Last failure: " +
                        "$lastFailure. Server output:\n" +
                        serverDiagnostics().ifBlank { "(the server printed nothing)" }
                )
            )
        }
    }
}
