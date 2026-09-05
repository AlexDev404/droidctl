package dev.alexdev404.droidctl.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.alexdev404.droidctl.AppContainer
import dev.alexdev404.droidctl.ui.common.MonospaceBlock
import dev.alexdev404.droidctl.ui.discovery.parseAddress
import kotlinx.coroutines.launch

/**
 * Pairing a new Target.
 *
 * The pairing port is *not* the connect port: Android advertises a separate,
 * short-lived pairing service while the "Pair device with pairing code" dialog
 * is open, and it disappears the moment that dialog is dismissed.
 *
 * The six-digit code is never logged, and the field is cleared as soon as
 * pairing finishes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(container: AppContainer, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session = container.mirrorSession
    val lastPair by container.preferences.lastManualPair.collectAsState(initial = "")
    // Remembered: rebuilding the flow on every recomposition would restart mDNS
    // discovery, and the pairing service only exists while the Target's dialog
    // is open.
    val pairingFlow = remember { container.discovery.pairingTargets() }
    val pairingTargets by pairingFlow.collectAsState(initial = emptyList())

    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(lastPair) { if (address.isEmpty()) address = lastPair }

    fun pair(host: String, port: Int) {
        busy = true
        status = null
        error = null
        scope.launch {
            container.preferences.setLastManualPair("$host:$port")
            session.pair(host, port, code)
                .onSuccess {
                    // Do not keep the code around a moment longer than needed.
                    code = ""
                    busy = false
                    status = "Paired with $host:$port. Now connect using the Target's " +
                        "wireless-debugging port (not this pairing port)."
                }
                .onFailure {
                    busy = false
                    error = it.message
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair a Target") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "On the Target, open Developer options > Wireless debugging > " +
                        "Pair device with pairing code. Enter the IP and port it shows, plus " +
                        "the six-digit code.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Pairing IP:port") },
                    placeholder = { Text("192.168.1.42:41234") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Pairing code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !busy && code.length == 6 && parseAddress(address) != null,
                    onClick = { parseAddress(address)?.let { (host, port) -> pair(host, port) } },
                ) { Text("Pair") }
            }

            status?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            error?.let {
                item {
                    Text("Pairing failed", style = MaterialTheme.typography.titleSmall)
                    MonospaceBlock(it)
                }
            }

            if (pairingTargets.isNotEmpty()) {
                item {
                    Text("Targets in pairing mode", style = MaterialTheme.typography.titleMedium)
                }
                items(pairingTargets, key = { it.serviceName }) { target ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(target.serviceName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (target.isResolved) "${target.host}:${target.port}" else "resolving...",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                enabled = target.isResolved,
                                onClick = { address = "${target.host}:${target.port}" },
                            ) { Text("Use this address") }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
