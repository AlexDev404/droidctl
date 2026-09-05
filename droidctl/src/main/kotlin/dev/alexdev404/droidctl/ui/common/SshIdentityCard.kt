package dev.alexdev404.droidctl.ui.common

import android.content.ClipData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import dev.alexdev404.droidctl.transport.SshCredentials
import dev.alexdev404.droidctl.transport.SshKeyStore
import kotlinx.coroutines.launch

/**
 * The Host's SSH public key, with the one instruction that goes with it.
 *
 * Shown rather than hidden behind a "set up SSH" flow because pasting this line
 * into the Target is the *only* manual step SSH mode has, and a key the user
 * cannot see is a key they cannot install.
 *
 * There is no import path on purpose: the private half is generated here and
 * never leaves app-private storage, which is a guarantee an imported key could
 * not make.
 */
@Composable
fun SshIdentityCard(keys: SshKeyStore, user: String = SshCredentials.DEFAULT_USER) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var publicKey by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Generating a 3072-bit key takes a moment on a phone, so it happens here,
    // once, rather than on the connect button where it would look like a stall.
    LaunchedEffect(Unit) {
        keys.publicKeyLine()
            .onSuccess { publicKey = it }
            .onFailure { error = it.message ?: "Could not generate an SSH key" }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("This Host's SSH key", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Add this line to the Target, in /data/adb/ssh/$user/.ssh/authorized_keys or " +
                    "through MagiskSSH's key manager. Until it is there, every connection is " +
                    "refused for the same reason: the Target does not know this key.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            when {
                error != null -> MonospaceBlock(error!!)
                publicKey == null -> Text(
                    "Generating a key pair...",
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> {
                    MonospaceBlock(publicKey!!)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        OutlinedButton(onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText(CLIP_LABEL, publicKey))
                                )
                            }
                        }) { Text("Copy") }
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        }
    }
}

private const val CLIP_LABEL = "DroidCtl SSH public key"
