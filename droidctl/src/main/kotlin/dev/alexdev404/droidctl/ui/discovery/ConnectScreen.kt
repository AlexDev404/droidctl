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
import androidx.compose.material3.FilterChip
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
import dev.alexdev404.droidctl.model.TransportKind
import dev.alexdev404.droidctl.session.MirrorSession
import dev.alexdev404.droidctl.transport.SshCredentials
import dev.alexdev404.droidctl.ui.common.MonospaceBlock
import dev.alexdev404.droidctl.ui.common.SshIdentityCard
import kotlinx.coroutines.launch

/**
 * Choosing a Target.
 *
 * Discovered and manual entry are both first-class: mDNS comes up empty on
 * plenty of real networks (client isolation, some OEM Wi-Fi stacks), and a
 * manual path hidden behind a discovery failure would be found only by users
 * who already know it exists.
 *
 * [transport] decides which half of the screen is shown. The two modes reach a
 * device on different ports with different prerequisites, so mixing their forms
 * would mostly produce connections to the wrong port; saved Targets keep the
 * mode they were added with and are always listed, so switching the toggle
 * never hides one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    container: AppContainer,
    session: MirrorSession,
    transport: TransportKind,
    onMirror: (KnownTarget) -> Unit,
    onPair: () -> Unit,
    onSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val known by container.preferences.knownTargets.collectAsState(initial = emptyList())

    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    fun mode(next: TransportKind) {
        error = null
        scope.launch { container.preferences.updateSettings { it.copy(transport = next) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect a Target") },
                actions = {
                    if (transport == TransportKind.Adb) {
                        IconButton(onClick = { refresh++ }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        // Item keys are namespaced per section because LazyColumn requires them
        // to be unique across the whole list, not per items() block. A Target
        // you have connected to appears both under "Known Targets" and under
        // "adb devices" with the same serial -- that is the normal case, not an
        // edge case, and a bare serial as the key crashes the list outright.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionHeader("Connection mode")
                TransportPicker(selected = transport, onSelect = ::mode)
            }

            when (transport) {
                TransportKind.Adb -> adbSection(
                    container = container,
                    session = session,
                    busy = busy,
                    refresh = refresh,
                    onBusy = { busy = it },
                    onError = { error = it },
                    onMirror = onMirror,
                    onPair = onPair,
                    scope = scope,
                )

                TransportKind.Ssh -> sshSection(
                    container = container,
                    session = session,
                    busy = busy,
                    onBusy = { busy = it },
                    onError = { error = it },
                    onMirror = onMirror,
                    scope = scope,
                )
            }

            error?.let { message ->
                item {
                    SectionHeader("Last error")
                    MonospaceBlock(message)
                }
            }

            if (known.isNotEmpty()) {
                item { SectionHeader("Known Targets") }
                items(known, key = { "known:${it.serial}" }) { target ->
                    KnownTargetCard(
                        target = target,
                        enabled = !busy,
                        onConnect = {
                            busy = true
                            error = null
                            scope.launch {
                                reconnect(session, target)
                                    .onSuccess {
                                        busy = false
                                        onMirror(it)
                                    }
                                    .onFailure {
                                        busy = false
                                        error = it.message
                                    }
                            }
                        },
                        onForget = { scope.launch { container.preferences.forgetTarget(target) } },
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * Re-establishes the link to a saved Target before mirroring it.
 *
 * Only ADB has anything to do here: `adb connect` has to succeed before a
 * serial means anything, and its failures (unauthorized, offline, a
 * wireless-debugging port that changed on reboot) are worth reporting on this
 * screen rather than as a mirroring failure two states later. An SSH Target
 * has no such step -- the session opens its own connection when it starts --
 * so it goes straight through.
 */
private suspend fun reconnect(session: MirrorSession, target: KnownTarget): Result<KnownTarget> =
    when (target.transport) {
        TransportKind.Adb -> session.connectTarget(target.host, target.port, target.name)
        TransportKind.Ssh -> Result.success(target)
    }

