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
import dev.alexdev404.droidctl.transport.RelayAsset
import dev.alexdev404.droidctl.transport.SshKeyStore
import dev.alexdev404.droidctl.transport.TransportFactory
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Manual constructor injection for the whole app.
 *
 * There is exactly one graph, it is built once, and it is small: a DI framework
 * would add a build step and an annotation processor to save about twenty lines.
 *
 * The session and everything downstream of it are built eagerly and are never
 * null. Only [adbClient] waits for [initializeAdb], because only the ADB
 * transport needs it: SSH mode has to work on a Host that will never pass the
 * root gate, so gating the whole graph on adb would make the second mode
 * unreachable on exactly the devices it exists for.
 */
class AppContainer(private val context: Context) {

    /** Outlives any one screen: a mirroring session must survive a rotation. */
    val applicationScope = CoroutineScope(SupervisorJob() + CoroutineName("DroidCtl"))

    val rootShell = RootShellSession()
    val preferences = DroidCtlPreferences(context)
    val discovery = AdbDiscovery(context)

    private val locator = AdbBinaryLocator(rootShell)
    private val serverAsset = ScrcpyServerAsset(context)
    private val relayAsset = RelayAsset(context)

    /** The Host's SSH identity. Generated on demand, never imported. */
    val sshKeys = SshKeyStore(context)

    val transports = TransportFactory(
        preferences = preferences,
        keys = sshKeys,
        relayAsset = relayAsset,
        scope = applicationScope,
    )

    val mirrorSession = MirrorSession(
        context = context,
        transports = transports,
        launcher = ScrcpyLauncher(serverAsset),
        preferences = preferences,
        scope = applicationScope,
    )

    /** Null until [initializeAdb] succeeds; only the ADB transport needs it. */
    val adbClient: AdbClient? get() = transports.adb

    var binary: AdbBinary? = null
        private set

    /**
     * Runs the ADB gate -- root, then the adb binary, then that adb actually
     * runs -- and stops at the first failure.
     *
     * Only reached in [dev.alexdev404.droidctl.model.TransportKind.Adb] mode.
     *
     * @return null when everything is in place, otherwise what is missing.
     */
    suspend fun initializeAdb(): AdbSetupFailure? {
        // Re-running the gate (its "Try again" button) must not replace a client
        // a live session is already mirroring through.
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
        transports.adb = client
        return null
    }
}
