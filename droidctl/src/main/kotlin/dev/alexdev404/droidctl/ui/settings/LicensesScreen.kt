package dev.alexdev404.droidctl.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.alexdev404.droidctl.scrcpy.ScrcpyProtocol
import dev.alexdev404.droidctl.ui.common.MonospaceBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One bundled or linked third-party component. */
private data class Attribution(
    val name: String,
    val version: String,
    val copyright: String,
    val license: String,
    val url: String,
    /** Asset holding the full license text, when this component is redistributed. */
    val licenseAsset: String? = null,
)

/**
 * Open source licenses.
 *
 * scrcpy in particular is *redistributed*: the APK contains its server, so
 * Apache 2.0 requires the license text to travel with it. It ships at
 * `assets/licenses/scrcpy-LICENSE` and is shown in full here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var expandedAsset by remember { mutableStateOf<String?>(null) }
    var licenseText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(expandedAsset) {
        licenseText = expandedAsset?.let { asset ->
            withContext(Dispatchers.IO) {
                runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }
                    .getOrElse { "Could not read $asset: ${it.message}" }
            }
        }
    }

    val attributions = remember {
        listOf(
            Attribution(
                name = "scrcpy (server)",
                version = ScrcpyProtocol.VERSION,
                copyright = "Copyright (C) 2018 Genymobile, Copyright (C) 2018-2026 Romain Vimont",
                license = "Apache License 2.0",
                url = "https://github.com/Genymobile/scrcpy",
                licenseAsset = "licenses/scrcpy-LICENSE",
            ),
            Attribution(
                name = "libsu",
                version = "6.0.0",
                copyright = "Copyright John Wu (topjohnwu)",
                license = "Apache License 2.0",
                url = "https://github.com/topjohnwu/libsu",
            ),
            Attribution(
                name = "AndroidX (Core, Lifecycle, Activity, Compose, DataStore)",
                version = "see gradle/libs.versions.toml",
                copyright = "Copyright The Android Open Source Project",
                license = "Apache License 2.0",
                url = "https://developer.android.com/jetpack/androidx",
            ),
            Attribution(
                name = "Kotlin standard library and coroutines",
                version = "see gradle/libs.versions.toml",
                copyright = "Copyright JetBrains s.r.o.",
                license = "Apache License 2.0",
                url = "https://github.com/JetBrains/kotlin",
            ),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open source licenses") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "DroidCtl bundles an unmodified build of the scrcpy server. It is not " +
                        "modified in any way; it is compiled from the scrcpy sources in this " +
                        "repository and shipped as-is.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            items(attributions, key = { it.name }) { attribution ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "${attribution.name} ${attribution.version}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(attribution.copyright, style = MaterialTheme.typography.bodySmall)
                        Text(attribution.license, style = MaterialTheme.typography.bodySmall)
                        Text(attribution.url, style = MaterialTheme.typography.bodySmall)
                        attribution.licenseAsset?.let { asset ->
                            TextButton(
                                onClick = {
                                    expandedAsset = if (expandedAsset == asset) null else asset
                                }
                            ) {
                                Text(if (expandedAsset == asset) "Hide license text" else "Show license text")
                            }
                            if (expandedAsset == asset) {
                                MonospaceBlock(licenseText ?: "Loading...")
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