/** The adb half: manual address, pairing, mDNS discovery and `adb devices`. */
private fun androidx.compose.foundation.lazy.LazyListScope.adbSection(
    container: AppContainer,
    session: MirrorSession,
    busy: Boolean,
    refresh: Int,
    onBusy: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onMirror: (KnownTarget) -> Unit,
    onPair: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    item {
        val lastManual by container.preferences.lastManualConnect.collectAsState(initial = "")
        // Remembered: rebuilding the flow on every recomposition would restart
        // mDNS discovery, and NsdManager takes seconds to find anything.
        val discoveryFlow = remember { container.discovery.connectableTargets() }
        val discovered by discoveryFlow.collectAsState(initial = emptyList())
        var manualAddress by remember { mutableStateOf("") }
        var devices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) }

        LaunchedEffect(lastManual) {
            if (manualAddress.isEmpty()) manualAddress = lastManual
        }
        LaunchedEffect(refresh) {
            devices = container.adbClient?.devices()?.getOrDefault(emptyList()) ?: emptyList()
        }

        fun connect(host: String, port: Int, name: String?) {
            onBusy(true)
            onError(null)
            scope.launch {
                container.preferences.setLastManualConnect("$host:$port")
                session.connectTarget(host, port, name)
                    .onSuccess {
                        onBusy(false)
                        onMirror(it)
                    }
                    .onFailure {
                        onBusy(false)
                        onError(it.message)
                    }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Manual")
            Text(
                "Enter the Target's IP and wireless-debugging port, shown under " +
                    "Developer options > Wireless debugging.",
                style = MaterialTheme.typography.bodySmall,
            )
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
            TextButton(onClick = onPair) { Text("Pair a new Target...") }

            SectionHeader("Discovered (mDNS)")
            if (discovered.isEmpty()) {
                Text(
                    "Nothing found yet. Many networks block mDNS between clients; use the " +
                        "manual field above if this stays empty.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                for (target in discovered) {
                    DiscoveredTargetCard(
                        target = target,
                        enabled = !busy,
                        onConnect = {
                            if (target.host != null) {
                                connect(target.host, target.port, target.serviceName)
                            }
                        },
                    )
                }
            }

            SectionHeader("adb devices")
            if (devices.isEmpty()) {
                Text("No devices attached.", style = MaterialTheme.typography.bodySmall)
            } else {
                for (device in devices) AdbDeviceCard(device)
            }
        }
    }
}

/** The SSH half: an address, an account, and the key to install on the Target. */
private fun androidx.compose.foundation.lazy.LazyListScope.sshSection(
    container: AppContainer,
    session: MirrorSession,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onMirror: (KnownTarget) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    item {
        val lastAddress by container.preferences.lastSshConnect.collectAsState(initial = "")
        val lastUser by container.preferences.lastSshUser.collectAsState(initial = "")
        var address by remember { mutableStateOf("") }
        var user by remember { mutableStateOf("") }

        LaunchedEffect(lastAddress) { if (address.isEmpty()) address = lastAddress }
        LaunchedEffect(lastUser) { if (user.isEmpty()) user = lastUser }

        val account = user.trim().ifBlank { SshCredentials.DEFAULT_USER }
        // A bare host means the default SSH port, which is what people type.
        val parsed = parseAddress(address) ?: address.trim()
            .takeIf { it.isNotBlank() && !it.contains(':') }
            ?.let { it to SshCredentials.DEFAULT_PORT }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("SSH")
            Text(
                "The Target must be rooted and running an sshd (for example MagiskSSH). " +
                    "This device needs nothing at all.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Target address") },
                singleLine = true,
                placeholder = { Text("192.168.1.42:22") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Account") },
                    singleLine = true,
                    placeholder = { Text(SshCredentials.DEFAULT_USER) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    enabled = !busy && parsed != null,
                    onClick = {
                        val (host, port) = parsed ?: return@Button
                        onBusy(true)
                        onError(null)
                        scope.launch {
                            container.preferences.setLastSshConnect("$host:$port", account)
                            session.connectSshTarget(host, port, account, null)
                                .onSuccess {
                                    onBusy(false)
                                    onMirror(it)
                                }
                                .onFailure {
                                    onBusy(false)
                                    onError(it.message)
                                }
                        }
                    },
                ) { Text("Connect") }
            }
            Text(
                "Logging in as $account puts the scrcpy server at the same privilege " +
                    "`adb shell` gives it, which is what it is built for.",
                style = MaterialTheme.typography.bodySmall,
            )

            SshIdentityCard(keys = container.sshKeys, user = account)
        }
    }
}

@Composable
private fun TransportPicker(selected: TransportKind, onSelect: (TransportKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (kind in TransportKind.entries) {
            FilterChip(
                selected = kind == selected,
                onClick = { onSelect(kind) },
                label = { Text(kind.label) },
            )
        }
    }
    Text(
        when (selected) {
            TransportKind.Adb -> "This device must be rooted and have the adb-ndk module."
            TransportKind.Ssh -> "The Target must be rooted and running an sshd."
        },
        style = MaterialTheme.typography.bodySmall,
    )
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
            Text(
                // The mode is part of a saved Target's identity, not a detail:
                // two entries for one device differ only by it and by the port.
                "${target.transport.label}  ${target.serial}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
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
