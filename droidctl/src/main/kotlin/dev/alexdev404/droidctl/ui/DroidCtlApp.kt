package dev.alexdev404.droidctl.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import dev.alexdev404.droidctl.AppContainer
import dev.alexdev404.droidctl.adb.AdbSetupFailure
import dev.alexdev404.droidctl.model.KnownTarget
import dev.alexdev404.droidctl.model.TransportKind
import dev.alexdev404.droidctl.ui.discovery.ConnectScreen
import dev.alexdev404.droidctl.ui.mirror.MirrorScreen
import dev.alexdev404.droidctl.ui.pairing.PairingScreen
import dev.alexdev404.droidctl.ui.settings.DebugPaneScreen
import dev.alexdev404.droidctl.ui.settings.LicensesScreen
import dev.alexdev404.droidctl.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Gate) }
    var setupFailure by remember { mutableStateOf<AdbSetupFailure?>(null) }
    var checking by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }

    // Null until the first read; the gate to run depends on it, so running one
    // before it lands would flash the wrong screen on every cold start.
    val settings by container.preferences.settings.collectAsState(initial = null)
    val mode = settings?.transport

    LaunchedEffect(attempt, mode) {
        val transport = mode ?: return@LaunchedEffect
        if (transport == TransportKind.Ssh) {
            // Nothing to gate: SSH mode asks nothing of the Host -- no root, no
            // adb binary. The device that has to be rooted is the Target, and
            // that only shows up when a connection is attempted.
            checking = false
            setupFailure = null
            if (screen is Screen.Gate) screen = Screen.Connect
            return@LaunchedEffect
        }

        checking = true
        val failure = container.initializeAdb()
        setupFailure = failure
        checking = false
        if (failure == null) {
            // Forwards left behind by a crashed run would collide with this
            // run's ports; clear them before the user can start a session.
            container.mirrorSession.clearStaleForwards()
            if (screen is Screen.Gate) screen = Screen.Connect
        } else if (screen !is Screen.Mirror) {
            // Switching to ADB mode on a Host that cannot run adb has to say so
            // rather than leave the user on a Connect screen whose every button
            // fails. A live mirror is left alone: it is running over whatever
            // transport it started on.
            screen = Screen.Gate
        }
    }

    // Back moves up the hierarchy rather than out of the app. The mirror screen
    // handles its own, because the debug pane there is an overlay it owns.
    BackHandler(enabled = screen !is Screen.Connect && screen !is Screen.Mirror) {
        screen = when (screen) {
            is Screen.Licenses -> Screen.Settings
            is Screen.DebugPane -> Screen.Settings
            is Screen.Settings -> Screen.Connect
            is Screen.Pairing -> Screen.Connect
            else -> Screen.Connect
        }
    }

    when (val current = screen) {
        is Screen.Gate -> FirstRunGateScreen(
            checking = checking,
            failure = setupFailure,
            adbVersionProbe = { container.adbClient?.adbVersion()?.getOrNull() },
            onRetry = { attempt++ },
            onUseSsh = {
                scope.launch {
                    container.preferences.updateSettings { it.copy(transport = TransportKind.Ssh) }
                }
            },
        )

        is Screen.Connect -> ConnectScreen(
            container = container,
            session = container.mirrorSession,
            transport = mode ?: TransportKind.Adb,
            onMirror = { target -> screen = Screen.Mirror(target) },
            onPair = { screen = Screen.Pairing },
            onSettings = { screen = Screen.Settings },
        )

        is Screen.Pairing -> PairingScreen(
            container = container,
            onDone = { screen = Screen.Connect },
        )

        is Screen.Mirror -> {
            // Starting a session with default settings and then discovering the
            // user's real ones a frame later would silently ignore their bit
            // rate and size, so wait for the first read.
            settings?.let { activeSettings ->
                MirrorScreen(
                    container = container,
                    session = container.mirrorSession,
                    target = current.target,
                    settings = activeSettings,
                    // Navigating away disposes MirrorScreen, whose DisposableEffect
                    // stops the session. Stopping here as well would queue a
                    // second teardown racing the next start.
                    onExit = { screen = Screen.Connect },
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
