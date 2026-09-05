package dev.alexdev404.droidctl.transport

import android.content.Context
import dev.alexdev404.droidctl.DroidCtlLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The bundled abstract-socket relay, extracted from assets so it can be pushed.
 *
 * Built from `:relay` by `:droidctl:packageScrcpyServer`, the same task that
 * bundles the scrcpy server, so the jar in the APK always matches the sources
 * in this repository.
 *
 * No digest check, unlike [dev.alexdev404.droidctl.scrcpy.ScrcpyServerAsset]:
 * the server jar is checksummed because a truncated extraction shows up on the
 * Target as an opaque `ClassNotFoundException` inside `app_process` and because
 * the digest is what lets a push be skipped. Neither applies here -- the relay
 * is sixteen kilobytes, is re-sent every session, and announces itself by
 * printing its port, so a bad copy fails immediately and legibly.
 */
class RelayAsset(private val context: Context) {

    private val log = DroidCtlLog.server

    private val cachedJar: File get() = File(context.cacheDir, ASSET_NAME)

    suspend fun extract(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(ASSET_NAME).use { input ->
                cachedJar.outputStream().use { output -> input.copyTo(output) }
            }
            log.d("Extracted the relay (${cachedJar.length()} bytes)")
            cachedJar
        }
    }

    private companion object {
        const val ASSET_NAME = "droidctl-relay.jar"
    }
}
