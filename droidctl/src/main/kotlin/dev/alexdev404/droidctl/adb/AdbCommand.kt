package dev.alexdev404.droidctl.adb

import java.io.IOException

/**
 * A single invocation of the `adb` binary on the **Host**.
 *
 * The command is modelled as an argv list rather than a string so that nothing
 * has to be escaped by hand at the call sites. [toShellLine] does the quoting
 * once, correctly, when the command is finally handed to the root shell.
 *
 * Arguments may be marked secret (see [redactingArg]); those are replaced with
 * `<redacted>` in [toString] and therefore in every log line and error message.
 * The ADB pairing code is the only such argument today, and it must never reach
 * logcat or the in-app debug pane.
 */
class AdbCommand private constructor(
    val args: List<String>,
    private val secretArgIndices: Set<Int>,
) {
    /** Marks the argument at [index] as secret, so it is never logged. */
    fun redactingArg(index: Int): AdbCommand {
        require(index in args.indices) { "no argument at index $index" }
        return AdbCommand(args, secretArgIndices + index)
    }

    /**
     * Renders this command as a single `sh` line invoking [binary].
     *
     * `HOME` and `TMPDIR` are always exported: when the resolved binary is the
     * adb-ndk wrapper it sets them itself and ours are ignored, but when it is a
     * bare `adb` binary they are what let it persist its `adbkey`. Without a
     * writable `HOME`, adb regenerates its key every run and the Target
     * re-prompts for authorization on every single session.
     *
     * **stdin is always `/dev/null`.** `adb shell` forwards its own stdin to the
     * remote process, so run in the foreground it reads from the very pipe libsu
     * feeds the shell its commands through -- and swallows the completion marker
     * libsu is waiting for. The command then never returns, the shared shell's
     * mutex is never released, and every later adb call queues behind it
     * forever. The long-lived server launch escapes this only by accident, being
     * backgrounded with `&`, which POSIX sh already redirects from `/dev/null`.
     */
    fun toShellLine(binary: AdbBinary): String = buildString {
        append("HOME=").append(quote(binary.homeDir))
        append(" TMPDIR=").append(quote(binary.tmpDir))
        append(' ').append(quote(binary.path))
        for (arg in args) {
            append(' ').append(quote(arg))
        }
        append(" < /dev/null")
    }

    /** A redacted, human-readable rendering. Safe to log. */
    override fun toString(): String =
        args.mapIndexed { i, arg -> if (i in secretArgIndices) "<redacted>" else arg }
            .joinToString(" ", prefix = "adb ")

    companion object {
        fun of(vararg args: String): AdbCommand = AdbCommand(args.toList(), emptySet())

        /** Wraps [value] in single quotes, escaping any single quote inside it. */
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}

/** Raw outcome of running a command in the root shell. */
data class AdbResult(
    val command: AdbCommand,
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
) {
    val isSuccess: Boolean get() = exitCode == 0

    val stdoutText: String get() = stdout.joinToString("\n")
    val stderrText: String get() = stderr.joinToString("\n")

    /** stderr if the command wrote any, otherwise stdout: adb uses both for errors. */
    val diagnosticText: String
        get() = stderrText.ifBlank { stdoutText }.ifBlank { "no output" }

    fun asException(context: String): AdbException =
        AdbException("$context (exit $exitCode): $diagnosticText", this)
}

/**
 * Failure of an adb operation. Carries the underlying [AdbResult] when there was
 * one so the UI can show the real adb diagnostics instead of a generic message.
 */
class AdbException(
    message: String,
    val result: AdbResult? = null,
    cause: Throwable? = null,
) : IOException(message, cause)
