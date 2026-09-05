package dev.alexdev404.droidctl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.alexdev404.droidctl.ui.DroidCtlApp
import dev.alexdev404.droidctl.ui.common.DroidCtlTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as DroidCtlApplication).container
        setContent {
            DroidCtlTheme {
                DroidCtlApp(container = container)
            }
        }
    }
}
