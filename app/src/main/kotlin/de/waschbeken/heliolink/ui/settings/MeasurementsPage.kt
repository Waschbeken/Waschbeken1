package de.waschbeken.heliolink.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.HealthConfig
import de.waschbeken.heliolink.core.HealthSetting
import de.waschbeken.heliolink.service.StrapUiState

/** Applies strap settings and knows which change is still pending or was refused by the strap. */
internal class StrapSettings(val state: StrapUiState, private val apply: (Map<HealthSetting, Int>, Map<HealthSetting, List<Int>>) -> Unit) {
    val config: HealthConfig? = state.settings
    val enabled get() = state.connected

    fun set(setting: HealthSetting, value: Int) = apply(mapOf(setting to value), emptyMap())

    fun setList(setting: HealthSetting, value: List<Int>) = apply(emptyMap(), mapOf(setting to value))

    /** Extra text for a setting: pending, refused or [normal]. */
    fun note(setting: HealthSetting, normal: String?): String? = when (setting) {
        in state.pendingSettings -> "Wird übertragen …"
        in state.rejectedSettings -> "Vom Strap nicht übernommen" + (normal?.let { " · $it" } ?: "")
        else -> normal
    }
}

@Composable
internal fun rememberStrapSettings(state: StrapUiState): StrapSettings {
    val controller = LocalContext.current.app.repo.controller
    return StrapSettings(state) { values, lists -> controller?.applySettings(values, lists) }
}

private fun intervalLabel(v: Int) = when {
    v == 0 -> "Aus"
    v < 0 -> "Intelligent (automatisch)"
    v == 1 -> "Jede Minute"
    else -> "Alle $v Minuten"
}

@Composable
private fun StrapSwitch(s: StrapSettings, setting: HealthSetting, title: String, description: String, enabled: Boolean = true) {
    val value = s.config?.bool(setting) ?: return
    SwitchSetting(title, s.note(setting, description), value, s.enabled && enabled) { s.set(setting, if (it) 1 else 0) }
}

@Composable
private fun StrapChoice(s: StrapSettings, setting: HealthSetting, title: String, unit: String, description: String? = null, enabled: Boolean = true) {
    val config = s.config ?: return
    val current = config.values[setting]?.let { it and 0xff } ?: return
    val options = config.options[setting]?.map { it and 0xff }?.takeIf { it.isNotEmpty() } ?: return
    ChoiceSetting(
        title,
        options.map { it to if (it == 0) "Aus" else "$it $unit" },
        current,
        subtitle = s.note(setting, description),
        enabled = s.enabled && enabled,
    ) { s.set(setting, it) }
}

