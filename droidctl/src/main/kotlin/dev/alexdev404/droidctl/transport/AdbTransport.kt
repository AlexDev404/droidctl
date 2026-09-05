package dev.alexdev404.droidctl.transport

import dev.alexdev404.droidctl.DroidCtlLog
import dev.alexdev404.droidctl.adb.AdbClient
import dev.alexdev404.droidctl.data.DroidCtlPreferences
import dev.alexdev404.droidctl.model.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * The original transport: everything through a rooted Host's adb.
 *
 * Requires root on the **Host** (to run adb) and nothing at all on the Target
 * beyond wireless debugging.
 */
class AdbTransport(
    private val adb: AdbClient,
    private val serial: String,
    private val preferences: DroidCtlPreferences,
) : DeviceTransport {

    private val log = DroidCtlLog.adb

    override val kind = TransportKind.Adb

    override val description get() = "adb $serial"

    override suspend fun pushFile(local: File, remotePath: String): Result<Unit> =
        adb.push(serial, local, remotePath)

    override suspend fun exec(command: String): Result<String> = adb.shell(serial, command)

    override suspend fun execStreaming(command: String): Result<RemoteProcess> =
        runCatching { adb.shellStreaming(serial, command) }

    /**
     * `adb forward tcp:<port> localabstract:<name>`.
     *
     * adb speaks the abstract namespace directly, so there is nothing to bridge
     * -- the whole job is picking a free Host port and asking adb for it.
     */
    override suspend fun openTunnel(abstractSocketName: String): Result<Tunnel> {
        var lastError: Throwable? = null
        repeat(PORT_ATTEMPTS) { attempt ->
            val hostPort = allocateHostPort().getOrElse { return Result.failure(it) }
            adb.forward(serial, hostPort, "localabstract:$abstractSocketName")
                .onSuccess {
                    preferences.recordForward(serial, hostPort)
                    return Result.success(AdbTunnel(hostPort))
                }
                .onFailure { error ->
                    lastError = error
                    log.w(
                        "Could not forward tcp:$hostPort (attempt ${attempt + 1}/$PORT_ATTEMPTS); " +
                            "trying another port"
                    )
                }
        }
        return Result.failure(
            IOException("Could not open an adb forward after $PORT_ATTEMPTS attempts", lastError)
        )
    }

    override suspend fun closeTunnel(tunnel: Tunnel) {
        adb.removeForward(serial, tunnel.hostPort)
            .onFailure { log.w("Could not remove forward tcp:${tunnel.hostPort}: ${it.message}") }
        preferences.clearForwardRecord(serial, tunnel.hostPort)
    }

    /** Nothing to release: the adb server outlives any one session by design. */
    override fun close() = Unit

    private class AdbTunnel(override val hostPort: Int) : Tunnel {
        override fun toString() = "adb forward tcp:$hostPort"
    }

    /**
     * Picks a free Host port by binding an ephemeral one and letting it go.
     *
     * Inherently racy, hence the retry above: something else can take the port
     * between the close and the forward.
     */
    private suspend fun allocateHostPort(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        }.recoverCatching { error ->
            throw IOException("Could not allocate a local port for the adb tunnel", error)
        }
    }

    private companion object {
        const val PORT_ATTEMPTS = 3
    }
}
