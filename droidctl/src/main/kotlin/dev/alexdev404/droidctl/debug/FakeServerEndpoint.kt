package dev.alexdev404.droidctl.debug

import java.io.Closeable

/**
 * A locally hosted stand-in for the scrcpy server on a Target.
 *
 * Implemented only in the debug variant (see `src/debug` and `src/release`
 * versions of `DebugSupport`). It speaks the real handshake and the real frame
 * framing over a loopback socket, which is what lets the sockets, the framing,
 * the decoder and the surface all be exercised on a single device with no
 * Target present.
 */
interface FakeServerEndpoint : Closeable {
    /** The loopback port the session should connect to, in place of an adb forward. */
    val port: Int

    /** Lines the fake server "printed", so the debug pane looks the same. */
    fun log(): List<String>
}
