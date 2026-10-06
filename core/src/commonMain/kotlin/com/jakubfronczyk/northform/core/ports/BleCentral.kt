package com.jakubfronczyk.northform.core.ports

import kotlin.jvm.JvmInline
import kotlinx.coroutines.flow.Flow
import com.jakubfronczyk.northform.core.SensorId

/**
 * A GATT characteristic/service UUID in the 16-bit short form the Bluetooth SIG assigns
 * ("180D", "2A37"). Comparison is case-insensitive because CoreBluetooth prints upper-case and the
 * SIG tables print mixed case.
 */
@JvmInline
value class GattUuid(val short: String) {
    fun matches(other: String): Boolean = other.equals(short, ignoreCase = true)
}

/**
 * The "dumb hands" transport port (D8). It carries NO policy: it is a thin, event-shaped mirror of
 * what a BLE central (CoreBluetooth on iOS, BluetoothGatt on Android) can be told and will report.
 * Every decision — what to do on a drop, when to back off, what "ready" means — is `SensorLink`
 * in :engine. An implementation must never call back into the app; it only emits [events].
 */
interface BleCentral {
    /** Every callback from the platform, in order, nothing dropped (the implementation uses an unbounded channel). */
    val events: Flow<BleEvent>

    fun scan(services: List<GattUuid>)
    fun stopScan()

    /**
     * Ask the platform to connect. On iOS a pending connect never times out: the system completes
     * it when the peripheral comes into range, which is why the link machine re-issues it after a
     * final disconnect instead of polling. `autoReconnect` maps to
     * `CBConnectPeripheralOptionEnableAutoReconnect` (iOS 17+):
     * https://developer.apple.com/documentation/corebluetooth/cbconnectperipheraloptionenableautoreconnect
     */
    fun connect(id: SensorId, autoReconnect: Boolean)
    fun cancelConnect(id: SensorId)

    /** Discover the given services and all their characteristics; completes with [BleEvent.Ready]. */
    fun discover(id: SensorId, services: List<GattUuid>)
    fun setNotify(id: SensorId, characteristic: GattUuid, on: Boolean)
    fun read(id: SensorId, characteristic: GattUuid)

    /** True if the platform already knows this peripheral (iOS: `retrievePeripherals(withIdentifiers:)`), so no scan is needed. */
    fun isKnown(id: SensorId): Boolean
}

sealed interface BleEvent {
    data object PowerOn : BleEvent
    data object PowerOff : BleEvent
    data object Unauthorized : BleEvent

    /**
     * iOS relaunched the app in the background and handed back the peripherals that were connected
     * or had a pending connection (`CBCentralManagerRestoredStatePeripheralsKey`). The link machine
     * adopts one as `wanted` (D9 step 4).
     */
    data class Restored(val peripherals: List<RestoredPeripheral>) : BleEvent
    data class RestoredPeripheral(val id: SensorId, val name: String?, val connected: Boolean)

    data class Discovered(val id: SensorId, val name: String?, val rssi: Int) : BleEvent
    data class Connected(val id: SensorId) : BleEvent
    data class FailedToConnect(val id: SensorId, val message: String?) : BleEvent

    /**
     * `isReconnecting = true` means the system will re-link by itself (auto-reconnect option);
     * `false` is a final disconnect and the link machine decides what happens next.
     */
    data class Disconnected(val id: SensorId, val isReconnecting: Boolean, val message: String?) : BleEvent

    /** Discovery finished; `characteristics` lists what the peripheral offers, with the property flags we care about. */
    data class Ready(val id: SensorId, val characteristics: List<Characteristic>) : BleEvent
    data class Characteristic(val service: GattUuid, val uuid: GattUuid, val notify: Boolean, val read: Boolean)

    data class Value(val id: SensorId, val characteristic: GattUuid, val bytes: ByteArray) : BleEvent {
        // ByteArray has referential equals; keep value semantics for tests (D4-style data class discipline).
        override fun equals(other: Any?): Boolean =
            other is Value && id == other.id && characteristic == other.characteristic && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = (id.hashCode() * 31 + characteristic.hashCode()) * 31 + bytes.contentHashCode()
    }

    data class ReadFailed(val id: SensorId, val characteristic: GattUuid, val message: String?) : BleEvent
}
