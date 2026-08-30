package dev.alexdev404.droidctl

import android.app.Application
import com.topjohnwu.superuser.Shell

class DroidCtlApplication : Application() {

    /** Built once here and handed down by constructor injection from [MainActivity]. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // One long-lived root shell for every adb invocation. FLAG_MOUNT_MASTER
        // keeps the shell in the global mount namespace so that /system/xbin
        // (where the adb-ndk module installs adb) is visible to it.
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(SHELL_TIMEOUT_SECONDS)
        )
        container = AppContainer(this)
    }

    private companion object {
        const val SHELL_TIMEOUT_SECONDS = 20L
    }
}
