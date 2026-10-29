package de.waschbeken.heliolink

import android.app.Application
import android.content.Context
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Prefs
import de.waschbeken.heliolink.service.StrapRepository

class HelioApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var db: HealthDb
        private set
    lateinit var repo: StrapRepository
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        db = HealthDb(this)
        repo = StrapRepository(prefs)
    }
}

val Context.app: HelioApp get() = applicationContext as HelioApp
