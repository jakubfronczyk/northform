package com.jakubfronczyk.northform.adapters.ble

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.ports.BleCentral
import com.jakubfronczyk.northform.core.ports.BleEvent
import com.jakubfronczyk.northform.core.ports.GattUuid
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBCentralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBCentralManagerRestoredStatePeripheralsKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBConnectPeripheralOptionEnableAutoReconnect
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.CoreFoundation.CFAbsoluteTime
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import platform.posix.memcpy

/**
 * The CoreBluetooth "hands" (D8), in two halves because Kotlin/Native forbids mixing Kotlin and
 * Objective-C supertypes on one class ("Mixing Kotlin and Objective-C supertypes is not supported"):
 *
 *   CoreBluetoothCentral : BleCentral            Kotlin — owns the CBCentralManager, executes commands,
 *                                                 exposes `events`
 *   └─ Delegate : NSObject(), CB…DelegateProtocol Obj-C — forwards every delegate callback as ONE BleEvent
 *
 * Neither half decides anything; `SensorLink` (:engine) is the brain.
 *
 * Boot order (D9): construct this INSIDE `NorthformApp.boot()`, which Swift calls first in
 * `didFinishLaunching`. The `CBCentralManager` is created in this class's initializer, with the
 * delegate already alive, so when iOS relaunches the app for a Bluetooth event
 * `centralManager(_:willRestoreState:)` — "the first method invoked when your app is relaunched into
 * the background" (Core Bluetooth Background Processing guide) — lands in a live Kotlin delegate.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreBluetoothCentral(
    /**
     * Must be identical across executions; a compile-time constant, never read from `launchOptions`
     * (nil in scene-based apps): https://developer.apple.com/documentation/corebluetooth/cbcentralmanageroptionrestoreidentifierkey
     */
    restoreIdentifier: String,
    private val log: (String) -> Unit = {},
) : BleCentral {

    private val channel = Channel<BleEvent>(Channel.UNLIMITED)
    override val events: Flow<BleEvent> = channel.receiveAsFlow()

    /** CoreBluetooth requires a strong reference to every CBPeripheral you use; keyed by identifier UUID string. */
    private val peripherals = HashMap<String, CBPeripheral>()

    private val delegate = Delegate()
    private val queue = dispatch_queue_create("com.jakubfronczyk.northform.ble", null)

    /**
     * CoreBluetooth aborts the process if restoration is requested without the `bluetooth-central`
     * background mode ("State restoration of CBCentralManager is only allowed for applications that
     * have specified the "bluetooth-central" background mode", NSInternalInconsistencyException).
     * The app declares it in Info.plist (project.yml); a test executable has no Info.plist, so it gets a
     * plain central. Reading one plist key is configuration, not a decision.
     */
    private val restorationAllowed: Boolean =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("UIBackgroundModes") as? List<*>)
            ?.contains("bluetooth-central") == true

    private val manager: CBCentralManager = CBCentralManager(
        delegate = delegate,
        queue = queue,
        options = buildMap<Any?, Any?> {
            put(CBCentralManagerOptionShowPowerAlertKey, false)
            if (restorationAllowed) put(CBCentralManagerOptionRestoreIdentifierKey, restoreIdentifier)
        },
    ).also {
        if (restorationAllowed) log("CBCentralManager created (restore id = $restoreIdentifier)")
        else log("CBCentralManager created WITHOUT state restoration: no bluetooth-central background mode in Info.plist")
    }

    private fun emit(e: BleEvent) {
        log("ble ← $e")
        check(channel.trySend(e).isSuccess) { "unbounded BLE event channel refused an event" }
    }

    // ── BleCentral commands ───────────────────────────────────────────────────────────────────

    override fun scan(services: List<GattUuid>) {
        manager.scanForPeripheralsWithServices(services.map { CBUUID.UUIDWithString(it.short) }, options = null)
    }

    override fun stopScan() = manager.stopScan()

    override fun isKnown(id: SensorId): Boolean = peripheral(id) != null

    override fun connect(id: SensorId, autoReconnect: Boolean) {
        val p = peripheral(id) ?: return log("connect: unknown peripheral $id (scan first)")
        // iOS 17+: the system re-links after an unexpected drop and reports it via
        // centralManager(_:didDisconnectPeripheral:timestamp:isReconnecting:error:).
        // https://developer.apple.com/documentation/corebluetooth/cbconnectperipheraloptionenableautoreconnect
        val options: Map<Any?, Any?>? = if (autoReconnect) mapOf(CBConnectPeripheralOptionEnableAutoReconnect to true) else null
        manager.connectPeripheral(p, options = options)
    }

    override fun cancelConnect(id: SensorId) {
        peripheral(id)?.let { manager.cancelPeripheralConnection(it) }
    }

    override fun discover(id: SensorId, services: List<GattUuid>) {
        val p = peripheral(id) ?: return
        p.delegate = delegate
        p.discoverServices(services.map { CBUUID.UUIDWithString(it.short) })
    }

    override fun setNotify(id: SensorId, characteristic: GattUuid, on: Boolean) {
        val (p, c) = characteristic(id, characteristic) ?: return
        p.setNotifyValue(on, forCharacteristic = c)
    }

    override fun read(id: SensorId, characteristic: GattUuid) {
        val (p, c) = characteristic(id, characteristic) ?: return
        p.readValueForCharacteristic(c)
    }

    // ── peripheral bookkeeping (shared by both halves) ────────────────────────────────────────

    /** Known to the system (previously connected) or seen in this process. `retrievePeripherals(withIdentifiers:)`, iOS 7+. */
    private fun peripheral(id: SensorId): CBPeripheral? {
        peripherals[id.value]?.let { return it }
        val uuid = NSUUID(uUIDString = id.value) ?: return null
        val found = manager.retrievePeripheralsWithIdentifiers(listOf(uuid)).firstOrNull() as? CBPeripheral ?: return null
        return remember(found)
    }

    private fun remember(p: CBPeripheral): CBPeripheral {
        peripherals[p.identifier.UUIDString] = p
        p.delegate = delegate
        return p
    }

    private fun characteristic(id: SensorId, uuid: GattUuid): Pair<CBPeripheral, CBCharacteristic>? {
        val p = peripheral(id) ?: return null
        val services = p.services?.filterIsInstance<CBService>() ?: return null
        val c = services.asSequence()
            .flatMap { it.characteristics?.filterIsInstance<CBCharacteristic>().orEmpty().asSequence() }
            .firstOrNull { uuid.matches(it.UUID.UUIDString) } ?: return null
        return p to c
    }

    /** Services whose characteristics are still being discovered; `Ready` fires when the count reaches zero. */
    private val pendingServices = HashMap<String, Int>()

    private val CBPeripheral.sensorId get() = SensorId(identifier.UUIDString)

    /**
     * The Obj-C half. `@ObjCSignatureOverride` on the selectors whose Kotlin parameter TYPES collide
     * with a sibling's (same types, different Obj-C names): https://kotlinlang.org/docs/native-objc-interop.html
     */
    private inner class Delegate : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

        // ── CBCentralManagerDelegate ──────────────────────────────────────────────────────────

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            when (central.state) {
                CBManagerStatePoweredOn -> emit(BleEvent.PowerOn)
                CBManagerStatePoweredOff -> emit(BleEvent.PowerOff)
                CBManagerStateUnauthorized -> emit(BleEvent.Unauthorized)
                else -> log("central state ${central.state} (resetting/unsupported/unknown): no event")
            }
        }

        /**
         * Background relaunch: "contains peripherals that were connected or had a pending connection when
         * the app stopped" — https://developer.apple.com/documentation/corebluetooth/central-manager-state-restoration-options
         */
        override fun centralManager(central: CBCentralManager, willRestoreState: Map<Any?, *>) {
            val restored = (willRestoreState[CBCentralManagerRestoredStatePeripheralsKey] as? List<*>)
                ?.filterIsInstance<CBPeripheral>().orEmpty()
                .map { remember(it) }
                .map { BleEvent.RestoredPeripheral(it.sensorId, it.name, connected = it.state == CBPeripheralStateConnected) }
            emit(BleEvent.Restored(restored))
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            remember(didDiscoverPeripheral)
            emit(BleEvent.Discovered(didDiscoverPeripheral.sensorId, didDiscoverPeripheral.name, RSSI.intValue))
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            emit(BleEvent.Connected(didConnectPeripheral.sensorId))
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            emit(BleEvent.FailedToConnect(didFailToConnectPeripheral.sensorId, error?.localizedDescription))
        }

        /** Pre-iOS-17 form; iOS 17+ calls the `isReconnecting` form instead. Both forwarded; SensorLink treats this one as final. */
        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            emit(BleEvent.Disconnected(didDisconnectPeripheral.sensorId, isReconnecting = false, message = error?.localizedDescription))
        }

        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            timestamp: CFAbsoluteTime,
            isReconnecting: Boolean,
            error: NSError?,
        ) {
            emit(BleEvent.Disconnected(didDisconnectPeripheral.sensorId, isReconnecting, error?.localizedDescription))
        }

        // ── CBPeripheralDelegate ──────────────────────────────────────────────────────────────

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            if (didDiscoverServices != null) return log("discoverServices failed: ${didDiscoverServices.localizedDescription}")
            val services = peripheral.services?.filterIsInstance<CBService>().orEmpty()
            if (services.isEmpty()) return emit(BleEvent.Ready(peripheral.sensorId, emptyList()))
            pendingServices[peripheral.identifier.UUIDString] = services.size
            services.forEach { peripheral.discoverCharacteristics(null, forService = it) }
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
            val key = peripheral.identifier.UUIDString
            val left = (pendingServices[key] ?: 1) - 1
            pendingServices[key] = left
            if (left > 0) return
            pendingServices.remove(key)
            val chars = peripheral.services?.filterIsInstance<CBService>().orEmpty().flatMap { s ->
                s.characteristics?.filterIsInstance<CBCharacteristic>().orEmpty().map { c ->
                    BleEvent.Characteristic(
                        service = GattUuid(s.UUID.UUIDString),
                        uuid = GattUuid(c.UUID.UUIDString),
                        notify = c.properties and CBCharacteristicPropertyNotify != 0uL,
                        read = c.properties and CBCharacteristicPropertyRead != 0uL,
                    )
                }
            }
            emit(BleEvent.Ready(peripheral.sensorId, chars))
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
            val uuid = GattUuid(didUpdateValueForCharacteristic.UUID.UUIDString)
            if (error != null) return emit(BleEvent.ReadFailed(peripheral.sensorId, uuid, error.localizedDescription))
            val data = didUpdateValueForCharacteristic.value ?: return
            emit(BleEvent.Value(peripheral.sensorId, uuid, data.toByteArray()))
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateNotificationStateForCharacteristic: CBCharacteristic, error: NSError?) {
            log("notify ${didUpdateNotificationStateForCharacteristic.UUID.UUIDString} = ${didUpdateNotificationStateForCharacteristic.isNotifying}${error?.let { " error ${it.localizedDescription}" } ?: ""}")
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    return ByteArray(n).apply { usePinned { memcpy(it.addressOf(0), bytes, length) } }
}
