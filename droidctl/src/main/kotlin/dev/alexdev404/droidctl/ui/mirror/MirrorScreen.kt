package dev.alexdev404.droidctl.ui.mirror

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.alexdev404.droidctl.AppContainer
import dev.alexdev404.droidctl.data.MirrorSettings
import dev.alexdev404.droidctl.input.KeyMapper
import dev.alexdev404.droidctl.model.KnownTarget
import dev.alexdev404.droidctl.session.MirrorSession
import dev.alexdev404.droidctl.session.SessionState
import dev.alexdev404.droidctl.ui.common.MonospaceBlock
import dev.alexdev404.droidctl.ui.settings.DebugPaneScreen

/**
 * The mirror.
 *
 * The `SurfaceView` fills the screen on a black background; the Target's video
 * is aspect-fitted inside it by the decoder, so the black bars are real
 * letterboxing and taps that land in them are discarded rather than clamped
 * (see `TouchMapper`).
 */
@Composable
fun MirrorScreen(
    container: AppContainer,
    session: MirrorSession,
    target: KnownTarget,
    settings: MirrorSettings,
    onExit: () -> Unit,
) {
    val state by session.state.collectAsState()
    val stats by session.decoderStats.collectAsState()
    val view = LocalView.current

    var controlsVisible by remember { mutableStateOf(false) }
    // An overlay rather than a destination of its own. Navigating away from the
    // mirror disposes this screen, and its DisposableEffect stops the session --
    // so opening the debug pane as a separate screen tore down the very session
    // it exists to explain.
    var debugVisible by remember { mutableStateOf(false) }
    var keyboardVisible by remember { mutableStateOf(false) }
    var batteryWarningShown by remember { mutableStateOf(false) }

    LaunchedEffect(target) {
        session.start(target, settings)
    }

    // A mirroring session is useless with the Host screen off, and the Host is
    // the device the user is holding.
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Full-screen immersive; the system bars come back with a swipe.
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    DisposableEffect(Unit) {
        onDispose { session.requestStop() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MirrorSurfaceView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    holder.addCallback(session.surfaceBridge)
                    onTouch = { event -> session.onTouchEvent(event) }
                }
            },
        )

        if (keyboardVisible) {
            HiddenTextInput(
                onText = { session.sendText(it) },
                onBackspace = { session.send(KeyMapper.press(android.view.KeyEvent.KEYCODE_DEL)) },
                onEnter = { session.send(KeyMapper.press(android.view.KeyEvent.KEYCODE_ENTER)) },
                onDismiss = { keyboardVisible = false },
            )
        }

        when (val current = state) {
            is SessionState.Failed -> FailureOverlay(current, onExit)
            is SessionState.Streaming -> Unit
            else -> ProgressOverlay(current.label, onExit)
        }

        if (!controlsVisible) {
            IconButton(
                onClick = { controlsVisible = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Icon(
                    Icons.Filled.UnfoldMore,
                    contentDescription = "Show controls",
                    tint = Color.White.copy(alpha = 0.6f),
                )
            }
        } else {
            ControlBar(
                statsLine = "${stats.resolution}  ${"%.0f".format(stats.fps)} fps  " +
                    "${"%.1f".format(stats.decodeLatencyMs)} ms  " +
                    "${stats.framesDropped} dropped",
                onHide = { controlsVisible = false },
                onBack = { session.send(KeyMapper.back()) },
                onHome = { session.send(KeyMapper.home()) },
                onRecents = { session.send(KeyMapper.recents()) },
                onPower = { session.send(KeyMapper.power()) },
                onRotate = { session.send(KeyMapper.rotate()) },
                onKeyboard = { keyboardVisible = !keyboardVisible },
                onDebug = { debugVisible = true },
                onExit = onExit,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (!batteryWarningShown && state is SessionState.Streaming) {
            BatteryNotice(onDismiss = { batteryWarningShown = true })
        }

        if (debugVisible) {
            Surface(Modifier.fillMaxSize()) {
                DebugPaneScreen(container = container, onBack = { debugVisible = false })
            }
        }
    }

    // Back closes the debug pane if it is open, and otherwise leaves the mirror.
    // Without this, back from the mirror screen exits the app outright.
    BackHandler(enabled = true) {
        if (debugVisible) debugVisible = false else onExit()
    }
}

@Composable
private fun ProgressOverlay(label: String, onCancel: () -> Unit) {
    Surface(
        color = Color.Black.copy(alpha = 0.75f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(label, color = Color.White)
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/**
 * A failed session, with the scrcpy server's own output.
 *
 * The server's stack trace is shown rather than only logged: it is almost
 * always the whole explanation, and the user cannot read logcat on the Host.
 */
@Composable
private fun FailureOverlay(state: SessionState.Failed, onExit: () -> Unit) {
    Surface(
        color = Color.Black.copy(alpha = 0.9f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Mirroring failed during ${state.stage}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(12.dp))
            MonospaceBlock(state.message)
            if (state.serverOutput.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("scrcpy server output", color = Color.White)
                Spacer(Modifier.height(8.dp))
                MonospaceBlock(state.serverOutput.takeLast(30).joinToString("\n"))
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onExit) { Text("Back") }
        }
    }
}

@Composable
private fun BatteryNotice(onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Mirroring keeps this device's screen on and decodes video continuously. " +
                    "Expect it to drain the battery quickly.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun ControlBar(
    statsLine: String,
    onHide: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit,
    onPower: () -> Unit,
    onRotate: () -> Unit,
    onKeyboard: () -> Unit,
    onDebug: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.7f),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                statsLine,
                color = Color.White.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlButton(Icons.AutoMirrored.Filled.ArrowBack, "Back on the Target", onBack)
                ControlButton(Icons.Filled.Home, "Home on the Target", onHome)
                ControlButton(Icons.Filled.Menu, "Recents on the Target", onRecents)
                ControlButton(Icons.Filled.PowerSettingsNew, "Power on the Target", onPower)
                ControlButton(Icons.Filled.ScreenRotation, "Rotate the Target", onRotate)
                ControlButton(Icons.Filled.Keyboard, "Type on the Target", onKeyboard)
                ControlButton(Icons.Filled.BugReport, "Debug pane", onDebug)
                ControlButton(Icons.Filled.Close, "Stop mirroring", onExit)
            }
            TextButton(onClick = onHide, modifier = Modifier.padding(start = 8.dp)) {
                Text("Hide controls", color = Color.White)
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(icon, contentDescription = description, tint = Color.White)
    }
}

/**
 * An off-screen `EditText` that exists only to collect IME input.
 *
 * Text goes to the Target as `INJECT_TEXT`, not as synthesized key events:
 * an IME produces characters that have no Android keycode at all (anything
 * accented, any non-Latin script, emoji), so per-character keycode synthesis
 * would silently drop most of what a user can type.
 */
@Composable
private fun HiddenTextInput(
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onDismiss: () -> Unit,
) {
    AndroidView(
        modifier = Modifier.size(1.dp),
        factory = { context ->
            android.widget.EditText(context).apply {
                // Invisible but focusable: it must be able to take IME focus.
                alpha = 0f
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isSingleLine = true
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                setOnEditorActionListener { _, _, _ ->
                    onEnter()
                    true
                }
                addTextChangedListener(object : android.text.TextWatcher {
                    private var previous = ""

                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                        previous = s?.toString().orEmpty()
                    }

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                    override fun afterTextChanged(s: android.text.Editable?) {
                        val current = s?.toString().orEmpty()
                        when {
                            current.length > previous.length && current.startsWith(previous) ->
                                onText(current.substring(previous.length))

                            current.length < previous.length ->
                                repeat(previous.length - current.length) { onBackspace() }

                            current != previous -> onText(current)
                        }
                        previous = current
                    }
                })
                requestFocus()
                val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onDismiss() }
            }
        },
        onRelease = { editText: View ->
            val imm = editText.context
                .getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(editText.windowToken, 0)
        },
    )
}
