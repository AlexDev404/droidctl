package dev.alexdev404.droidctl.debug

import android.content.Context

/**
 * Debug build: the fake scrcpy server is available.
 *
 * The release variant provides a different implementation of this same object
 * (see `src/release`), so `src/main` needs no build-type checks of its own.
 */
object DebugSupport {
    const val isFakeServerAvailable = true

    fun startFakeServer(context: Context): FakeServerEndpoint = FakeScrcpyServer(context).apply { start() }
}
