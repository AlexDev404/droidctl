package dev.alexdev404.droidctl.transport

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dev.alexdev404.droidctl.DroidCtlLog
import dev.alexdev404.droidctl.model.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** Where and as whom to log in. */
data class SshCredentials(
    val host: String,
    val port: Int = DEFAULT_PORT,
    /**
     * The account on the Target.
     *
     * `shell` by default, not `root`: uid 2000 is exactly what `adb shell`
     * gives, which is the privilege level scrcpy is built for. The server would
     * demote itself anyway -- `Server.dropRootPrivileges` calls `setuid(2000)`
     * because copy-paste does not work as root.
     */
    val user: String = DEFAULT_USER,
) {
    val address: String get() = "$user@$host:$port"

    companion object {
        const val DEFAULT_PORT = 22
        const val DEFAULT_USER = "shell"
    }
}

/**
 * Reaches the Target over SSH instead of adb.
 *
 * The trade against [AdbTransport] is a straight swap of which device needs
 * root, not a strict improvement:
 *
 * |        | adb                            | SSH                          |
 * |--------|--------------------------------|------------------------------|
 * | Host   | rooted, with the adb-ndk module| **nothing** -- no root       |
 * | Target | stock, nothing installed       | rooted, running an sshd      |
 *
 * Logging in as `shell` lands in the same uid 2000 that `adb shell` provides,
 * so the scrcpy server runs with exactly the privileges it expects.
 *
 * The one thing SSH cannot do that adb can is reach Linux's *abstract* socket
 * namespace, where scrcpy listens. [openTunnel] closes that gap with a relay
 * process on the Target; see `:relay`.
 */
