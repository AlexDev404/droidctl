package dev.alexdev404.droidctl.transport

import dev.alexdev404.droidctl.model.TransportKind
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.IOException

/**
 * A [DeviceTransport] that records what was asked of it.
 *
 * The whole point of the transport seam is that everything above it -- the
 * launcher, the session, the video path -- is identical over adb and over SSH.
 * That is only worth claiming if it can be exercised without either, which is
 * what this is for.
 */
class FakeTransport(
    /** stdout for each command, by the command line itself. */
    private val responses: Map<String, String> = emptyMap(),
    private val failingCommands: Set<String> = emptySet(),
    private val tunnelFails: Boolean = false,
    private val streamingFails: Boolean = false,
) : DeviceTransport {

    override val kind = TransportKind.Adb
    override val description = "fake"

    val pushed = mutableListOf<Pair<String, String>>()
    val commands = mutableListOf<String>()
    val streamed = mutableListOf<String>()
    val tunnelsOpened = mutableListOf<String>()
    val tunnelsClosed = mutableListOf<Int>()
    var closed = false
        private set

    /** Set when a command should behave as though the Target is missing it. */
    override suspend fun pushFile(local: File, remotePath: String): Result<Unit> {
        pushed += local.name to remotePath
        return Result.success(Unit)
    }

    override suspend fun exec(command: String): Result<String> {
        commands += command
        if (command in failingCommands) {
            return Result.failure(IOException("`$command` failed"))
        }
        return Result.success(responses[command] ?: "")
    }

    override suspend fun execStreaming(command: String): Result<RemoteProcess> {
        streamed += command
        if (streamingFails) return Result.failure(IOException("could not start `$command`"))
        return Result.success(FakeProcess())
    }

    override suspend fun openTunnel(abstractSocketName: String): Result<Tunnel> {
        tunnelsOpened += abstractSocketName
        if (tunnelFails) return Result.failure(IOException("no tunnel"))
        return Result.success(FakeTunnel(TUNNEL_PORT))
    }

    override suspend fun closeTunnel(tunnel: Tunnel) {
        tunnelsClosed += tunnel.hostPort
    }

    override fun close() {
        closed = true
    }

    private class FakeTunnel(override val hostPort: Int) : Tunnel

    class FakeProcess : RemoteProcess {
        private val lines = MutableSharedFlow<ProcessLine>(replay = 16)
        override val output: SharedFlow<ProcessLine> = lines.asSharedFlow()
        override fun snapshot(): List<ProcessLine> = lines.replayCache
        var closed = false
            private set

        override suspend fun close() {
            closed = true
        }
    }

    companion object {
        const val TUNNEL_PORT = 41234
    }
}
