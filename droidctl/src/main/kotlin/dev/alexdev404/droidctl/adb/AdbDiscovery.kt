package dev.alexdev404.droidctl.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dev.alexdev404.droidctl.DroidCtlLog
import dev.alexdev404.droidctl.model.DiscoveredTarget
import dev.alexdev404.droidctl.model.DiscoveryKind
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import java.util.Collections

/**
 * mDNS discovery of Targets with wireless debugging enabled.
 *
 * Android advertises two service types:
 *
 *  - `_adb-tls-pairing._tcp` while the *Pair device with pairing code* dialog is
 *    open, and
 *  - `_adb-tls-connect._tcp` for a device that is already paired.
 *
 * Discovery is genuinely unreliable in the field: client isolation on the
 * network, some OEM Wi-Fi stacks, and Android's own NsdManager quirks all make
 * it come up empty on a Target that is right there. Manual `IP:port` entry is
 * therefore a first-class path in the UI, not a fallback hidden behind a
 * failure.
 */
class AdbDiscovery(context: Context) {

    private val log = DroidCtlLog.adb
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Targets currently showing a pairing code. */
    fun pairingTargets(): Flow<List<DiscoveredTarget>> =
        browse(SERVICE_TYPE_PAIRING, DiscoveryKind.Pairing)

    /** Paired Targets ready to be connected to. */
    fun connectableTargets(): Flow<List<DiscoveredTarget>> =
        browse(SERVICE_TYPE_CONNECT, DiscoveryKind.Connect)

    /** Both service types at once, pairing entries first. */
    fun allTargets(): Flow<List<DiscoveredTarget>> =
        combine(pairingTargets(), connectableTargets()) { pairing, connect -> pairing + connect }

    private fun browse(serviceType: String, kind: DiscoveryKind): Flow<List<DiscoveredTarget>> =
        callbackFlow {
            val found = Collections.synchronizedMap(LinkedHashMap<String, DiscoveredTarget>())

            fun publish() {
                trySend(synchronized(found) { found.values.toList() })
            }

            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    // Not swallowed: with discovery silently dead the UI would
                    // show an empty list forever and look like "no devices".
                    log.w("mDNS discovery of $serviceType could not start (error $errorCode)")
                    close(
                        AdbException(
                            "Network service discovery failed to start (error $errorCode). " +
                                "Enter the Target's IP and port manually instead."
                        )
                    )
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    log.w("mDNS discovery of $serviceType could not stop (error $errorCode)")
                }

                override fun onDiscoveryStarted(serviceType: String) {
                    log.d("Browsing $serviceType")
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    log.d("Stopped browsing $serviceType")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    log.d("Found ${service.serviceName} (${service.serviceType})")
                    found[service.serviceName] =
                        DiscoveredTarget(service.serviceName, kind, host = null, port = 0)
                    publish()
                    resolve(service, kind, found, ::publish)
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    log.d("Lost ${service.serviceName}")
                    found.remove(service.serviceName)
                    publish()
                }
            }

            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            awaitClose {
                runCatching { nsdManager.stopServiceDiscovery(listener) }
                    .onFailure { log.d("stopServiceDiscovery threw during cleanup: ${it.message}") }
            }
        }

    @Suppress("DEPRECATION") // resolveService(NsdServiceInfo, ResolveListener) is the only API before 34
    private fun resolve(
        service: NsdServiceInfo,
        kind: DiscoveryKind,
        found: MutableMap<String, DiscoveredTarget>,
        publish: () -> Unit,
    ) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                log.w("Could not resolve ${service.serviceName} (error $errorCode)")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = hostAddressOf(resolved) ?: return
                found[resolved.serviceName] =
                    DiscoveredTarget(resolved.serviceName, kind, host, resolved.port)
                log.i("Resolved ${resolved.serviceName} to $host:${resolved.port}")
                publish()
            }
        }
        nsdManager.resolveService(service, listener)
    }

    @Suppress("DEPRECATION") // getHost() is deprecated in favour of getHostAddresses() on API 34+
    private fun hostAddressOf(info: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull()?.hostAddress
        } else {
            info.host?.hostAddress
        }

    private companion object {
        const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp"
        const val SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp"
    }
}
