package dev.alexdev404.droidctl.adb

import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import dev.alexdev404.droidctl.DroidCtlLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the **single long-lived** root shell every short adb invocation runs
 * through.
 *
 * Spawning `su` per command is slow and makes Magisk pop an authorization
 * prompt storm, so all of `adb connect`, `adb devices`, `adb forward`, ... share
 * one shell. Commands are serialised behind a mutex: libsu's shell is a single
 * pipe, and interleaving two jobs on it mixes their output.
 */
class RootShellSession {

    private val log = DroidCtlLog.adb
    private val mutex = Mutex()

    /** Whether root was granted. Null until the first check. */
    @Volatile
    private var rootGranted: Boolean? = null

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        rootGranted ?: runCatching {
            // getShell() blocks while su is spawned and the request is granted.
            Shell.getShell().isRoot
        }.onFailure {
            log.e("Could not obtain a root shell", it)
        }.getOrDefault(false).also { rootGranted = it }
    }

    /** Runs [command] on the shared root shell and returns its raw result. */
    suspend fun run(binary: AdbBinary, command: AdbCommand): AdbResult =
        runRaw(command.toShellLine(binary), command)

    /** Runs a raw `sh` line on the shared root shell. Used for binary discovery. */
    suspend fun runRaw(line: String, command: AdbCommand): AdbResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                log.d("exec: $command")
                val out = ArrayList<String>()
                val err = ArrayList<String>()
                val result = Shell.cmd(line).to(out, err).exec()
                AdbResult(command, result.code, out.toList(), err.toList())
            }
        }

    /**
     * Starts a long-lived command (the `app_process` invocation that runs the
     * scrcpy server on the Target) on a **dedicated** root shell.
     *
     * It cannot share the shell above: the command runs for the whole mirroring
     * session and would block every other adb call behind it. In exchange for
     * one extra `su` spawn per session we get live stdout/stderr, which is the
     * only place a server-side Java stack trace ever appears.
     */
    suspend fun startLongLived(binary: AdbBinary, command: AdbCommand): RootProcess =
        withContext(Dispatchers.IO) { RootProcess.start(binary, command) }
}

/**
 * A running long-lived root command, with its output exposed as a flow.
 *
 * NOTE (deviation from the spec's `shellStreaming(): Process`): libsu does not
 * hand out a [java.lang.Process] for a command run inside its shell, and
 * hand-rolling `Runtime.exec("su")` to get one is explicitly ruled out. This
 * type is the equivalent handle: line-oriented output plus a [close] that
 * actually terminates the process.
 */
class RootProcess private constructor(
    private val shell: Shell,
    private val label: String,
) {
    private val log = DroidCtlLog.server
    private val closed = AtomicBoolean(false)

    @Volatile
    private var pid: Int? = null

    @Volatile
    private var exitCode: Int? = null

    private val _output = MutableSharedFlow<ProcessLine>(
        replay = 200,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** stdout and stderr of the command, interleaved, in arrival order. */
    val output: SharedFlow<ProcessLine> = _output.asSharedFlow()

    /** Exit code once the command has terminated, null while it is running. */
    val terminatedWith: Int? get() = exitCode

    val isRunning: Boolean get() = exitCode == null && !closed.get()

    /** Everything the process has printed so far, oldest first. */
    fun snapshot(): List<ProcessLine> = _output.replayCache

    /**
     * Terminates the command and releases its shell.
     *
     * Killing the Host-side `adb shell` closes the adb connection to the Target,
     * which closes the server's sockets, which is how the scrcpy server on the
     * Target learns to exit. Idempotent.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val pidToKill = pid
        if (pidToKill != null && exitCode == null) {
            // Signalled from the *shared* shell: the dedicated one is blocked in
            // `wait` on exactly this pid and cannot run anything else.
            runCatching { Shell.cmd("kill -TERM $pidToKill 2>/dev/null").exec() }
                .onFailure { log.w("Could not signal $label (pid=$pidToKill)", it) }
        }
        runCatching { shell.close() }
            .onFailure { log.w("Could not close the shell running $label", it) }
        log.d("$label terminated")
    }

    companion object {
        private const val PID_MARKER = "__DROIDCTL_PID__:"

        internal fun start(binary: AdbBinary, command: AdbCommand): RootProcess {
            val label = command.toString()
            // A dedicated shell: this command runs for the whole session.
            val shell = Shell.Builder.create().setFlags(0).build()
            val process = RootProcess(shell, label)

            // Start in the background so the shell survives to report the pid,
            // then block on it so libsu still sees the job run to completion.
            // Using `exec` instead would replace libsu's own shell process and
            // break its job-completion protocol.
            val line = buildString {
                append(command.toShellLine(binary))
                append(" & echo ").append(PID_MARKER).append("\$!; wait \$!")
            }

            val stdout = object : CallbackList<String>() {
                override fun onAddElement(e: String) {
                    if (e.startsWith(PID_MARKER)) {
                        process.pid = e.removePrefix(PID_MARKER).trim().toIntOrNull()
                        return
                    }
                    process._output.tryEmit(ProcessLine(e, isError = false))
                }
            }
            val stderr = object : CallbackList<String>() {
                override fun onAddElement(e: String) {
                    process._output.tryEmit(ProcessLine(e, isError = true))
                }
            }

            shell.newJob().add(line).to(stdout, stderr).submit { result ->
                process.exitCode = result.code
                process._output.tryEmit(
                    ProcessLine(
                        "<adb shell exited with code ${result.code}>",
                        isError = result.code != 0,
                    )
                )
            }
            return process
        }
    }
}

/** One line of output from a [RootProcess]. */
data class ProcessLine(val text: String, val isError: Boolean)
