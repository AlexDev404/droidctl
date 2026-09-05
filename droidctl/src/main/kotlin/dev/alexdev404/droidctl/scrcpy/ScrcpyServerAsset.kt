package dev.alexdev404.droidctl.scrcpy

import android.content.Context
import dev.alexdev404.droidctl.DroidCtlLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The scrcpy server jar, as [ScrcpyLauncher] needs it.
 *
 * An interface purely so the launcher can be exercised without an Android
 * `Context`: what it does with the jar -- skip the push when the Target's copy
 * already matches, time the push when it does not -- is transport-independent
 * logic worth testing on the JVM. [ScrcpyServerAsset] is the only
 * implementation that ships.
 */
interface ServerJar {
    /** The SHA-256 of the jar, as computed at build time. */
    suspend fun expectedSha256(): String

    /** The jar as a file on the Host, ready to push. */
    suspend fun extract(): Result<File>
}

/**
 * The bundled scrcpy server: extract it from assets, check it, push it to the
 * Target.
 *
 * The jar is not vendored as a binary in git. `:droidctl:packageScrcpyServer`
 * builds it from the scrcpy server sources that live in this same repository
 * and drops it into the APK's assets, which is what lets DroidCtl claim the
 * protocol it implements matches the server it ships (see `docs/PROTOCOL.md`).
 *
 * The jar stays unmodified upstream code under the Apache License 2.0; the
 * license text ships alongside it at `assets/licenses/scrcpy-LICENSE` and is
 * shown by the in-app licenses screen.
 */
class ScrcpyServerAsset(private val context: Context) : ServerJar {

    private val log = DroidCtlLog.server

    /** Where the jar is cached on the Host once extracted from assets. */
    private val cachedJar: File get() = File(context.cacheDir, ASSET_NAME)

    override suspend fun expectedSha256(): String = withContext(Dispatchers.IO) {
        context.assets.open(SHA256_ASSET_NAME)
            .use { it.readBytes() }
            .toString(Charsets.UTF_8)
            .trim()
            .lowercase()
    }

    /**
     * Extracts the jar to the Host's cache directory and verifies its SHA-256.
     *
     * The digest is the one computed at build time and shipped next to the jar.
     * It does not defend against a tampered APK -- both come from the same
     * build -- but it does catch a truncated or partially written extraction,
     * which otherwise surfaces on the Target as an opaque `ClassNotFoundException`
     * inside `app_process`.
     */
    override suspend fun extract(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val expected = expectedSha256()

            if (cachedJar.isFile && sha256(cachedJar) == expected) {
                log.d("Reusing extracted scrcpy server at ${cachedJar.absolutePath}")
                return@runCatching cachedJar
            }

            context.assets.open(ASSET_NAME).use { input ->
                cachedJar.outputStream().use { output -> input.copyTo(output) }
            }
            val actual = sha256(cachedJar)
            if (actual != expected) {
                cachedJar.delete()
                throw IOException(
                    "Extracted scrcpy server is corrupt: expected SHA-256 $expected but got $actual"
                )
            }
            log.i("Extracted scrcpy server ${ScrcpyProtocol.VERSION} (${cachedJar.length()} bytes)")
            cachedJar
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ASSET_NAME = "scrcpy-server.jar"
        const val SHA256_ASSET_NAME = "scrcpy-server.jar.sha256"
    }
}
