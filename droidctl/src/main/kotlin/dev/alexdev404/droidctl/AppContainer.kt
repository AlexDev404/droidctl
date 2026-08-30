package dev.alexdev404.droidctl

import android.content.Context
import dev.alexdev404.droidctl.adb.AdbBinary
import dev.alexdev404.droidctl.adb.AdbBinaryLocator
import dev.alexdev404.droidctl.adb.AdbClient
import dev.alexdev404.droidctl.adb.AdbDiscovery
import dev.alexdev404.droidctl.adb.AdbSetupFailure
import dev.alexdev404.droidctl.data.DroidCtlPreferences
import dev.alexdev404.droidctl.adb.RootShellSession
import dev.alexdev404.droidctl.scrcpy.ScrcpyLauncher
import dev.alexdev404.droidctl.scrcpy.ScrcpyServerAsset
import dev.alexdev404.droidctl.session.MirrorSession
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Manual constructor injection for the whole app.
 *
 * There is exactly one graph, it is built once, and it is small: a DI framework
 * would add a build step and an annotation processor to save about twenty lines.
 * Nothing here is lazy-by-accident -- [adbClient] and everything downstream of
 * it can only exist once [initialize] has found a working adb binary.
 */
class AppContainer(private val context: Context) {

    /** Outlives any one screen: a mirroring session must survive a rotation. */
    val applicationScope = CoroutineScope(SupervisorJob() + CoroutineName("DroidCtl"))

    val rootShell = RootShellSession()
    val preferences = DroidCtlPreferences(context)
    val discovery = AdbDiscovery(context)

    private val locator = AdbBinaryLocator(rootShell)
    private val serverAsset = ScrcpyServerAsset(context)

    /** Null until [initialize] succeeds. */
    var adbClient: AdbClient? = null
        private set

    var mirrorSession: MirrorSession? = null
        private set

    var binary: AdbBinary? = null
        private set

    /**
     * Runs the first-run gate, in order, and stops at the first failure.
     *
     * @return null when everything is in place, otherwise what is missing.
     */
    suspend fun initialize(): AdbSetupFailure? {
        // Re-running the gate (its "Try again" button) must not build a second
        // MirrorSession and orphan the one that owns the live forward.
        if (adbClient != null) return null

        if (!rootShell.isRootAvailable()) return AdbSetupFailure.NoRoot

        val resolved = locator.resolve().getOrElse {
            return AdbSetupFailure.BinaryNotFound(
                listOf(AdbBinary.ADB_NDK_PATH, AdbBinary.SYSTEM_BIN_PATH, "PATH")
            )
        }
        locator.prepareHome(resolved)

        val client = AdbClient(rootShell, resolved)
        val version = client.adbVersion().getOrElse { error ->
            return AdbSetupFailure.VersionCheckFailed(resolved.path, error.message ?: "unknown error")
        }
        DroidCtlLog.adb.i("adb at ${resolved.path}: ${version.lineSequence().first()}")

        client.startServer().onFailure {
            return AdbSetupFailure.VersionCheckFailed(resolved.path, it.message ?: "unknown error")
        }

        binary = resolved
        adbClient = client
        mirrorSession = MirrorSession(
            context = context,
            adb = client,
            launcher = ScrcpyLauncher(client, serverAsset),
            preferences = preferences,
            scope = applicationScope,
        )
        return null
    }
}
