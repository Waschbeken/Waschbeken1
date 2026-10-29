package de.waschbeken.heliolink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.waschbeken.heliolink.BuildConfig
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.update.ShizukuInstaller

/** Reconnects to the strap after a reboot or an update of the app. */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED && ShizukuInstaller.consumeSilentInstall(context)) {
            Notifications.installed(context, BuildConfig.VERSION_NAME.substringBefore("-"))
        }
        if (!context.app.prefs.isConfigured) return
        try {
            StrapService.start(context)
        } catch (_: Exception) {
            // Not allowed in this state (e.g. missing Bluetooth permission); the app starts it later.
        }
    }
}
