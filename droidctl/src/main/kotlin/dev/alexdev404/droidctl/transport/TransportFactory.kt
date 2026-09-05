package dev.alexdev404.droidctl.transport

import dev.alexdev404.droidctl.DroidCtlLog
import dev.alexdev404.droidctl.adb.AdbClient
import dev.alexdev404.droidctl.data.DroidCtlPreferences
import dev.alexdev404.droidctl.model.KnownTarget
import dev.alexdev404.droidctl.model.TransportKind
import kotlinx.coroutines.CoroutineScope
import java.io.IOException

/**
 * Opens the right [DeviceTransport] for a Target.
 *
 * Holds the [AdbClient] rather than taking it at construction because the two
 * modes are gated differently: adb needs root and the adb-ndk module on the
 * Host and so cannot be set up until that gate passes, while SSH needs neither
 * and must stay usable on a Host where the gate never will. Everything
 * downstream of here -- the session, the launcher, the whole video path -- is
 * built once and works either way.
 */
class TransportFactory(
    private val preferences: DroidCtlPreferences,
    private val keys: SshKeyStore,
    private val relayAsset: RelayAsset,
    private val scope: CoroutineScope,
) {
    private val log = DroidCtlLog.adb

    /** Set once the adb gate passes; null on a Host that has no usable adb. */
    @Volatile
    var adb: AdbClient? = null

    val isAdbReady: Boolean get() = adb != null

    fun requireAdb(): AdbClient = adb ?: throw IOException(
        "adb is not available on this Host. Grant root and install the adb-ndk module, " +
            "or switch the connection mode to SSH."
    )

    suspend fun open(target: KnownTarget): Result<DeviceTransport> = when (target.transport) {
        TransportKind.Adb -> runCatching { AdbTransport(requireAdb(), target.serial, preferences) }
        TransportKind.Ssh -> openSsh(target)
    }

    /**
     * Connects over SSH, pinning the Target's host key the first time.
     *
     * The pin is written only after the connection succeeds, so a Target that
     * refuses the key does not leave a record behind that would then have to be
     * cleared before a retry could work.
     */
    private suspend fun openSsh(target: KnownTarget): Result<DeviceTransport> {
        val relay = relayAsset.extract().getOrElse { return Result.failure(it) }
        val privateKey = keys.privateKey().getOrElse { return Result.failure(it) }
        val credentials = SshCredentials(
            host = target.host,
            port = target.port,
            user = target.sshUser ?: SshCredentials.DEFAULT_USER,
        )
        return SshTransport
            .connect(credentials, privateKey, target.sshHostKey, relay, scope)
            .onSuccess { transport ->
                if (target.sshHostKey == null) {
                    transport.hostKeyBase64?.let { key ->
                        log.i("Pinning ${credentials.host}'s SSH host key on first connection")
                        preferences.rememberSshHostKey(target, key)
                    }
                }
            }
    }
}
