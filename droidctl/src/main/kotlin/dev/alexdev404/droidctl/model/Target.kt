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
    /**
     * The link speed measured the last time the server jar was pushed to this
     * Target, or null if it never has been.
     *
     * Remembered because the push is skipped once the Target already has the
     * jar, and that is the only thing DroidCtl transfers before the video
     * stream exists. Without this, Automatic would have nothing to go on for
     * every session after the first.
     */
    val lastMeasuredBitsPerSecond: Long? = null,
    /** Which way this Target is reached. */
    val transport: TransportKind = TransportKind.Adb,
    /**
     * The account to log in as in [TransportKind.Ssh] mode, or null for the
     * transport's own default.
     *
     * Null rather than the literal `shell` so the default lives in exactly one
     * place -- next to the transport that knows *why* it is `shell` -- while
     * this stays a plain data class the persistence layer can round-trip.
     */
    val sshUser: String? = null,
    /**
     * The Target's SSH host key, base64, recorded on the first connection.
     *
     * Trust on first use, exactly as any ssh client does: once this is set it
     * must match, so a different machine answering on that address is refused
     * rather than silently mirrored.
     */
    val sshHostKey: String? = null,
) {
    /**
     * The stable identity of a Target, and its adb serial in [TransportKind.Adb]
     * mode.
     *
     * `host:port` in both modes, so a device reached over SSH on 22 and the same
     * device reached over adb on its wireless-debugging port are two entries --
     * which is right: they need different things set up and can fail
     * independently.
     */
    val serial: String get() = "$host:$port"
}
