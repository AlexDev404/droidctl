package dev.alexdev404.droidctl.scrcpy

import dev.alexdev404.droidctl.DroidCtlLog
import dev.alexdev404.droidctl.adb.AdbClient
import dev.alexdev404.droidctl.adb.RootProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ServerSocket

/**
 * A scrcpy server running on the Target, plus the adb forward that reaches it.
 */
class ScrcpyServerHandle(
    val serial: String,
    val hostPort: Int,
    val process: RootProcess,
) {
    /** Everything the server has printed. The only place its stack traces appear. */
    val output get() = process.output

    /** Everything printed so far, for a failure message. */
    fun snapshot() = process.snapshot()
}

/**
 * What pushing the server jar revealed about the link.
 *
 * The push is the one sizeable transfer that happens before the video stream
 * exists, so it doubles as the bandwidth probe: no extra traffic, and no delay
 * added to a launch that had to push the jar anyway.
 *
 * Two honest caveats, both accounted for by [ConnectionQuality.HEADROOM]:
 * it measures Host to Target while video travels the other way, and a very fast
 * link finishes so quickly that adb's own overhead dominates the number.
 */
data class PushMeasurement(
    val bytes: Long,
    val elapsedMs: Long,
) {
    val bitsPerSecond: Long
        get() = if (elapsedMs <= 0) 0 else bytes * 8 * 1_000 / elapsedMs

    /**
     * False when the transfer was too brief for the timing to mean anything;
     * the link is certainly fast, but the figure itself is mostly overhead.
     */
    val isMeaningful: Boolean get() = elapsedMs >= MIN_MEANINGFUL_MS

    override fun toString(): String =
        "$bytes bytes in ${elapsedMs}ms (${bitsPerSecond / 1000} kbps)"

    companion object {
        const val MIN_MEANINGFUL_MS = 150L
    }
}

/**
 * How the server jar got to the Target.
 */
sealed interface ServerDelivery {

    /** It was transferred, and the transfer was timed. */
    data class Pushed(val measurement: PushMeasurement) : ServerDelivery

    /** The Target already had a byte-identical copy, so nothing was sent. */
    data object AlreadyPresent : ServerDelivery
}

/**
 * Starts the scrcpy server on the Target and sets up the tunnel to it.
 *
 * The launch sequence mirrors the reference client (`app/src/server.c`):
 * push the jar, forward a Host port onto the Target's abstract socket, then
 * run `app_process` with the classpath pointing at the jar.
 */
