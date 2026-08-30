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

        /** Matches the reference client: 100 attempts, 100 ms apart. */
        private const val CONNECT_ATTEMPTS = 100
        private const val CONNECT_RETRY_DELAY_MS = 100L

        /** Generous, but bounded: the handshake must not hang the UI forever. */
        private const val HANDSHAKE_TIMEOUT_MS = 10_000

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
            serverDiagnostics: () -> String,
        ): Result<ScrcpyConnection> = withContext(Dispatchers.IO) {
            val scoped = log.withScid(scid)
            var video: Socket? = null
            var control: Socket? = null
            try {
                video = connectWithRetry(hostPort, scoped, serverDiagnostics)
                    .getOrElse { return@withContext Result.failure(it) }

                // The dummy byte is written by the server as soon as it accepts
                // the first socket. Its arrival is the only proof the tunnel
                // reaches a live server rather than a stale adb forward.
                video.soTimeout = HANDSHAKE_TIMEOUT_MS
                val dummy = video.getInputStream().read()
                if (dummy != 0) {
                    throw IOException(
                        "Expected the scrcpy dummy byte, got $dummy. Server output:\n" +
                            serverDiagnostics()
                    )
                }
                scoped.d("Dummy byte received; the tunnel is live")

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

        private suspend fun connectWithRetry(
            hostPort: Int,
            scoped: DroidCtlLog,
            serverDiagnostics: () -> String,
        ): Result<Socket> {
            var lastError: IOException? = null
            repeat(CONNECT_ATTEMPTS) { attempt ->
                coroutineContext.ensureActive()
                try {
                    return Result.success(
                        Socket().apply {
                            tcpNoDelay = true
                            connect(InetSocketAddress("127.0.0.1", hostPort), HANDSHAKE_TIMEOUT_MS)
                        }
                    )
                } catch (e: IOException) {
                    lastError = e
                    if (attempt == 0 || attempt % 10 == 9) {
                        scoped.d("Waiting for the scrcpy server (attempt ${attempt + 1}/$CONNECT_ATTEMPTS)")
                    }
                    delay(CONNECT_RETRY_DELAY_MS)
                }
            }
            return Result.failure(
                IOException(
                    "The scrcpy server never started listening on 127.0.0.1:$hostPort after " +
                        "${CONNECT_ATTEMPTS * CONNECT_RETRY_DELAY_MS} ms. Server output:\n" +
                        serverDiagnostics().ifBlank { "(the server printed nothing)" },
                    lastError,
                )
            )
        }
    }
}
