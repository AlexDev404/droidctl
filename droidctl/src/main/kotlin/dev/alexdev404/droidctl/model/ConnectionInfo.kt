package dev.alexdev404.droidctl.model

import dev.alexdev404.droidctl.scrcpy.ScrcpyOptions

/**
 * Everything that identifies one mirroring session, for the debug pane and for
 * teardown.
 */
data class ConnectionInfo(
    /** The Target's identity, i.e. `host:port`. */
    val serial: String,
    /** Which way this session reaches the Target. */
    val transport: TransportKind,
    /** The Host port the tunnel is listening on. */
    val hostPort: Int,
    /** The scrcpy session id, as it appears in the socket name and in every log line. */
    val socketName: String,
    val options: ScrcpyOptions,
) {
    val scid: String get() = socketName
}
