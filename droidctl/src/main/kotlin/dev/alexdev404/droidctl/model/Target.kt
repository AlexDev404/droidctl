package dev.alexdev404.droidctl.model

/** How a Target was found on the network. */
enum class DiscoveryKind {
    /** `_adb-tls-pairing._tcp`: the Target is showing a pairing code right now. */
    Pairing,

    /** `_adb-tls-connect._tcp`: the Target is already paired and ready to connect. */
    Connect,
}

/** A Target advertised over mDNS. */
data class DiscoveredTarget(
    val serviceName: String,
    val kind: DiscoveryKind,
    val host: String?,
    val port: Int,
) {
    val isResolved: Boolean get() = host != null && port > 0
    val serial: String? get() = if (isResolved) "$host:$port" else null
}

/**
 * A Target the user has connected to before.
 *
 * Persisted so that reconnecting is one tap. The port is remembered as a hint
 * only: a Target's wireless debugging port changes when it reboots or when
 * debugging is toggled, which is the single most common reason a saved Target
 * stops connecting.
 */
data class KnownTarget(
    val name: String,
    val host: String,
    val port: Int,
    val lastConnectedAtMillis: Long,
) {
    val serial: String get() = "$host:$port"
}
