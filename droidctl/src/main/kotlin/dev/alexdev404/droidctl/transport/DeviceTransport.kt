package dev.alexdev404.droidctl.transport

import dev.alexdev404.droidctl.model.TransportKind
import kotlinx.coroutines.flow.SharedFlow
import java.io.Closeable
import java.io.File

/**
 * One line of output from a command on the Target.
 *
 * Lives here rather than with either transport because it is what
 * [RemoteProcess] deals in, and a server-side stack trace has to read the same
 * whether it arrived over adb or over SSH.
 */
data class ProcessLine(val text: String, val isError: Boolean)

/** A long-lived command running on the Target. */
interface RemoteProcess {
    /** stdout and stderr, interleaved in arrival order. */
    val output: SharedFlow<ProcessLine>

    /** Everything printed so far, oldest first. */
    fun snapshot(): List<ProcessLine>

    /** Terminates it. Idempotent. */
    suspend fun close()
}

/**
 * A route from a Host loopback port to one of the Target's abstract sockets.
 *
 * How that route is built is the single biggest difference between the two
 * transports, and the reason this abstraction exists at all: adb forwards to
 * `localabstract:` natively, while SSH cannot reach the abstract namespace and
 * needs a relay running on the Target to bridge it.
 *
 * Deliberately not [Closeable]: tearing a tunnel down suspends in both
 * transports (an `adb forward --remove` on one, killing the relay on the
 * other), so it is [DeviceTransport.closeTunnel] that ends one. A tunnel that
 * outlives its session is not cosmetic -- an adb forward sits in the adb server
 * until adb restarts and collides with whichever session next picks that port.
 */
interface Tunnel {
    /** The Host port that now reaches the Target's socket. */
    val hostPort: Int
}

/**
 * Everything a mirroring session needs from the Target, independent of how it
 * got there.
 *
 * Deliberately small. These operations are the entire surface adb was being
 * used for during a session, which is why a second transport was a seam rather
 * than a rewrite.
 */
interface DeviceTransport : Closeable {

    val kind: TransportKind

    /** Shown in logs and the debug pane, e.g. `adb 192.168.1.5:5555`. */
    val description: String

    /** Copies [local] to [remotePath] on the Target. */
    suspend fun pushFile(local: File, remotePath: String): Result<Unit>

    /** Runs [command] on the Target and returns its stdout. */
    suspend fun exec(command: String): Result<String>

    /**
     * Starts [command] on the Target and keeps the handle.
     *
     * Used for the `app_process` invocation that runs the scrcpy server, whose
     * stdout and stderr are the only place a server-side stack trace appears.
     */
    suspend fun execStreaming(command: String): Result<RemoteProcess>

    /** Makes `localabstract:`[abstractSocketName] reachable on a Host port. */
    suspend fun openTunnel(abstractSocketName: String): Result<Tunnel>

    /** Tears a tunnel down. Tolerates one that is already gone. */
    suspend fun closeTunnel(tunnel: Tunnel)
}