class SshTransport private constructor(
    private val session: Session,
    private val credentials: SshCredentials,
    private val relayJar: File,
    private val scope: CoroutineScope,
) : DeviceTransport {

    private val log = DroidCtlLog.adb

    override val kind = TransportKind.Ssh

    override val description get() = "ssh ${credentials.address}"

    /**
     * The Target's host key, base64, for pinning on later connections.
     *
     * Read after connecting rather than configured before it, because on a
     * first connection there is nothing yet to compare against.
     */
    val hostKeyBase64: String? get() = runCatching { session.hostKey?.key }.getOrNull()

    override suspend fun pushFile(local: File, remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val channel = session.openChannel("sftp") as ChannelSftp
                channel.connect(CHANNEL_TIMEOUT_MS)
                try {
                    channel.put(local.absolutePath, remotePath, ChannelSftp.OVERWRITE)
                } finally {
                    channel.disconnect()
                }
            }.recoverCatching { error ->
                throw IOException("Could not copy ${local.name} to $remotePath over SSH", error)
            }
        }

    override suspend fun exec(command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val stdout = channel.inputStream
            val stderr = channel.errStream
            channel.connect(CHANNEL_TIMEOUT_MS)
            try {
                val out = stdout.readBytes().toString(Charsets.UTF_8)
                val err = stderr.readBytes().toString(Charsets.UTF_8)
                // Drain first, then wait: a channel is not "closed" until its
                // output has been consumed, so checking the exit status before
                // reading can hang on a command that produced anything at all.
                while (!channel.isClosed) Thread.sleep(POLL_MS)
                if (channel.exitStatus != 0) {
                    throw IOException(
                        "`$command` failed on ${credentials.address} " +
                            "(exit ${channel.exitStatus}): ${err.ifBlank { out }.trim()}"
                    )
                }
                out
            } finally {
                channel.disconnect()
            }
        }
    }

    override suspend fun execStreaming(command: String): Result<RemoteProcess> =
        withContext(Dispatchers.IO) {
            runCatching {
                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(command)
                val stdout = channel.inputStream
                val stderr = channel.errStream
                channel.connect(CHANNEL_TIMEOUT_MS)
                SshProcess(channel, stdout, stderr, command, scope)
            }
        }

    /**
     * Bridges a Host loopback port to the Target's abstract socket.
     *
     * Three hops, because SSH cannot address the abstract namespace directly:
     *
     * 1. the [relayJar] runs on the Target under `app_process` and listens on a
     *    loopback TCP port there, forwarding each connection to the abstract
     *    socket;
     * 2. `ssh -L` forwards a Host port to that Target port;
     * 3. the session connects to the Host port exactly as it would to an
     *    `adb forward`, and cannot tell the difference.
     *
     * The relay reports the port it got on its first line of stdout, so it can
     * be given an ephemeral one rather than guessing at what is free on a
     * device we do not otherwise inspect.
     */
    override suspend fun openTunnel(abstractSocketName: String): Result<Tunnel> =
        withContext(Dispatchers.IO) {
            pushFile(relayJar, RELAY_REMOTE_PATH).getOrElse { return@withContext Result.failure(it) }

            val command = "CLASSPATH=$RELAY_REMOTE_PATH app_process / " +
                "dev.alexdev404.droidctl.relay.Relay $abstractSocketName"
            val process = execStreaming(command).getOrElse { return@withContext Result.failure(it) }

            val remotePort = withTimeoutOrNull(RELAY_STARTUP_TIMEOUT_MS) {
                awaitRelayPort(process)
            } ?: run {
                process.close()
                return@withContext Result.failure(
                    IOException(
                        "The relay on the Target did not report a port within " +
                            "$RELAY_STARTUP_TIMEOUT_MS ms. Its output:\n" +
                            process.snapshot().joinToString("\n") { it.text }
                                .ifBlank { "(it printed nothing)" }
                    )
                )
            }

            runCatching {
                // Port 0 asks jsch for an ephemeral local port and returns it.
                val hostPort = session.setPortForwardingL(0, "127.0.0.1", remotePort)
                log.i(
                    "SSH tunnel: 127.0.0.1:$hostPort -> ${credentials.host} " +
                        "relay :$remotePort -> localabstract:$abstractSocketName"
                )
                SshTunnel(hostPort, process) as Tunnel
            }.onFailure { process.close() }
        }

    /**
     * Drops the forward, then kills the relay.
     *
     * In that order: the relay exiting first would leave jsch forwarding to a
     * port nothing is listening on, and a late connection through it would
     * report a refused socket rather than a closed session.
     */
    override suspend fun closeTunnel(tunnel: Tunnel) {
        runCatching { session.delPortForwardingL(tunnel.hostPort) }
            .onFailure {
                log.w("Could not drop the SSH forward on ${tunnel.hostPort}: ${it.message}")
            }
        (tunnel as? SshTunnel)?.relay?.close()
    }

    /**
     * Reads the relay's `RELAY_PORT <n>` line.
     *
     * Safe against the relay having already printed it: the process flow
     * replays what it has seen, so there is no race between starting the
     * command and starting to listen for its first line.
     */
    private suspend fun awaitRelayPort(process: RemoteProcess): Int? =
        process.output
            .mapNotNull { line ->
                val text = line.text.trim()
                if (text.startsWith(RELAY_PORT_MARKER)) {
                    text.removePrefix(RELAY_PORT_MARKER).trim().toIntOrNull()
                } else {
                    null
                }
            }
            .firstOrNull()

    override fun close() {
        runCatching { session.disconnect() }
    }

    private class SshTunnel(
        override val hostPort: Int,
        /** Kept so [closeTunnel] can stop it; it lives exactly as long as the tunnel. */
        val relay: RemoteProcess,
    ) : Tunnel {
        override fun toString() = "ssh -L $hostPort"
    }

    companion object {
        private const val CHANNEL_TIMEOUT_MS = 30_000
        private const val POLL_MS = 20L
        private const val RELAY_STARTUP_TIMEOUT_MS = 30_000L
        private const val RELAY_PORT_MARKER = "RELAY_PORT"

        const val RELAY_REMOTE_PATH = "/data/local/tmp/droidctl-relay.jar"

        /**
         * Connects and authenticates.
         *
         * Key-based only. MagiskSSH ships `authorized_keys` per user and a key
         * manager in its WebUI, and a password prompt in a mirroring app is a
         * worse thing to build than a key the app generates once and never
         * discloses.
         */
        suspend fun connect(
            credentials: SshCredentials,
            privateKeyPem: ByteArray,
            knownHostKey: String?,
            relayJar: File,
            scope: CoroutineScope,
        ): Result<SshTransport> = withContext(Dispatchers.IO) {
            runCatching {
                val jsch = JSch()
                jsch.addIdentity("droidctl", privateKeyPem, null, null)

                val session = jsch.getSession(credentials.user, credentials.host, credentials.port)
                if (knownHostKey != null) {
                    // Pinned: the key recorded on the first connection to this
                    // Target is required to match on every later one, so a
                    // different machine answering on that address is refused
                    // rather than silently accepted.
                    jsch.hostKeyRepository.add(
                        HostKey(credentials.host, Base64.getDecoder().decode(knownHostKey)),
                        null,
                    )
                    session.setConfig("StrictHostKeyChecking", "yes")
                } else {
                    // First connection: trust on first use, exactly as any ssh
                    // client does, and hand the key back so it can be pinned
                    // from then on.
                    session.setConfig("StrictHostKeyChecking", "no")
                }
                session.connect(CHANNEL_TIMEOUT_MS)
                SshTransport(session, credentials, relayJar, scope)
            }.recoverCatching { error ->
                throw IOException("Could not connect to ${credentials.address}: ${error.message}", error)
            }
        }
    }
}

/** A command running on the Target through an SSH exec channel. */
private class SshProcess(
    private val channel: ChannelExec,
    stdout: InputStream,
    stderr: InputStream,
    private val label: String,
    scope: CoroutineScope,
) : RemoteProcess {

    private val closed = AtomicBoolean(false)

    private val _output = MutableSharedFlow<ProcessLine>(
        replay = 200,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val output: SharedFlow<ProcessLine> = _output.asSharedFlow()

    init {
        // One reader per stream, because both must be drained: an SSH channel
        // whose output nobody consumes stops the remote command once its window
        // fills, which would look like the scrcpy server silently freezing.
        scope.launch(Dispatchers.IO) { pump(stdout, isError = false) }
        scope.launch(Dispatchers.IO) { pump(stderr, isError = true) }
    }

    private fun pump(stream: InputStream, isError: Boolean) {
        runCatching {
            stream.bufferedReader().forEachLine { line ->
                _output.tryEmit(ProcessLine(line, isError))
            }
        }
        if (!isError) {
            _output.tryEmit(
                ProcessLine("<$label exited with code ${channel.exitStatus}>", channel.exitStatus != 0)
            )
        }
    }

    override fun snapshot(): List<ProcessLine> = _output.replayCache

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) { runCatching { channel.disconnect() } }
    }
}
