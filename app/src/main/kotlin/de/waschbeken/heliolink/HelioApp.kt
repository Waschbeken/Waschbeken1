package de.waschbeken.heliolink

import android.app.Application
import android.content.Context
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
        )
    }
}

val Context.app: HelioApp get() = applicationContext as HelioApp
