package dev.alexdev404.droidctl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.alexdev404.droidctl.adb.AdbBinary
import dev.alexdev404.droidctl.adb.AdbSetupFailure
import dev.alexdev404.droidctl.ui.common.MonospaceBlock

/**
 * The first-run gate.
 *
 * Checks root, then the adb binary, then that adb actually runs, and blocks on
 * the first failure with the remediation for exactly that failure. The one
 * outcome this screen must never produce is an empty screen with no
 * explanation.
 */
@Composable
fun FirstRunGateScreen(
    checking: Boolean,
    failure: AdbSetupFailure?,
    adbVersionProbe: suspend () -> String?,
    onRetry: () -> Unit,
) {
    var version by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(checking, failure) {
        if (!checking && failure == null) version = adbVersionProbe()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("DroidCtl", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Mirror and control a second Android device over wireless ADB.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            when {
                checking -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Checking root and adb on this device (the Host)...")
                }

                failure == null -> {
                    Text("Ready.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    version?.let { MonospaceBlock(it) }
                }

                else -> GateFailure(failure = failure, onRetry = onRetry)
            }
        }
    }
}

@Composable
private fun GateFailure(failure: AdbSetupFailure, onRetry: () -> Unit) {
    val (title, explanation, detail) = when (failure) {
        is AdbSetupFailure.NoRoot -> Triple(
            "This app requires root",
            "DroidCtl runs adb as root on this device (the Host). Install Magisk and grant " +
                "DroidCtl superuser access, then try again.",
            null,
        )

        is AdbSetupFailure.BinaryNotFound -> Triple(
            "No adb binary found",
            "DroidCtl needs the adb-ndk Magisk module, which installs a static adb at " +
                "${AdbBinary.ADB_NDK_PATH}. Install it from ${AdbBinary.ADB_NDK_URL} and reboot.",
            "Searched: " + failure.searched.joinToString(", "),
        )

        is AdbSetupFailure.VersionCheckFailed -> Triple(
            "adb could not run",
            "An adb binary exists at ${failure.path} but it failed to run. Its own output is below.",
            failure.stderr,
        )
    }

    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(explanation, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    detail?.let {
        Spacer(Modifier.height(16.dp))
        MonospaceBlock(it)
    }
    Spacer(Modifier.height(24.dp))
    Button(onClick = onRetry, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp)) {
        Text("Try again")
    }
}
