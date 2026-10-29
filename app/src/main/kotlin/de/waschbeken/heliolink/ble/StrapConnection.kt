package de.waschbeken.heliolink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import de.waschbeken.heliolink.core.DeviceInfo
import de.waschbeken.heliolink.core.GattWrite
import de.waschbeken.heliolink.core.StrapEvent
import de.waschbeken.heliolink.core.StrapProtocol
import de.waschbeken.heliolink.core.WriteTarget
import java.util.UUID

object Uuids {
    private fun huami(id: String) = UUID.fromString("0000$id-0000-3512-2118-0009af100700")
    private fun std(id: String) = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")

    val CHUNKED_WRITE: UUID = huami("0016")
    val CHUNKED_READ: UUID = huami("0017")
    val FETCH_CONTROL: UUID = huami("0004")
    val FETCH_DATA: UUID = huami("0005")
    val HEART_RATE_MEASUREMENT: UUID = std("2a37")
    val CURRENT_TIME: UUID = std("2a2b")
    val FIRMWARE_REVISION: UUID = std("2a26")
    val HARDWARE_REVISION: UUID = std("2a27")
    val SERIAL_NUMBER: UUID = std("2a25")
    val CCCD: UUID = std("2902")
}

enum class LinkState { DISCONNECTED, CONNECTING, AUTHENTICATING, READY, AUTH_FAILED }

/**
 * Owns the GATT connection to the strap, serialises all GATT operations and feeds the
 * [StrapProtocol]. Every public method and every callback runs on [handler]'s thread.
 */