@Composable
internal fun MeasurementsPage(state: StrapUiState) {
    val s = rememberStrapSettings(state)
    val config = s.config
    if (config == null) {
        SettingsNote(
            if (state.connected) "Lade Einstellungen vom Strap …"
            else "Diese Einstellungen werden direkt auf dem Strap gespeichert. Verbinde den Strap, um sie zu ändern.",
        )
        return
    }

    SettingsHeader("Herzfrequenz rund um die Uhr")
    val interval = config.values[HealthSetting.HEART_RATE_INTERVAL]
    if (interval != null) {
        val options = config.options[HealthSetting.HEART_RATE_INTERVAL]?.takeIf { it.isNotEmpty() } ?: listOf(0, -2, 1, 5, 10, 30)
        ChoiceSetting(
            "Messintervall",
            options.sortedWith(compareBy({ it <= 0 }, { it })).map { it to intervalLabel(it) },
            interval,
            subtitle = s.note(HealthSetting.HEART_RATE_INTERVAL, "Kürzer = genauer, länger = mehr Akku"),
            enabled = s.enabled,
        ) { s.set(HealthSetting.HEART_RATE_INTERVAL, it) }
        SettingsNote(
            "Öfter als jede Minute misst der Strap von sich aus nicht. Für sekundengenaue Werte: " +
                "Einstellungen → Puls-Aufzeichnung.",
        )
    }
    StrapSwitch(
        s, HealthSetting.HEART_RATE_ACTIVITY_MONITORING, "Bei Aktivität häufiger messen",
        when {
            interval == 0 -> "Nur möglich, wenn das Messintervall nicht „Aus“ ist"
            interval != null && interval < 0 -> "Bei „Intelligent“ regelt der Strap das teilweise selbst. Lässt sich die " +
                "Option nicht einschalten: zuerst ein festes Intervall wählen (z. B. jede Minute)"
            else -> "Erkennt Bewegung und misst dann öfter"
        },
        enabled = interval != 0,
    )
    StrapSwitch(
        s, HealthSetting.HEART_RATE_BROADCAST, "Puls an andere Geräte senden",
        "Der Strap wird zum Brustgurt: Laufband, Radcomputer, Zwift, Strava & Co. empfangen den Puls direkt per Bluetooth. Braucht mehr Akku.",
    )

    SettingsHeader("Pulswarnungen")
    val alertsWork = interval == null || interval < 0 || interval == 1
    StrapChoice(
        s, HealthSetting.HEART_RATE_HIGH_ALERT, "Warnung bei hohem Puls (in Ruhe)", "bpm",
        if (alertsWork) "Der Strap vibriert, wenn der Puls ohne Bewegung darüber liegt" else "Nur bei „Intelligent“ oder „Jede Minute“",
        enabled = alertsWork,
    )
    StrapChoice(
        s, HealthSetting.HEART_RATE_LOW_ALERT, "Warnung bei niedrigem Puls", "bpm",
        if (alertsWork) "Der Strap vibriert, wenn der Puls darunter fällt" else "Nur bei „Intelligent“ oder „Jede Minute“",
        enabled = alertsWork,
    )

    SettingsHeader("Schlaf")
    StrapSwitch(s, HealthSetting.SLEEP_HIGH_ACCURACY, "Schlaf mit hoher Genauigkeit", "Nutzt den Pulssensor für REM- und Tiefschlaf. Empfohlen.")
    StrapSwitch(s, HealthSetting.SLEEP_BREATHING_QUALITY, "Atmungsqualität im Schlaf", "Misst nachts Atemfrequenz und Sauerstoff")

    SettingsHeader("Stress")
    StrapSwitch(s, HealthSetting.STRESS_MONITORING, "Stress automatisch messen", "Alle paar Minuten aus der Herzfrequenzvariabilität")
    StrapSwitch(
        s, HealthSetting.STRESS_RELAXATION_REMINDER, "Entspannungs-Erinnerung", "Vibriert bei länger hohem Stress",
        enabled = config.bool(HealthSetting.STRESS_MONITORING) != false,
    )

    SettingsHeader("Blutsauerstoff (SpO₂)")
    StrapSwitch(s, HealthSetting.SPO2_ALL_DAY, "SpO₂ ganztägig messen", "Regelmäßige Messungen, auch nachts")
    StrapChoice(
        s, HealthSetting.SPO2_LOW_ALERT, "Warnung bei niedrigem SpO₂", "%", "Der Strap vibriert darunter",
        enabled = config.bool(HealthSetting.SPO2_ALL_DAY) != false,
    )

    SettingsHeader("Bluetooth")
    StrapSwitch(
        s, HealthSetting.CONNECTED_ADVERTISING, "Sichtbar bleiben, wenn verbunden",
        "Andere Geräte (z. B. Laufband) finden den Strap auch, während HelioLink verbunden ist",
    )
    SettingsNote("Angezeigt wird nur, was dein Strap meldet. Änderungen übernimmt er sofort; die App prüft danach, ob sie angekommen sind.")
}

