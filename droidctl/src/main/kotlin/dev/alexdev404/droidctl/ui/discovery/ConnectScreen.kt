package dev.alexdev404.droidctl.ui.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import dev.alexdev404.droidctl.AppContainer
import dev.alexdev404.droidctl.adb.AdbDevice
import dev.alexdev404.droidctl.model.DiscoveredTarget
import dev.alexdev404.droidctl.model.DiscoveryKind
import dev.alexdev404.droidctl.model.KnownTarget
import dev.alexdev404.droidctl.session.MirrorSession
import dev.alexdev404.droidctl.ui.common.MonospaceBlock
import kotlinx.coroutines.launch

/**
 * Choosing a Target.
 *
 * Discovered and manual entry are both first-class: mDNS comes up empty on
 * plenty of real networks (client isolation, some OEM Wi-Fi stacks), and a
 * manual path hidden behind a discovery failure would be found only by users
 * who already know it exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    container: AppContainer,
    session: MirrorSession,
    onMirror: (KnownTarget) -> Unit,
    onPair: () -> Unit,
    onSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val known by container.preferences.knownTargets.collectAsState(initial = emptyList())
    val lastManual by container.preferences.lastManualConnect.collectAsState(initial = "")
    // Remembered: rebuilding the flow on every recomposition would restart mDNS
    // discovery, and NsdManager takes seconds to find anything.
    val discoveryFlow = remember { container.discovery.connectableTargets() }
    val discovered by discoveryFlow.collectAsState(initial = emptyList())

    var manualAddress by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(lastManual) {
        if (manualAddress.isEmpty()) manualAddress = lastManual
    }
    LaunchedEffect(refresh) {
        devices = container.adbClient?.devices()?.getOrDefault(emptyList()) ?: emptyList()
    }

    fun connect(host: String, port: Int, name: String?) {
        busy = true
        error = null
        scope.launch {
            container.preferences.setLastManualConnect("$host:$port")
            session.connectTarget(host, port, name)
                .onSuccess { target ->
                    busy = false
                    refresh++
                    onMirror(target)
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
                title = { Text("Connect a Target") },
                actions = {
                    IconButton(onClick = { refresh++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
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
                SectionHeader("Manual")
                Text(
                    "Enter the Target's IP and wireless-debugging port, shown under " +
                        "Developer options > Wireless debugging.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualAddress,
                        onValueChange = { manualAddress = it },
                        label = { Text("IP:port") },
                        singleLine = true,
                        placeholder = { Text("192.168.1.42:37129") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(
                        enabled = !busy && parseAddress(manualAddress) != null,
                        onClick = {
                            parseAddress(manualAddress)?.let { (host, port) ->
                                connect(host, port, null)
                            }
                        },
                    ) { Text("Connect") }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onPair) { Text("Pair a new Target...") }
            }

            error?.let { message ->
                item {
                    SectionHeader("Last error")
                    MonospaceBlock(message)
                }
            }

            if (known.isNotEmpty()) {
                item { SectionHeader("Known Targets") }
                items(known, key = { it.serial }) { target ->
                    KnownTargetCard(
                        target = target,
                        enabled = !busy,
                        onConnect = { connect(target.host, target.port, target.name) },
                        onForget = { scope.launch { container.preferences.forgetTarget(target) } },
                    )
                }
            }

            item { SectionHeader("Discovered (mDNS)") }
            if (discovered.isEmpty()) {
                item {
                    Text(
                        "Nothing found yet. Many networks block mDNS between clients; use the " +
                            "manual field above if this stays empty.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                items(discovered, key = { it.serviceName }) { target ->
                    DiscoveredTargetCard(
                        target = target,
                        enabled = !busy,
                        onConnect = {
                            if (target.host != null) connect(target.host, target.port, target.serviceName)
                        },
                    )
                }
            }

            item { SectionHeader("adb devices") }
            if (devices.isEmpty()) {
                item { Text("No devices attached.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(devices, key = { it.serial }) { device -> AdbDeviceCard(device) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun KnownTargetCard(
    target: KnownTarget,
    enabled: Boolean,
    onConnect: () -> Unit,
    onForget: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(target.name, style = MaterialTheme.typography.titleSmall)
            Text(target.serial, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = onConnect, enabled = enabled) { Text("Mirror") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onForget) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun DiscoveredTargetCard(
    target: DiscoveredTarget,
    enabled: Boolean,
    onConnect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(target.serviceName, style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    target.isResolved -> "${target.host}:${target.port}"
                    else -> "resolving..."
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (target.kind == DiscoveryKind.Pairing) {
                Text(
                    "This Target is in pairing mode; pair it first.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onConnect, enabled = enabled && target.isResolved) { Text("Mirror") }
        }
    }
}

@Composable
private fun AdbDeviceCard(device: AdbDevice) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(device.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                "${device.serial}  ${device.state.name.lowercase()}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            device.remediation?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Splits `host:port`, returning null when it is not a usable address. */
internal fun parseAddress(raw: String): Pair<String, Int>? {
    val trimmed = raw.trim()
    val separator = trimmed.lastIndexOf(':')
    if (separator <= 0 || separator == trimmed.lastIndex) return null
    val port = trimmed.substring(separator + 1).toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return trimmed.substring(0, separator) to port
}
