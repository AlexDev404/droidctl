package dev.alexdev404.droidctl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import dev.alexdev404.droidctl.AppContainer
import dev.alexdev404.droidctl.adb.AdbSetupFailure
import dev.alexdev404.droidctl.model.KnownTarget
import dev.alexdev404.droidctl.ui.discovery.ConnectScreen
import dev.alexdev404.droidctl.ui.mirror.MirrorScreen
import dev.alexdev404.droidctl.ui.pairing.PairingScreen
import dev.alexdev404.droidctl.ui.settings.DebugPaneScreen
import dev.alexdev404.droidctl.ui.settings.LicensesScreen
import dev.alexdev404.droidctl.ui.settings.SettingsScreen

/** Where the user is. Deliberately a plain enum: there are six screens. */
private sealed interface Screen {
    data object Gate : Screen
    data object Connect : Screen
    data object Pairing : Screen
    data class Mirror(val target: KnownTarget) : Screen
    data object Settings : Screen
    data object Licenses : Screen
    data object DebugPane : Screen
}

@Composable
fun DroidCtlApp(container: AppContainer) {
    var screen by remember { mutableStateOf<Screen>(Screen.Gate) }
    var setupFailure by remember { mutableStateOf<AdbSetupFailure?>(null) }
    var checking by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        checking = true
        val failure = container.initialize()
        setupFailure = failure
        checking = false
        if (failure == null) {
            // Forwards left behind by a crashed run would collide with this
            // run's ports; clear them before the user can start a session.
            container.mirrorSession?.clearStaleForwards()
            if (screen is Screen.Gate) screen = Screen.Connect
        }
    }

    when (val current = screen) {
        is Screen.Gate -> FirstRunGateScreen(
            checking = checking,
            failure = setupFailure,
            adbVersionProbe = { container.adbClient?.adbVersion()?.getOrNull() },
            onRetry = { attempt++ },
        )

        is Screen.Connect -> {
            val session = container.mirrorSession
            if (session == null) {
                // The gate has not passed yet (or root was revoked mid-session).
                LaunchedEffect(Unit) { screen = Screen.Gate }
            } else {
                ConnectScreen(
                    container = container,
                    session = session,
                    onMirror = { target -> screen = Screen.Mirror(target) },
                    onPair = { screen = Screen.Pairing },
                    onSettings = { screen = Screen.Settings },
                )
            }
        }

        is Screen.Pairing -> PairingScreen(
            container = container,
            onDone = { screen = Screen.Connect },
        )

        is Screen.Mirror -> {
            val session = container.mirrorSession
            val settings by container.preferences.settings.collectAsState(initial = null)
            when {
                session == null -> LaunchedEffect(Unit) { screen = Screen.Gate }
                // Starting a session with default settings and then discovering
                // the user's real ones a frame later would silently ignore their
                // bit rate and size, so wait for the first read.
                settings == null -> Unit
                else -> MirrorScreen(
                    session = session,
                    target = current.target,
                    settings = settings!!,
                    // Navigating away disposes MirrorScreen, whose DisposableEffect
                    // stops the session. Stopping here as well would queue a
                    // second teardown racing the next start.
                    onExit = { screen = Screen.Connect },
                    onDebugPane = { screen = Screen.DebugPane },
                )
            }
        }

        is Screen.Settings -> SettingsScreen(
            container = container,
            onBack = { screen = Screen.Connect },
            onLicenses = { screen = Screen.Licenses },
            onDebugPane = { screen = Screen.DebugPane },
        )

        is Screen.Licenses -> LicensesScreen(onBack = { screen = Screen.Settings })

        is Screen.DebugPane -> DebugPaneScreen(
            container = container,
            onBack = { screen = Screen.Settings },
        )
    }
}
