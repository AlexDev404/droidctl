package dev.alexdev404.droidctl.debug

import android.content.Context

/**
 * Release build: there is no fake server.
 *
 * The debug variant provides a different implementation of this same object
 * (see `src/debug`), so nothing in `src/main` needs a build-type check beyond
 * [isFakeServerAvailable].
 */
object DebugSupport {
    const val isFakeServerAvailable = false

    fun startFakeServer(context: Context): FakeServerEndpoint =
        throw UnsupportedOperationException("The fake scrcpy server ships in debug builds only")
}
