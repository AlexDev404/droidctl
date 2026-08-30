package dev.alexdev404.droidctl.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.alexdev404.droidctl.AppContainer
import dev.alexdev404.droidctl.BuildConfig
import dev.alexdev404.droidctl.data.MirrorSettings
import dev.alexdev404.droidctl.debug.DebugSupport
import dev.alexdev404.droidctl.scrcpy.ScrcpyProtocol
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    onDebugPane: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings by container.preferences.settings.collectAsState(initial = MirrorSettings())

    fun update(transform: (MirrorSettings) -> MirrorSettings) {
        scope.launch { container.preferences.updateSettings(transform) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("Video", style = MaterialTheme.typography.titleMedium)
                NumberField(
                    label = "Max size (px, 0 = the Target's own size)",
                    value = settings.maxSize,
                    onValue = { value -> update { it.copy(maxSize = value) } },
                )
                NumberField(
                    label = "Video bit rate (bits/s)",
                    value = settings.videoBitRate,
                    onValue = { value -> update { it.copy(videoBitRate = value) } },
                )
                NumberField(
                    label = "Max FPS (0 = unlimited)",
                    value = settings.maxFps,
                    onValue = { value -> update { it.copy(maxFps = value) } },
                )
            }

            item {
                HorizontalDivider()
                Text("On the Target", style = MaterialTheme.typography.titleMedium)
                SwitchRow(
                    title = "Keep the Target awake",
                    subtitle = "Passes stay_awake=true to the scrcpy server.",
                    checked = settings.stayAwake,
                    onChange = { value -> update { it.copy(stayAwake = value) } },
                )
                SwitchRow(
                    title = "Show touches on the Target",
                    subtitle = "Draws the injected touches on the Target's own screen.",
                    checked = settings.showTouches,
                    onChange = { value -> update { it.copy(showTouches = value) } },
                )
            }

            item {
                HorizontalDivider()
                Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                SwitchRow(
                    title = "Raw-dump mode",
                    subtitle = "Writes the post-header payload stream to a .h264 file instead of " +
                        "decoding it. If the dump plays in ffplay, the sockets and framing are " +
                        "fine and the fault is in the decoder.",
                    checked = settings.rawDumpEnabled,
                    onChange = { value -> update { it.copy(rawDumpEnabled = value) } },
                )
                if (DebugSupport.isFakeServerAvailable) {
                    SwitchRow(
                        title = "Use the fake scrcpy server",
                        subtitle = "Debug builds only. Replays a recorded stream from a local " +
                            "socket so the video path can be exercised with no Target attached.",
                        checked = settings.useFakeServer,
                        onChange = { value -> update { it.copy(useFakeServer = value) } },
                    )
                }
                TextButton(onClick = onDebugPane) { Text("Open the debug pane") }
            }

            item {
                HorizontalDivider()
                Text("About", style = MaterialTheme.typography.titleMedium)
                Text(
                    "DroidCtl ${BuildConfig.VERSION_NAME}, bundling an unmodified scrcpy server " +
                        "${ScrcpyProtocol.VERSION} built from the sources in this repository.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onLicenses) { Text("Open source licenses") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onValue: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> onValue(text.filter(Char::isDigit).take(9).toIntOrNull() ?: 0) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