@SuppressLint("MissingPermission")
class StrapConnection(
    private val context: Context,
    private val address: String,
    authKey: ByteArray,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onLinkState(state: LinkState, detail: String?)
        fun onEvent(event: StrapEvent)
        fun onDeviceInfo(info: DeviceInfo)
    }

    private sealed class Op {
        class Write(val c: BluetoothGattCharacteristic, val value: ByteArray) : Op()
        class Notify(val c: BluetoothGattCharacteristic, val enable: Boolean) : Op()
        class Read(val c: BluetoothGattCharacteristic) : Op()
        class Mtu(val mtu: Int) : Op()
        class Run(val block: () -> Unit) : Op()
    }

    val protocol = StrapProtocol(authKey, ::enqueue, ::onProtocolEvent)

    var state = LinkState.DISCONNECTED
        private set

    private var gatt: BluetoothGatt? = null
    private val characteristics = HashMap<UUID, BluetoothGattCharacteristic>()
    private val ops = ArrayDeque<Op>()
    private var current: Op? = null
    private var closedByUser = false
    private var reconnectAttempt = 0
    private var lastFetchActivity = 0L
    private var info = DeviceInfo()

    private val opTimeout = Runnable {
        // A GATT operation never completed, skip it so the queue does not get stuck.
        current = null
        pump()
    }

    private val reconnectRunnable = Runnable { openGatt(autoConnect = true) }

    private val fetchWatchdog = object : Runnable {
        override fun run() {
            if (protocol.isSyncing && System.currentTimeMillis() - lastFetchActivity > 30_000) {
                protocol.abortSync("Zeitüberschreitung beim Abruf")
            }
            handler.postDelayed(this, 5_000)
        }
    }

    fun connect() {
        closedByUser = false
        reconnectAttempt = 0
        handler.removeCallbacks(reconnectRunnable)
        if (gatt == null) openGatt(autoConnect = false)
    }

    fun close() {
        closedByUser = true
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(fetchWatchdog)
        protocol.abortSync("Verbindung getrennt")
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        resetQueue()
        setState(LinkState.DISCONNECTED, null)
    }

    /** Starts the history download if possible. */
    fun startSync(block: StrapProtocol.() -> Unit) {
        if (state != LinkState.READY || protocol.isSyncing) return
        lastFetchActivity = System.currentTimeMillis()
        protocol.block()
    }

    private fun openGatt(autoConnect: Boolean) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            setState(LinkState.DISCONNECTED, "Bluetooth ist aus")
            scheduleReconnect()
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            setState(LinkState.DISCONNECTED, "Ungültige Adresse $address")
            return
        }
        setState(LinkState.CONNECTING, if (autoConnect) "Warte auf Strap…" else null)
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun scheduleReconnect() {
        if (closedByUser || state == LinkState.AUTH_FAILED) return
        reconnectAttempt++
        val delay = (reconnectAttempt * 5_000L).coerceAtMost(60_000L)
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    private fun setState(newState: LinkState, detail: String?) {
        state = newState
        listener.onLinkState(newState, detail)
    }

    private fun onProtocolEvent(event: StrapEvent) {
        when (event) {
            is StrapEvent.AuthFailed -> {
                setState(LinkState.AUTH_FAILED, event.reason)
                closedByUser = true
                gatt?.disconnect()
            }
            is StrapEvent.Ready -> {
                reconnectAttempt = 0
                setState(LinkState.READY, null)
            }
            is StrapEvent.SyncProgress -> lastFetchActivity = System.currentTimeMillis()
            else -> Unit
        }
        listener.onEvent(event)
    }

    // ------------------------------------------------------------------ queue

    private fun enqueue(write: GattWrite) {
        val uuid = when (write.target) {
            WriteTarget.CHUNKED_WRITE -> Uuids.CHUNKED_WRITE
            WriteTarget.CHUNKED_ACK -> Uuids.CHUNKED_READ
            WriteTarget.FETCH_CONTROL -> Uuids.FETCH_CONTROL
            WriteTarget.CURRENT_TIME -> Uuids.CURRENT_TIME
        }
        val c = characteristics[uuid] ?: return
        if (write.target == WriteTarget.FETCH_CONTROL) lastFetchActivity = System.currentTimeMillis()
        ops.addLast(Op.Write(c, write.value))
        pump()
    }

    private fun resetQueue() {
        handler.removeCallbacks(opTimeout)
        ops.clear()
        current = null
        characteristics.clear()
    }

    private fun pump() {
        if (current != null) return
        val g = gatt ?: return
        val op = ops.removeFirstOrNull() ?: return
        current = op
        val started = try {
            when (op) {
                is Op.Write -> writeCharacteristic(g, op.c, op.value)
                is Op.Notify -> enableNotification(g, op.c, op.enable)
                is Op.Read -> g.readCharacteristic(op.c)
                is Op.Mtu -> g.requestMtu(op.mtu)
                is Op.Run -> {
                    op.block()
                    false
                }
            }
        } catch (e: SecurityException) {
            listener.onEvent(StrapEvent.Log("Bluetooth-Berechtigung fehlt: ${e.message}"))
            false
        }
        if (started) {
            handler.postDelayed(opTimeout, 5_000)
        } else {
            current = null
            handler.post { pump() }
        }
    }

    private fun completeOp() {
        handler.removeCallbacks(opTimeout)
        current = null
        pump()
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val type = c.writeType
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type
            c.value = value
            g.writeCharacteristic(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotification(g: BluetoothGatt, c: BluetoothGattCharacteristic, enable: Boolean): Boolean {
        if (!g.setCharacteristicNotification(c, enable)) return false
        val descriptor = c.getDescriptor(Uuids.CCCD) ?: return false
        val value = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            g.writeDescriptor(descriptor)
        }
    }

    // ------------------------------------------------------------------ setup after connect

    private fun onServicesReady(g: BluetoothGatt) {
        characteristics.clear()
        for (service in g.services) {
            for (c in service.characteristics) characteristics.putIfAbsent(c.uuid, c)
        }
        val write = characteristics[Uuids.CHUNKED_WRITE]
        val read = characteristics[Uuids.CHUNKED_READ]
        if (write == null || read == null) {
            setState(LinkState.DISCONNECTED, "Das Gerät spricht kein Zepp-OS-Protokoll")
            g.disconnect()
            return
        }
        ops.addLast(Op.Mtu(247))
        ops.addLast(Op.Notify(read, true))
        characteristics[Uuids.FETCH_CONTROL]?.let { ops.addLast(Op.Notify(it, true)) }
        characteristics[Uuids.FETCH_DATA]?.let { ops.addLast(Op.Notify(it, true)) }
        characteristics[Uuids.HEART_RATE_MEASUREMENT]?.let { ops.addLast(Op.Notify(it, true)) }
        listOf(Uuids.FIRMWARE_REVISION, Uuids.HARDWARE_REVISION, Uuids.SERIAL_NUMBER).forEach { uuid ->
            characteristics[uuid]?.let { ops.addLast(Op.Read(it)) }
        }
        ops.addLast(Op.Run {
            setState(LinkState.AUTHENTICATING, null)
            protocol.start()
        })
        handler.removeCallbacks(fetchWatchdog)
        handler.post(fetchWatchdog)
        pump()
    }

    private fun onRead(uuid: UUID, value: ByteArray) {
        val text = String(value, Charsets.UTF_8).trim { it <= ' ' }
        info = when (uuid) {
            Uuids.FIRMWARE_REVISION -> info.copy(firmware = text)
            Uuids.HARDWARE_REVISION -> info.copy(hardware = text)
            Uuids.SERIAL_NUMBER -> info.copy(serial = text)
            else -> return
        }
        listener.onDeviceInfo(info)
    }

    private fun onNotification(uuid: UUID, value: ByteArray) {
        when (uuid) {
            Uuids.CHUNKED_READ -> protocol.onChunkedNotification(value)
            Uuids.FETCH_CONTROL -> {
                lastFetchActivity = System.currentTimeMillis()
                protocol.onFetchControlNotification(value)
            }
            Uuids.FETCH_DATA -> {
                lastFetchActivity = System.currentTimeMillis()
                protocol.onFetchDataNotification(value)
            }
            Uuids.HEART_RATE_MEASUREMENT -> protocol.onHeartRateMeasurement(value)
        }
    }

    // ------------------------------------------------------------------ GATT callback (binder threads)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    setState(LinkState.CONNECTING, "Verbunden, lese Dienste…")
                    handler.postDelayed({ if (g == gatt) g.discoverServices() }, 600)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    protocol.abortSync("Verbindung verloren")
                    resetQueue()
                    handler.removeCallbacks(fetchWatchdog)
                    g.close()
                    gatt = null
                    if (state != LinkState.AUTH_FAILED) {
                        setState(LinkState.DISCONNECTED, if (status != BluetoothGatt.GATT_SUCCESS) "Verbindung verloren ($status)" else null)
                    }
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) onServicesReady(g) else g.disconnect()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) protocol.onAttMtuChanged(mtu)
                if (current is Op.Mtu) completeOp()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post { if (current is Op.Write) completeOp() }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post { if (current is Op.Notify) completeOp() }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val copy = value.copyOf()
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) onRead(c.uuid, copy)
                if (current is Op.Read) completeOp()
            }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            onCharacteristicRead(g, c, c.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            val uuid = c.uuid
            handler.post { if (g == gatt) onNotification(uuid, copy) }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            onCharacteristicChanged(g, c, c.value ?: return)
        }
    }
}
