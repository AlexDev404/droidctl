package dev.alexdev404.droidctl.session

import dev.alexdev404.droidctl.model.ConnectionInfo
import dev.alexdev404.droidctl.model.KnownTarget
import dev.alexdev404.droidctl.scrcpy.TargetMeta

/**
 * The mirroring lifecycle, as an explicit state machine.
 *
 * Every step that can fail is its own state so that a failure can say which one
 * it was: "could not connect" and "the server started but never opened its
 * socket" need completely different things from the user.
 */
sealed interface SessionState {

    /** Nothing is running. */
    data object Idle : SessionState

    /** `adb pair` is in flight. */
    data class Pairing(val target: String) : SessionState

    /** `adb connect` is in flight. */
    data class Connecting(val target: String) : SessionState

    /** Pushing `scrcpy-server.jar` to the Target. */
    data class PushingServer(val target: KnownTarget) : SessionState

    /** `app_process` has been launched; the server has not accepted yet. */
    data class StartingServer(val target: KnownTarget, val connection: ConnectionInfo) : SessionState

    /** Connecting the video and control sockets and reading the handshake. */
    data class AwaitingSockets(val target: KnownTarget, val connection: ConnectionInfo) : SessionState

    /** Video is flowing. */
    data class Streaming(
        val target: KnownTarget,
        val connection: ConnectionInfo,
        val meta: TargetMeta,
    ) : SessionState

    /** The session dropped and is being re-established. */
    data class Reconnecting(val target: KnownTarget, val attempt: Int, val reason: String) : SessionState

    /** The session ended cleanly. */
    data object Stopped : SessionState

    /**
     * The session failed.
     *
     * [serverOutput] carries whatever the scrcpy server printed. It is part of
     * the state rather than only the log because a server-side stack trace is
     * usually the *only* thing that explains the failure, and the user cannot
     * read logcat on the Host.
     */
    data class Failed(
        val stage: String,
        val message: String,
        val serverOutput: List<String> = emptyList(),
        val cause: Throwable? = null,
    ) : SessionState

    val isBusy: Boolean
        get() = this is Pairing || this is Connecting || this is PushingServer ||
            this is StartingServer || this is AwaitingSockets || this is Reconnecting

    val label: String
        get() = when (this) {
            is Idle -> "Idle"
            is Pairing -> "Pairing with $target"
            is Connecting -> "Connecting to $target"
            is PushingServer -> "Pushing scrcpy server"
            is StartingServer -> "Starting scrcpy server"
            is AwaitingSockets -> "Waiting for the scrcpy server"
            is Streaming -> "Streaming ${meta.width}x${meta.height}"
            is Reconnecting -> "Reconnecting (attempt $attempt)"
            is Stopped -> "Stopped"
            is Failed -> "Failed: $message"
        }
}
