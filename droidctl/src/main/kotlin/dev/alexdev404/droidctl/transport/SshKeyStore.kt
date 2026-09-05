package dev.alexdev404.droidctl.transport

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import dev.alexdev404.droidctl.DroidCtlLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The Host's SSH identity.
 *
 * Generated once on this device and never disclosed: the private key stays in
 * app-private storage and only the public half is ever shown, for the user to
 * paste into the Target's `authorized_keys`. That is why there is no "import a
 * key" path here -- a key the app generated cannot have been copied off another
 * machine along the way.
 */
class SshKeyStore(private val context: Context) {

    private val log = DroidCtlLog.adb

    private val keyFile: File get() = File(context.filesDir, "ssh/$PRIVATE_KEY_NAME")

    val exists: Boolean get() = keyFile.isFile

    /** Generates the key pair if there is not one already. */
    suspend fun ensureKeyPair(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (keyFile.isFile) return@runCatching
            keyFile.parentFile?.mkdirs()
            log.i("Generating an SSH key pair for this Host")
            val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, KEY_BITS)
            try {
                keyFile.outputStream().use { pair.writePrivateKey(it) }
                // Readable only by this app; the whole point of generating here.
                keyFile.setReadable(true, true)
                keyFile.setWritable(true, true)
                File(keyFile.parentFile, PUBLIC_KEY_NAME).outputStream().use {
                    pair.writePublicKey(it, COMMENT)
                }
            } finally {
                pair.dispose()
            }
        }
    }

    /** The private key, PEM encoded, for handing to jsch. */
    suspend fun privateKey(): Result<ByteArray> = withContext(Dispatchers.IO) {
        ensureKeyPair().mapCatching { keyFile.readBytes() }
    }

    /**
     * The public key as one `authorized_keys` line.
     *
     * This is what the user copies to the Target -- into
     * `/data/adb/ssh/shell/.ssh/authorized_keys`, or through MagiskSSH's own key
     * manager.
     */
    suspend fun publicKeyLine(): Result<String> = withContext(Dispatchers.IO) {
        ensureKeyPair().mapCatching {
            val file = File(keyFile.parentFile, PUBLIC_KEY_NAME)
            if (file.isFile) {
                file.readText().trim()
            } else {
                // Regenerating the public half from the private one, rather than
                // failing, keeps a half-written first run recoverable.
                val pair = KeyPair.load(JSch(), keyFile.absolutePath)
                try {
                    ByteArrayOutputStream()
                        .also { pair.writePublicKey(it, COMMENT) }
                        .toString(Charsets.UTF_8.name())
                        .trim()
                } finally {
                    pair.dispose()
                }
            }
        }
    }

    private companion object {
        const val PRIVATE_KEY_NAME = "droidctl_id_rsa"
        const val PUBLIC_KEY_NAME = "droidctl_id_rsa.pub"
        const val COMMENT = "droidctl"

        /**
         * RSA rather than ed25519: jsch can generate RSA with no extra
         * dependency, and 3072 bits is both fast enough to make on a phone and
         * accepted by every sshd worth connecting to.
         */
        const val KEY_BITS = 3072
    }
}