@Composable
internal fun MovementPage(state: StrapUiState) {
    val s = rememberStrapSettings(state)
    val config = s.config
    val prefs = LocalContext.current.app.prefs
    prefsTick()
    if (config == null) {
        SettingsNote(if (state.connected) "Lade Einstellungen vom Strap …" else "Verbinde den Strap, um diese Einstellungen zu ändern.")
        return
    }

    SettingsHeader("Bewegungserinnerung")
    StrapSwitch(s, HealthSetting.INACTIVITY_WARNINGS, "Bewegungserinnerung", "Vibriert nach etwa einer Stunde Sitzen")
    val reminderOn = config.bool(HealthSetting.INACTIVITY_WARNINGS) == true
    config.values[HealthSetting.INACTIVITY_START]?.let {
        TimeSetting("Von", it, s.enabled && reminderOn, s.note(HealthSetting.INACTIVITY_START, null)) { v -> s.set(HealthSetting.INACTIVITY_START, v) }
    }
    config.values[HealthSetting.INACTIVITY_END]?.let {
        TimeSetting("Bis", it, s.enabled && reminderOn, s.note(HealthSetting.INACTIVITY_END, null)) { v -> s.set(HealthSetting.INACTIVITY_END, v) }
    }
    StrapSwitch(s, HealthSetting.INACTIVITY_DND, "Pause (z. B. Mittag oder Unterricht)", "In diesem Zeitraum keine Erinnerung", enabled = reminderOn)
    val pauseOn = reminderOn && config.bool(HealthSetting.INACTIVITY_DND) == true
    config.values[HealthSetting.INACTIVITY_DND_START]?.let {
        TimeSetting("Pause von", it, s.enabled && pauseOn, s.note(HealthSetting.INACTIVITY_DND_START, null)) { v -> s.set(HealthSetting.INACTIVITY_DND_START, v) }
    }
    config.values[HealthSetting.INACTIVITY_DND_END]?.let {
        TimeSetting("Pause bis", it, s.enabled && pauseOn, s.note(HealthSetting.INACTIVITY_DND_END, null)) { v -> s.set(HealthSetting.INACTIVITY_DND_END, v) }
    }

    SettingsHeader("Tagesziel")
    val goal = config.values[HealthSetting.GOAL_STEPS]
    IntSetting(
        "Schrittziel",
        goal ?: prefs.profile.stepGoal,
        500..100_000,
        "Schritte",
        subtitle = if (goal != null) s.note(HealthSetting.GOAL_STEPS, "auf dem Strap und in der App") else "in der App",
    ) { v ->
        prefs.profile = prefs.profile.copy(stepGoal = v)
        if (goal != null) s.set(HealthSetting.GOAL_STEPS, v)
    }
    StrapSwitch(s, HealthSetting.GOAL_NOTIFICATION, "Vibrieren bei erreichtem Ziel", "Der Strap meldet sich, wenn du das Schrittziel geschafft hast")

    SettingsHeader("Training automatisch erkennen")
    val categories = config.lists[HealthSetting.WORKOUT_DETECTION_CATEGORIES]
    val possible = config.options[HealthSetting.WORKOUT_DETECTION_CATEGORIES].orEmpty()
    if (categories != null && possible.isNotEmpty()) {
        SwitchSetting(
            "Trainings erkennen",
            s.note(HealthSetting.WORKOUT_DETECTION_CATEGORIES, "Der Strap erkennt z. B. Laufen oder Gehen und zeichnet es als Training auf"),
            categories.isNotEmpty(),
            s.enabled,
        ) { on -> s.setList(HealthSetting.WORKOUT_DETECTION_CATEGORIES, if (on) possible else emptyList()) }
    }
    val sensitivity = config.values[HealthSetting.WORKOUT_DETECTION_SENSITIVITY]
    if (sensitivity != null) {
        val labels = mapOf(0 to "Hoch (erkennt früher)", 1 to "Standard", 2 to "Niedrig (weniger Fehlalarme)")
        val options = config.options[HealthSetting.WORKOUT_DETECTION_SENSITIVITY]?.takeIf { it.isNotEmpty() } ?: labels.keys.toList()
        ChoiceSetting(
            "Empfindlichkeit",
            options.map { it to (labels[it] ?: "Stufe $it") },
            sensitivity,
            subtitle = s.note(HealthSetting.WORKOUT_DETECTION_SENSITIVITY, null),
            enabled = s.enabled && categories?.isNotEmpty() == true,
        ) { s.set(HealthSetting.WORKOUT_DETECTION_SENSITIVITY, it) }
    }
    if (categories == null && sensitivity == null) {
        SettingsNote("Dein Strap meldet keine Trainingserkennung.")
    }
    SettingsNote("Trainings mit sekündlichem Puls startest du im Tab „Training“.")
}