class ScrcpyLauncher(
    private val adb: AdbClient,
    private val asset: ScrcpyServerAsset,
) {
    private val log = DroidCtlLog.server

    /**
     * Pushes the server, opens the forward and starts `app_process`.
     *
     * The returned handle owns the process; the caller must close it on every
     * teardown path and must remove the forward itself (the launcher does not
     * know when the session is over).
     */
    /**
     * Makes sure the Target has the right server jar, pushing it only if it does
     * not already.
     *
     * Re-sending three quarters of a megabyte at the start of every session is
     * most of the wait on a slow link -- nearly half a minute at 256 kbps, every
     * single time -- so the Target's copy is checksummed first and left alone
     * when it matches. Comparing digests rather than sizes or timestamps means a
     * jar left behind by a different scrcpy version is still replaced; a
     * mismatched server aborts at startup with an error that reads like a
     * protocol bug.
     *
     * Separate from [launch] because the quality rung is decided between the
     * two: a push is what measures the link, and `max_size` and `video_bit_rate`
     * are both fixed the moment the server starts.
     */
    suspend fun ensureServerOnTarget(serial: String): Result<ServerDelivery> {
        val jar = asset.extract().getOrElse { return Result.failure(it) }
        val expected = asset.expectedSha256()

        if (targetHasServer(serial, expected)) {
            log.i("The Target already has this scrcpy server; skipping the push")
            return Result.success(ServerDelivery.AlreadyPresent)
        }

        val startedAt = System.nanoTime()
        adb.push(serial, jar, ScrcpyOptions.DEVICE_SERVER_PATH)
            .getOrElse { return Result.failure(it) }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val measurement = PushMeasurement(jar.length(), elapsedMs)
        log.i("Pushed the scrcpy server: $measurement")
        return Result.success(ServerDelivery.Pushed(measurement))
    }

    /**
     * Whether the Target's copy of the jar already matches [expectedSha256].
     *
     * Any failure here -- no `sha256sum` on the Target, no such file, a garbled
     * reply -- answers "no", so the worst case is the push we would have done
     * anyway.
     */
    private suspend fun targetHasServer(serial: String, expectedSha256: String): Boolean {
        val output = adb.shell(serial, "sha256sum ${ScrcpyOptions.DEVICE_SERVER_PATH} 2>/dev/null")
            .getOrElse { return false }
        val digest = output.trim().substringBefore(' ').lowercase()
        return digest.isNotEmpty() && digest == expectedSha256
    }

    /**
     * Opens the tunnel and starts `app_process`.
     *
     * Expects [pushServer] to have run already.
     */
    suspend fun launch(serial: String, options: ScrcpyOptions): Result<ScrcpyServerHandle> {
        val scoped = log.withScid(options.socketName)

        val hostPort = openForward(serial, options, scoped).getOrElse { return Result.failure(it) }

        val command = buildShellCommand(options)
        scoped.i("Starting scrcpy ${ScrcpyProtocol.VERSION} on $serial via 127.0.0.1:$hostPort")
        scoped.d("app_process command: $command")

        val process = runCatching { adb.shellStreaming(serial, command) }
            .getOrElse { error ->
                // Do not leak the forward we just created.
                adb.removeForward(serial, hostPort)
                return Result.failure(
                    IOException("Could not start the scrcpy server on $serial", error)
                )
            }

        return Result.success(ScrcpyServerHandle(serial, hostPort, process))
    }

    /**
     * Allocates a Host port and forwards it onto the Target's abstract socket.
     *
     * Picking a port by binding an ephemeral one and letting it go is inherently
     * racy: another process -- or a forward this app leaked in a previous run --
     * can take it between the close and the `adb forward`. Retrying with a fresh
     * port is cheaper than trying to hold the port reserved.
     */
    private suspend fun openForward(
        serial: String,
        options: ScrcpyOptions,
        scoped: dev.alexdev404.droidctl.DroidCtlLog,
    ): Result<Int> {
        var lastError: Throwable? = null
        repeat(PORT_ATTEMPTS) { attempt ->
            val hostPort = allocateHostPort().getOrElse { return Result.failure(it) }
            adb.forward(serial, hostPort, "localabstract:${options.socketName}")
                .onSuccess { return Result.success(hostPort) }
                .onFailure { error ->
                    lastError = error
                    scoped.w(
                        "Could not forward tcp:$hostPort (attempt ${attempt + 1}/$PORT_ATTEMPTS); " +
                            "trying another port"
                    )
                }
        }
        return Result.failure(
            IOException("Could not open an adb forward after $PORT_ATTEMPTS attempts", lastError)
        )
    }

    /**
     * The `app_process` command line.
     *
     * `CLASSPATH` must be set in the environment of the command, and the scrcpy
     * version must be the first positional argument: the server compares it to
     * its own `BuildConfig.VERSION_NAME` and aborts on a mismatch. That check is
     * deliberate upstream behaviour and is not worked around here -- a mismatch
     * means the bundled jar and this code disagree about the wire format.
     */
    internal fun buildShellCommand(options: ScrcpyOptions): String = buildString {
        append("CLASSPATH=").append(ScrcpyOptions.DEVICE_SERVER_PATH)
        append(" app_process / com.genymobile.scrcpy.Server ")
        append(ScrcpyProtocol.VERSION)
        for (argument in options.toArguments()) {
            append(' ').append(argument)
        }
    }

    /**
     * Picks a free Host port by binding an ephemeral one and letting it go.
     *
     * Inherently racy -- something else can take the port between the close and
     * the forward -- so the caller retries. Binding on 127.0.0.1 keeps the
     * probe off the network.
     */
    private suspend fun allocateHostPort(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        }.recoverCatching { error ->
            throw IOException("Could not allocate a local port for the adb tunnel", error)
        }
    }

    private companion object {
        const val PORT_ATTEMPTS = 3
    }
}
