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

enum class FetchType(val code: Byte, val label: String) {
    ACTIVITY(0x01, "Aktivität"),
    STRESS(0x13, "Stress"),
}

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
    data class SyncFinished(val success: Boolean, val message: String? = null) : StrapEvent
    data class Log(val message: String) : StrapEvent
}

/** Persists the timestamp up to which each data type has been synchronised. */
interface SyncCursor {
    /** Epoch millis of the first minute that still needs to be fetched, or null. */
    fun get(type: FetchType): Long?
    fun set(type: FetchType, epochMillis: Long)
}
