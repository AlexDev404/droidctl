package dev.alexdev404.droidctl.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alexdev404.droidctl.AppContainer
import dev.alexdev404.droidctl.LogBuffer
import dev.alexdev404.droidctl.scrcpy.ScrcpyProtocol
import dev.alexdev404.droidctl.session.SessionState

/**
 * The debug pane.
 *
 * Not optional: this project has a Host, a Target, a root shell, an adb tunnel,
 * two sockets, a server process and a hardware decoder in the path, and the
 * user cannot read logcat on the device they are holding. When mirroring "just
 * doesn't work", this screen is where the answer is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugPaneScreen(container: AppContainer, onBack: () -> Unit) {
    // The session exists from app start, whether or not any transport is set
    // up, so every one of these is a plain read. It used to be null until the
    // adb gate passed, which is what all the empty-flow fallbacks here were for.
    val session = container.mirrorSession
    val state by session.state.collectAsState()
    val stats by session.decoderStats.collectAsState()
    val serverOutput by session.serverOutput.collectAsState()
    val rawDump by session.rawDumpPath.collectAsState()
    val quality by session.quality.collectAsState()
    val linkMeasurement by session.linkMeasurement.collectAsState()
    val network by session.network.collectAsState()
    val targetDisplay by session.targetDisplay.collectAsState()
    val logLines by LogBuffer.tail.collectAsState()

    val logListState = rememberLazyListState()
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) logListState.scrollToItem(logLines.lastIndex)
    }

    val connection = when (val current = state) {
        is SessionState.Streaming -> current.connection
        is SessionState.AwaitingSockets -> current.connection
        is SessionState.StartingServer -> current.connection
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { LogBuffer.clear() }) { Text("Clear log") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text("Session", style = MaterialTheme.typography.titleMedium)
            Field("State", state.label)
            Field("scid", connection?.scid ?: "-")
            Field("Target serial", connection?.serial ?: "-")
            Field("Transport", connection?.transport?.label ?: "-")
            Field("Forwarded port", connection?.hostPort?.let { "127.0.0.1:$it" } ?: "-")
            Field("scrcpy version", ScrcpyProtocol.VERSION)
            Field("adb binary", container.binary?.path ?: "-")
            Field("Bit rate", connection?.options?.videoBitRate?.let { "$it bit/s" } ?: "-")
            rawDump?.let { Field("Raw dump", it) }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Text("Link", style = MaterialTheme.typography.titleMedium)
            Field("Quality", quality.label)
            Field("Target screen", targetDisplay?.toString() ?: "unknown")
            Field(
                "max_size",
                connection?.options?.maxSize?.let { if (it == 0) "uncapped" else "$it px" } ?: "-",
            )
            Field(
                "Measured at connect",
                linkMeasurement?.let {
                    "${it.bitsPerSecond / 1000} kbps ($it)" +
                        if (it.isMeaningful) "" else " — too brief to time"
                } ?: "-",
            )
            Field(
                "Video throughput",
                network?.let { "${it.throughputBitsPerSecond / 1000} kbps over ${it.spanMs} ms" } ?: "-",
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Text("Decoder", style = MaterialTheme.typography.titleMedium)
            Field("Codec", stats.codecName ?: "-")
            Field("Resolution", stats.resolution)
            Field("Frames decoded", stats.framesDecoded.toString())
            Field("Frames dropped", stats.framesDropped.toString())
            Field("FPS", "%.1f".format(stats.fps))
            Field("Decode latency", "%.1f ms".format(stats.decodeLatencyMs))
            Field("Bytes received", stats.bytesReceived.toString())

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Text("scrcpy server output", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                if (serverOutput.isEmpty()) {
                    item { Text("(nothing yet)", style = MaterialTheme.typography.bodySmall) }
                }
                items(serverOutput) { line ->
                    Text(
                        line.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (line.isError) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                }
            }

            HorizontalDivider()
            Text("DroidCtl log", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                state = logListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(logLines) { line ->
                    Text(
                        "${line.priorityChar} ${line.tag.removePrefix("DroidCtl/")}: ${line.message}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (line.priority >= android.util.Log.WARN) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color.Unspecified
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(140.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
