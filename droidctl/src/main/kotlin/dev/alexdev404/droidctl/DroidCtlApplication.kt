package dev.alexdev404.droidctl

import android.app.Application
import com.topjohnwu.superuser.Shell
import dev.alexdev404.droidctl.adb.RootShellSession

class DroidCtlApplication : Application() {

    /** Built once here and handed down by constructor injection from [MainActivity]. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // One long-lived root shell for every adb invocation, built with the
        // flags every other shell in the app uses too (see SHELL_FLAGS).
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(RootShellSession.SHELL_FLAGS)
                .setTimeout(RootShellSession.SHELL_TIMEOUT_SECONDS)
        )
        container = AppContainer(this)
    }
}
