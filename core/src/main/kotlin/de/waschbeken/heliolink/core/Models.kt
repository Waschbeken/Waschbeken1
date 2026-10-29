package de.waschbeken.heliolink.core

/** Characteristic a [GattWrite] must be sent to. */
enum class WriteTarget {
    /** 00000016-0000-3512-2118-0009af100700 */
    CHUNKED_WRITE,

    /** 00000017-0000-3512-2118-0009af100700 (acks are written to the read characteristic) */
    CHUNKED_ACK,

    /** 00000004-0000-3512-2118-0009af100700 */
    FETCH_CONTROL,

    /** Standard current time characteristic 0x2A2B */
    CURRENT_TIME,
}

class GattWrite(val target: WriteTarget, val value: ByteArray) {
    override fun toString() = "GattWrite($target, ${value.toHex()})"
}

enum class HeartRateMode(val code: Byte) {
    STOP(0x00), START(0x01), CONTINUE(0x02)
}

/** One minute of activity data as stored by the strap. */
data class ActivitySample(
    val timestamp: Long, // epoch seconds, start of the minute
    val rawKind: Int,
    val intensity: Int,
    val steps: Int,
    val heartRate: Int, // 0 = no measurement
    val sleep: Int,
    val deepSleep: Int,
    val remSleep: Int,
) {
    val isSleep get() = rawKind == KIND_SLEEP
    val notWorn get() = rawKind == KIND_NOT_WORN || rawKind == KIND_CHARGING

    val sleepStage: SleepStage
        get() = when {
            !isSleep -> SleepStage.AWAKE
            remSleep and 127 > 55 -> SleepStage.REM
            deepSleep and 127 > 42 -> SleepStage.DEEP
            else -> SleepStage.LIGHT
        }

    companion object {
        const val KIND_NOT_WORN = 115
        const val KIND_CHARGING = 118
        const val KIND_SLEEP = 120
    }
}

enum class SleepStage { AWAKE, LIGHT, DEEP, REM }

data class StressSample(val timestamp: Long, val stress: Int)

/** History data types. [code] is null when the strap's code is not known up front (see [StrapProtocol.probeSleepCode]). */
enum class FetchType(val code: Int?, val label: String) {
    ACTIVITY(0x01, "Aktivität"),
    STRESS(0x13, "Stress"),
    SPO2(0x25, "SpO₂"),
    RESTING_HEART_RATE(0x3a, "Ruhepuls"),
    PAI(0x0d, "PAI"),
    TEMPERATURE(0x2e, "Hauttemperatur"),
    RESPIRATION(0x38, "Atemfrequenz"),
    HRV(0x49, "HRV"),
    SPO2_SLEEP(0x26, "SpO₂ im Schlaf"),
    ODI(0x4a, "Atemaussetzer-Auswertung"),
    HRV_SDNN(0x4e, "HRV (SDNN)"),
    SLEEP(null, "Schlafphasen"),
}

/** Personal Activity Intelligence as calculated by the strap (one value per day). */
data class PaiSample(
    val timestamp: Long,
    val today: Float,
    val total: Float,
    val lowMinutes: Int,
    val moderateMinutes: Int,
    val highMinutes: Int,
)

data class ValueSample(val timestamp: Long, val value: Int)

/**
 * The strap's nightly oxygen drop summary (fetch type 0x4A). [day] is the local midnight that
 * starts the day of waking up (epoch seconds). [validTime] is how long the measurement was usable
 * (unit not verified, probably seconds); the other values are only meaningful when it is above 0.
 */
data class OdiNight(val day: Long, val validTime: Long, val odi: Long, val score: Int?, val drops: Int)

data class DeviceInfo(val firmware: String? = null, val hardware: String? = null, val serial: String? = null)

sealed interface StrapEvent {
    data object Authenticated : StrapEvent
    data class AuthFailed(val reason: String) : StrapEvent
    data class Ready(val encryptedEndpoints: Set<Int>) : StrapEvent
    data class Battery(val level: Int, val charging: Boolean) : StrapEvent
    data class Steps(val steps: Long, val distanceMeters: Long, val calories: Long) : StrapEvent
    data class HeartRate(val bpm: Int, val rrIntervalsMs: List<Int>) : StrapEvent
    data class SleepState(val asleep: Boolean) : StrapEvent
    data class HealthSettings(val settings: HealthConfig) : StrapEvent
    data class ConfigAck(val status: Int) : StrapEvent
    data object FindAcknowledged : StrapEvent
    data object FindStoppedOnStrap : StrapEvent

    data class SyncProgress(val type: FetchType, val since: Long, val expectedBytes: Long, val receivedBytes: Long) : StrapEvent
    data class ActivityData(val samples: List<ActivitySample>) : StrapEvent
    data class StressData(val samples: List<StressSample>) : StrapEvent
    data class Spo2Data(val samples: List<ValueSample>) : StrapEvent
    data class RestingHeartRateData(val samples: List<ValueSample>) : StrapEvent
    data class SleepData(val records: List<SleepRecord>) : StrapEvent
    data class PaiData(val samples: List<PaiSample>) : StrapEvent

    /** Skin temperature in hundredths of a degree Celsius. */
    data class TemperatureData(val samples: List<ValueSample>) : StrapEvent
    data class RespirationData(val samples: List<ValueSample>) : StrapEvent
    /** The strap's own HRV values in ms, measured every one or two minutes (day and night). */
    data class HrvData(val samples: List<ValueSample>) : StrapEvent
    /** SDNN in ms, a few values a day, calculated by the strap. */
    data class SdnnData(val samples: List<ValueSample>) : StrapEvent
    /** The strap's own nightly summary of oxygen drops (sleep apnea screening). */
    data class OdiData(val nights: List<OdiNight>) : StrapEvent
    data class Alarms(val alarms: List<StrapAlarm>) : StrapEvent

    /** Progress of the sleep code search: [tried] of [total] codes, with the last answer. */
    data class SleepProbeProgress(val tried: Int, val total: Int, val code: Int, val detail: String) : StrapEvent

    /** Result of [StrapProtocol.probeSleepCode]: the fetch code of the sleep records, or null if none matched. */
    data class SleepCodeProbed(val code: Int?, val offset: Int) : StrapEvent
    data class SyncFinished(val success: Boolean, val message: String? = null) : StrapEvent
    data class Log(val message: String) : StrapEvent
}

/** Persists the timestamp up to which each data type has been synchronised. */
interface SyncCursor {
    /** Epoch millis of the first minute that still needs to be fetched, or null. */
    fun get(type: FetchType): Long?
    fun set(type: FetchType, epochMillis: Long)
}
