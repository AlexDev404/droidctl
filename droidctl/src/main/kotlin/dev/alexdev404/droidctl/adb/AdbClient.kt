package dev.alexdev404.droidctl.adb

import dev.alexdev404.droidctl.DroidCtlLog
import java.io.File

/**
 * Connection state a Target can be in, as reported by `adb devices -l`.
 *
 * These are kept distinct because they need different remediation: `device` is
 * ready, `unauthorized` needs a tap on the Target, `offline` usually needs a
 * reconnect, and `authorizing` just needs a moment.
 */
enum class AdbDeviceState {
    Device,
    Unauthorized,
    Offline,
    Authorizing,
    Unknown;

    val isUsable: Boolean get() = this == Device

    companion object {
        fun parse(raw: String): AdbDeviceState = when (raw) {
            "device" -> Device
            "unauthorized" -> Unauthorized
            "offline" -> Offline
            "authorizing" -> Authorizing
            else -> Unknown
        }
    }
}

/** One line of `adb devices -l`. */
data class AdbDevice(
    val serial: String,
    val state: AdbDeviceState,
    val model: String? = null,
    val product: String? = null,
    val transportId: String? = null,
) {
    /** A name to show the user: the model if adb reported one, else the serial. */
    val displayName: String get() = model?.replace('_', ' ') ?: serial

    /** What the user should do about [state], or null when nothing is wrong. */
    val remediation: String?
        get() = when (state) {
            AdbDeviceState.Device -> null
            AdbDeviceState.Unauthorized ->
                "The Target has not authorized this Host. Accept the debugging prompt on the " +
                    "Target's screen, then reconnect."
            AdbDeviceState.Offline ->
                "The Target is listed but not responding. Disconnect and reconnect it; if the " +
                    "Target rebooted, its wireless debugging port has changed."
            AdbDeviceState.Authorizing ->
                "The Target is still authorizing this Host. Wait a moment and refresh."
            AdbDeviceState.Unknown ->
                "adb reported a state DroidCtl does not recognize for this Target."
        }
}

/**
 * The adb client: a thin, typed wrapper around shelling out to the adb binary.
 *
 * Everything here runs as root, through [RootShellSession], because binding
 * adb's server socket and reading its key store needs it. Note that only the
 * *control* calls need root -- the video and control data path is an ordinary
 * unprivileged socket to `127.0.0.1`.
 */
