package de.waschbeken.heliolink.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.HealthSetting
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.service.StrapUiState
import de.waschbeken.heliolink.ui.dbValue
import de.waschbeken.heliolink.ui.formatMinutes

@Composable
internal fun RecordingPage(state: StrapUiState) {
    val prefs = LocalContext.current.app.prefs
    prefsTick()
    SettingsHeader("Puls durch die App aufzeichnen")
    SettingsNote(
        "Der Strap selbst speichert den Puls höchstens einmal pro Minute. Solange er mit dem Handy verbunden ist, " +
            "kann HelioLink zusätzlich die Live-Werte aufzeichnen – bis zu jede Sekunde. Die Werte erscheinen in der " +
            "Tageskurve und im Export (puls_app.csv).",
    )
    ChoiceSetting(
        "Aufzeichnen",
        listOf(0 to "Aus", 1 to "Jede Sekunde", 5 to "Alle 5 Sekunden", 10 to "Alle 10 Sekunden", 30 to "Alle 30 Sekunden"),
        prefs.continuousHrSeconds,
        subtitle = if (prefs.continuousHrSeconds > 0) "läuft, solange der Strap verbunden ist" else null,
    ) { prefs.continuousHrSeconds = it }
    SettingsNote(
        "Nebenbei berechnet die App jede Minute die HRV aus den Herzschlag-Abständen – so gibt es HRV-Werte auch tagsüber. " +
            "Achtung Akku: Die Live-Messung hält den Pulssensor dauerhaft an. Der Strap hält dann statt bis zu 10 Tagen " +
            "eher 1–2 Tage durch. Für Sport reicht es meist, im Tab „Training“ zu starten – dort wird immer jede Sekunde gemessen.",
    )
    val today = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()
    val count by dbValue(state.dataVersion, prefs.continuousHrSeconds) { it.hr(today, today + 86400).size }
    SettingRow("Heute aufgezeichnet", "${count ?: 0} Werte")
}

@Composable
internal fun ProfilePage(state: StrapUiState) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val controller = context.app.repo.controller
    prefsTick()
    val p = prefs.profile

    SettingsHeader("Körper")
    IntSetting("Alter", p.age, 5..110, "Jahre", subtitle = "für max. Puls und Kalorien") { prefs.profile = prefs.profile.copy(age = it) }
    ChoiceSetting("Geschlecht", listOf(true to "männlich", false to "weiblich"), p.male, subtitle = "für die Kalorienformel") {
        prefs.profile = prefs.profile.copy(male = it)
    }
    IntSetting("Gewicht", p.weightKg, 20..300, "kg") { prefs.profile = prefs.profile.copy(weightKg = it) }
    IntSetting("Größe", p.heightCm, 80..250, "cm") { prefs.profile = prefs.profile.copy(heightCm = it) }

    SettingsHeader("Ziele")
    IntSetting("Schrittziel", p.stepGoal, 500..100_000, "Schritte") { v ->
        prefs.profile = prefs.profile.copy(stepGoal = v)
        if (state.settings?.has(HealthSetting.GOAL_STEPS) == true) controller?.applySettings(mapOf(HealthSetting.GOAL_STEPS to v), emptyMap())
    }
    ChoiceSetting(
        "Schlafziel",
        (6 * 60..10 * 60 step 30).map { it to formatMinutes(it) },
        p.sleepGoalMinutes,
        subtitle = "für die Schlafansicht",
    ) { prefs.profile = prefs.profile.copy(sleepGoalMinutes = it) }
    SettingsNote("Diese Werte bleiben auf dem Handy. Das Schrittziel wird zusätzlich an den Strap geschickt.")
}

@Composable
internal fun TrainingPage() {
    val prefs = LocalContext.current.app.prefs
    prefsTick()
    val p = prefs.profile
    val now = System.currentTimeMillis() / 1000
    val resting by dbValue(Unit) { it.values(HealthDb.RESTING_HR, now - 14 * 86400, now + 60) }
    val strapResting = resting.orEmpty().lastOrNull()?.value

    SettingsHeader("Puls")
    IntSetting(
        "Maximaler Puls",
        p.maxHrOverride,
        0..230,
        display = if (p.maxHrOverride > 0) "${p.maxHrOverride} bpm" else "Automatisch (${HeartRateZones.maxHeartRate(p.age)} bpm aus dem Alter)",
        subtitle = "0 = aus dem Alter berechnen",
    ) { prefs.profile = prefs.profile.copy(maxHrOverride = if (it in 1..99) 0 else it) }
    IntSetting(
        "Ruhepuls",
        p.restingHrManual,
        0..120,
        display = if (p.restingHrManual > 0) "${p.restingHrManual} bpm" else "Vom Strap (${strapResting?.let { "$it bpm" } ?: "noch unbekannt"})",
        subtitle = "0 = Wert vom Strap verwenden",
    ) { prefs.profile = prefs.profile.copy(restingHrManual = if (it in 1..29) 0 else it) }

    SettingsHeader("Pulszonen")
    ChoiceSetting(
        "Berechnung",
        listOf(false to "% vom maximalen Puls", true to "% der Herzfrequenzreserve (Karvonen)"),
        p.zonesFromReserve,
        subtitle = "Karvonen berücksichtigt deinen Ruhepuls und passt für Trainierte oft besser",
    ) { prefs.profile = prefs.profile.copy(zonesFromReserve = it) }
    val rest = p.zoneRest(strapResting)
    (0..4).forEach { z ->
        val from = HeartRateZones.lowerBpm(z, p.maxHeartRate, rest)
        val to = if (z < 4) HeartRateZones.lowerBpm(z + 1, p.maxHeartRate, rest) - 1 else p.maxHeartRate
        SettingRow("Zone ${z + 1} · ${HeartRateZones.names[z]}", "$from – $to bpm")
    }

    SettingsHeader("Während des Trainings")
    ChoiceSetting(
        "Standard-Sportart",
        listOf("Laufen", "Radfahren", "Krafttraining", "HIIT", "Gehen", "Sonstiges").map { it to it },
        prefs.defaultSport,
    ) { prefs.defaultSport = it }
    IntSetting(
        "Vibrieren über",
        prefs.workoutHrUpper,
        0..230,
        display = if (prefs.workoutHrUpper > 0) "${prefs.workoutHrUpper} bpm" else "Aus",
        subtitle = "Der Strap vibriert, wenn du zu schnell wirst (0 = aus)",
    ) { prefs.workoutHrUpper = it }
    IntSetting(
        "Vibrieren unter",
        prefs.workoutHrLower,
        0..200,
        display = if (prefs.workoutHrLower > 0) "${prefs.workoutHrLower} bpm" else "Aus",
        subtitle = "Ab Minute 3, wenn du zu langsam wirst (0 = aus)",
    ) { prefs.workoutHrLower = it }
    SettingsNote(
        "Tipp für deine Zone: Untergrenze und Obergrenze auf deine Zielzone stellen, z. B. Zone 2 von " +
            "${HeartRateZones.lowerBpm(1, p.maxHeartRate, rest)} bis ${HeartRateZones.lowerBpm(2, p.maxHeartRate, rest) - 1} bpm.",
    )
}
