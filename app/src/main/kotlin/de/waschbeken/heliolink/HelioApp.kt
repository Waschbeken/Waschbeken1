package de.waschbeken.heliolink

import android.app.Application
import android.content.Context
import de.waschbeken.heliolink.data.CrashReporter
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Prefs
import de.waschbeken.heliolink.service.StrapRepository
import de.waschbeken.heliolink.update.AppUpdater
import de.waschbeken.heliolink.update.UpdateManager

class HelioApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var db: HealthDb
        private set
    lateinit var repo: StrapRepository
        private set
    lateinit var updater: AppUpdater
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        prefs = Prefs(this)
        db = HealthDb(this)
        repo = StrapRepository(prefs)
        updater = AppUpdater(
            UpdateManager(this, prefs),
            skipped = { prefs.skippedVersion },
            skip = { v ->
                prefs.skippedVersion = v
                prefs.notifiedUpdateVersion = ""
            },
            useShizuku = { prefs.shizukuInstall },
            beforeInstall = { if (prefs.autoBackupBeforeUpdate) de.waschbeken.heliolink.data.AutoBackup.run(this) },
        )
    }
}

val Context.app: HelioApp get() = applicationContext as HelioApp

/** Whether an activity of the app is visible (silent updates only happen in the background). */
object AppVisibility {
    @Volatile
    var inForeground = false
}