class AdbClient(
    private val shell: RootShellSession,
    private val binary: AdbBinary,
) {
    private val log = DroidCtlLog.adb

    suspend fun isRootAvailable(): Boolean = shell.isRootAvailable()

    /** `adb version`. Used by the first-run gate to prove the binary really runs. */
    suspend fun adbVersion(): Result<String> {
        val result = shell.run(binary, AdbCommand.of("version"))
        return if (result.isSuccess && result.stdout.isNotEmpty()) {
            Result.success(result.stdoutText)
        } else {
            Result.failure(result.asException("`adb version` failed"))
        }
    }

    /** `adb start-server`. Idempotent; safe to call on every app start. */
    suspend fun startServer(): Result<Unit> {
        val result = shell.run(binary, AdbCommand.of("start-server"))
        return if (result.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(result.asException("Could not start the adb server"))
        }
    }

    /**
     * `adb pair <host>:<port> <code>`.
     *
     * [code] is the six-digit code the Target shows under
     * Wireless debugging > Pair device with pairing code. It is marked secret so
     * it never reaches a log line or an error message.
     */
    suspend fun pair(host: String, port: Int, code: String): Result<Unit> {
        val command = AdbCommand.of("pair", "$host:$port", code).redactingArg(2)
        val result = shell.run(binary, command)
        // adb pair exits 0 even on failure in some builds; check the text too.
        val text = result.stdoutText + "\n" + result.stderrText
        val paired = result.isSuccess && text.contains("Successfully paired", ignoreCase = true)
        return if (paired) {
            log.i("Paired with $host:$port")
            Result.success(Unit)
        } else {
            Result.failure(
                AdbException(
                    "Pairing with $host:$port failed: ${redactCode(result.diagnosticText, code)}",
                    result,
                )
            )
        }
    }

    /** `adb connect <host>:<port>`, returning the serial the Target is known by. */
    suspend fun connect(host: String, port: Int): Result<String> {
        val serial = "$host:$port"
        val result = shell.run(binary, AdbCommand.of("connect", serial))
        val text = result.stdoutText + "\n" + result.stderrText
        val connected = result.isSuccess &&
            (text.contains("connected to", ignoreCase = true)) &&
            !text.contains("failed to connect", ignoreCase = true) &&
            !text.contains("cannot connect", ignoreCase = true)
        return if (connected) {
            log.i("Connected to Target $serial")
            Result.success(serial)
        } else {
            Result.failure(result.asException("Could not connect to $serial"))
        }
    }

    /** `adb disconnect <serial>`. */
    suspend fun disconnect(serial: String): Result<Unit> {
        val result = shell.run(binary, AdbCommand.of("disconnect", serial))
        return if (result.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(result.asException("Could not disconnect $serial"))
        }
    }

    /** `adb devices -l`, parsed. */
    suspend fun devices(): Result<List<AdbDevice>> {
        val result = shell.run(binary, AdbCommand.of("devices", "-l"))
        return if (result.isSuccess) {
            Result.success(parseDevices(result.stdout))
        } else {
            Result.failure(result.asException("Could not list devices"))
        }
    }

    /** `adb -s <serial> forward tcp:<hostPort> <remote>`. */
    suspend fun forward(serial: String, hostPort: Int, remote: String): Result<Unit> {
        val result = shell.run(
            binary,
            AdbCommand.of("-s", serial, "forward", "tcp:$hostPort", remote),
        )
        return if (result.isSuccess) {
            log.d("Forwarded 127.0.0.1:$hostPort -> $remote on $serial")
            Result.success(Unit)
        } else {
            Result.failure(result.asException("Could not forward tcp:$hostPort to $remote"))
        }
    }

    /**
     * `adb -s <serial> forward --remove tcp:<hostPort>`.
     *
     * Leaked forwards accumulate in the adb server and collide with the next
     * session's port, so this is called on every teardown path and again for
     * stale ports at app start.
     */
    suspend fun removeForward(serial: String, hostPort: Int): Result<Unit> {
        val result = shell.run(
            binary,
            AdbCommand.of("-s", serial, "forward", "--remove", "tcp:$hostPort"),
        )
        return if (result.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(result.asException("Could not remove forward tcp:$hostPort"))
        }
    }

    /** `adb -s <serial> push <local> <remote>`. */
    suspend fun push(serial: String, local: File, remote: String): Result<Unit> {
        val result = shell.run(
            binary,
            AdbCommand.of("-s", serial, "push", local.absolutePath, remote),
        )
        return if (result.isSuccess) {
            log.i("Pushed ${local.name} (${local.length()} bytes) to $serial:$remote")
            Result.success(Unit)
        } else {
            Result.failure(result.asException("Could not push ${local.name} to $serial:$remote"))
        }
    }

    /**
     * Starts `adb -s <serial> shell <command>` and keeps the handle.
     *
     * Used for the `app_process` invocation that runs the scrcpy server: it is
     * long-lived, and its stdout/stderr are where server-side stack traces
     * appear. Losing that output makes any server failure undiagnosable.
     */
    suspend fun shellStreaming(serial: String, command: String): RootProcess =
        shell.startLongLived(binary, AdbCommand.of("-s", serial, "shell", command))

    companion object {
        internal fun parseDevices(lines: List<String>): List<AdbDevice> = lines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("List of devices attached") }
            .filterNot { it.startsWith("*") } // "* daemon started successfully"
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val extras = parts.drop(2)
                    .mapNotNull { field ->
                        val idx = field.indexOf(':')
                        if (idx <= 0) null else field.substring(0, idx) to field.substring(idx + 1)
                    }
                    .toMap()
                AdbDevice(
                    serial = parts[0],
                    state = AdbDeviceState.parse(parts[1]),
                    model = extras["model"],
                    product = extras["product"],
                    transportId = extras["transport_id"],
                )
            }

        /**
         * Belt and braces: adb sometimes echoes the pairing code back in its own
         * error text, so strip it from anything we are about to show or log.
         */
        internal fun redactCode(text: String, code: String): String =
            if (code.isEmpty()) text else text.replace(code, "<redacted>")
    }
}
