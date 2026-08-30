package dev.alexdev404.droidctl.adb

import dev.alexdev404.droidctl.DroidCtlLog

/**
 * A resolved `adb` executable on the **Host**.
 *
 * @param path absolute path to the executable.
 * @param isAdbNdkWrapper true when the path is the adb-ndk wrapper script, which
 *   sets `HOME`/`TMPDIR` itself. The wrapper is preferred precisely because it
 *   gets that right; [homeDir]/[tmpDir] are only a safety net for a bare binary.
 */
data class AdbBinary(
    val path: String,
    val isAdbNdkWrapper: Boolean,
    val homeDir: String = DEFAULT_HOME,
    val tmpDir: String = DEFAULT_TMP,
) {
    companion object {
        /**
         * A root-writable directory that survives across sessions, so that adb
         * can persist its `adbkey` there. Without it the Target re-prompts to
         * authorize this Host on every single connection.
         */
        const val DEFAULT_HOME = "/data/local/tmp/droidctl-adb"
        const val DEFAULT_TMP = "/data/local/tmp/droidctl-adb"

        /** Where the adb-ndk Magisk module installs its binaries. */
        const val ADB_NDK_PATH = "/system/xbin/adb"
        const val SYSTEM_BIN_PATH = "/system/bin/adb"
        const val ADB_NDK_URL = "https://github.com/Magisk-Modules-Repo/adb-ndk"
    }
}

/** Why the first-run gate is blocking the app. */
sealed interface AdbSetupFailure {
    /** No root shell could be obtained. */
    data object NoRoot : AdbSetupFailure

    /** No adb executable was found anywhere we looked. */
    data class BinaryNotFound(val searched: List<String>) : AdbSetupFailure

    /** An adb executable exists but `adb version` failed; [stderr] is verbatim. */
    data class VersionCheckFailed(val path: String, val stderr: String) : AdbSetupFailure
}

/**
 * Locates and validates the adb binary once, at startup.
 *
 * Search order is the one the spec fixes: the adb-ndk wrapper first, then
 * `/system/bin/adb` (present on some ROMs), then whatever is on `PATH`.
 */
class AdbBinaryLocator(private val shell: RootShellSession) {

    private val log = DroidCtlLog.adb

    suspend fun resolve(): Result<AdbBinary> {
        val searched = listOf(AdbBinary.ADB_NDK_PATH, AdbBinary.SYSTEM_BIN_PATH)

        for (candidate in searched) {
            if (isExecutable(candidate)) {
                log.i("Using adb at $candidate")
                return Result.success(
                    AdbBinary(candidate, isAdbNdkWrapper = candidate == AdbBinary.ADB_NDK_PATH)
                )
            }
        }

        val which = shell.runRaw("command -v adb", AdbCommand.of("command -v adb"))
        val onPath = which.stdout.firstOrNull()?.trim()
        if (which.isSuccess && !onPath.isNullOrBlank() && isExecutable(onPath)) {
            log.i("Using adb found on PATH at $onPath")
            return Result.success(AdbBinary(onPath, isAdbNdkWrapper = false))
        }

        return Result.failure(
            AdbException(
                "No adb binary found. DroidCtl needs the adb-ndk Magisk module, which installs " +
                    "a static adb at ${AdbBinary.ADB_NDK_PATH}. Searched: " +
                    (searched + "PATH").joinToString(", ") + ". See ${AdbBinary.ADB_NDK_URL}"
            )
        )
    }

    /** Ensures adb's HOME exists before it is first used to persist `adbkey`. */
    suspend fun prepareHome(binary: AdbBinary): Result<Unit> {
        val result = shell.runRaw(
            "mkdir -p ${AdbCommand.quote(binary.homeDir)} && mkdir -p ${AdbCommand.quote(binary.tmpDir)}",
            AdbCommand.of("mkdir", "-p", binary.homeDir, binary.tmpDir),
        )
        return if (result.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(result.asException("Could not create adb HOME at ${binary.homeDir}"))
        }
    }

    private suspend fun isExecutable(path: String): Boolean {
        val quoted = AdbCommand.quote(path)
        val result = shell.runRaw("test -x $quoted", AdbCommand.of("test", "-x", path))
        return result.isSuccess
    }
}
