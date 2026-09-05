package dev.alexdev404.droidctl.scrcpy

import dev.alexdev404.droidctl.DroidCtlLog
import dev.alexdev404.droidctl.transport.DeviceTransport
import dev.alexdev404.droidctl.transport.RemoteProcess
import dev.alexdev404.droidctl.transport.Tunnel
import java.io.IOException

/**
 * A scrcpy server running on the Target, plus the tunnel that reaches it.
 */
class ScrcpyServerHandle(
    val hostPort: Int,
    val process: RemoteProcess,
    val tunnel: Tunnel,
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
 * link finishes so quickly that the transport's own overhead dominates it.
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
 * The launch sequence mirrors the reference client (`app/src/server.c`): push
 * the jar, open a tunnel onto the Target's abstract socket, then run
 * `app_process` with the classpath pointing at the jar. How each of those three
 * is carried out belongs to the [DeviceTransport], which is what lets the same
 * sequence run over adb or over SSH.
 */
class ScrcpyLauncher(private val asset: ServerJar) {

    private val log = DroidCtlLog.server

    /**
     * Makes sure the Target has the right server jar.
     *
     * Pushed every session by default, because the push is the only sizeable
     * transfer before the video stream exists and so the only thing Automatic
     * can measure the link with. Re-sending three quarters of a megabyte costs
     * nearly half a minute at 256 kbps, so [allowSkip] lets the user trade that
     * measurement for a much faster connect: the Target's copy is checksummed
     * and left alone when it matches. Comparing digests rather than sizes means
     * a jar from a different scrcpy version is still replaced; a mismatched
     * server aborts at startup with an error that reads like a protocol bug.
     *
     * Separate from [launch] because the quality rung is decided between the
     * two: a push is what measures the link, and `max_size` and `video_bit_rate`
     * are both fixed the moment the server starts.
     */
    suspend fun ensureServerOnTarget(
        transport: DeviceTransport,
        allowSkip: Boolean,
    ): Result<ServerDelivery> {
        val jar = asset.extract().getOrElse { return Result.failure(it) }

        if (allowSkip && targetHasServer(transport, asset.expectedSha256())) {
            log.i("The Target already has this scrcpy server; skipping the push")
            return Result.success(ServerDelivery.AlreadyPresent)
        }

        val startedAt = System.nanoTime()
        transport.pushFile(jar, ScrcpyOptions.DEVICE_SERVER_PATH)
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
    private suspend fun targetHasServer(
        transport: DeviceTransport,
        expectedSha256: String,
    ): Boolean {
        val output = transport
            .exec("sha256sum ${ScrcpyOptions.DEVICE_SERVER_PATH} 2>/dev/null")
            .getOrElse { return false }
        val digest = output.trim().substringBefore(' ').lowercase()
        return digest.isNotEmpty() && digest == expectedSha256
    }

    /**
     * Opens the tunnel and starts `app_process`.
     *
     * Expects [ensureServerOnTarget] to have run already.
     */
    suspend fun launch(
        transport: DeviceTransport,
        options: ScrcpyOptions,
    ): Result<ScrcpyServerHandle> {
        val scoped = log.withScid(options.socketName)

        val tunnel = transport.openTunnel(options.socketName)
            .getOrElse { return Result.failure(it) }

        val command = buildShellCommand(options)
        scoped.i("Starting scrcpy ${ScrcpyProtocol.VERSION} via ${transport.description}")
        scoped.d("app_process command: $command")

        val process = transport.execStreaming(command).getOrElse { error ->
            // Do not leak the tunnel we just opened.
            runCatching { transport.closeTunnel(tunnel) }
            return Result.failure(
                IOException("Could not start the scrcpy server on the Target", error)
            )
        }

        return Result.success(ScrcpyServerHandle(tunnel.hostPort, process, tunnel))
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
}
