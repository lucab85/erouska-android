package cz.covid19cz.erouska.bt

import android.app.PendingIntent
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.databinding.ObservableArrayList
import cz.covid19cz.erouska.AppConfig
import cz.covid19cz.erouska.bt.entity.ObservableScanSession
import cz.covid19cz.erouska.bt.entity.ScanSession
import cz.covid19cz.erouska.db.DatabaseRepository
import cz.covid19cz.erouska.db.ScanDataEntity
import cz.covid19cz.erouska.db.SharedPrefsRepository
import cz.covid19cz.erouska.ext.*
import cz.covid19cz.erouska.receiver.BtScanReceiver
import cz.covid19cz.erouska.utils.L
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import no.nordicsemi.android.support.v18.scanner.*
import no.nordicsemi.android.support.v18.scanner.ScanSettings.MATCH_MODE_STICKY
import java.util.*
import kotlin.collections.HashMap


class BluetoothRepository(
    val context: Context,
    private val db: DatabaseRepository,
    private val btManager: BluetoothManager,
    private val prefs : SharedPrefsRepository
) {

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("1440dd68-67e4-11ea-bc55-0242ac130003")
        val GATT_CHARACTERISTIC_UUID: UUID = UUID.fromString("9472fbde-04ff-4fff-be1c-b9d3287e8f28")
        const val APPLE_MANUFACTURER_ID = 76
    }

    private var iosPendingIntent: PendingIntent? = null
    private var androidPendingIntent: PendingIntent? = null
    private val scanResultsMap = HashMap<String, ScanSession>()
    private var discoveredIosDevices: MutableMap<String, ScanSession> = mutableMapOf()
    val scanResultsList = ObservableArrayList<ScanSession>()

    private var isAdvertising = false
    private var isScanning = false

    private var gattFailDisposable: Disposable? = null

    val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onScanResult(result)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            BluetoothLeScannerCompat.getScanner().stopScan(this)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach {
                onScanResult(it)
            }
        }
    }

    val scanIosOnBackgroundCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onScanIosOnBackgroundResult(result)
        }

        override fun onScanFailed(errorCode: Int) {
            BluetoothLeScannerCompat.getScanner().stopScan(this)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach {
                onScanIosOnBackgroundResult(it)
            }
        }
    }

    private val advertisingCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            L.d("BLE advertising started.")
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            L.e("BLE advertising failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val mac = gatt.device.address

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                L.d("GATT connected. Mac: ${gatt.device.address}")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnectFromGatt(gatt)
                L.d("GATT disconnected. Mac: ${gatt.device.address}")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (GATT_CHARACTERISTIC_UUID == characteristic?.uuid) {
                val tuid = characteristic.value?.asHexLower
                val mac = gatt.device?.address

                if (tuid != null) {
                    L.d("GATT TUID found. Mac ${gatt.device.address}. TUID: $tuid")
                    discoveredIosDevices[mac]?.let { session ->
                            session.deviceId = tuid
                            scanResultsMap[tuid] = session
                            session
                    }
                } else {
                    L.e("GATT TUID not found on $mac")
                }
                disconnectFromGatt(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnectFromGatt(gatt)
                return
            }
            val characteristic =
                gatt.getService(SERVICE_UUID)?.getCharacteristic(GATT_CHARACTERISTIC_UUID)
            if (characteristic != null) {
                gatt.readCharacteristic(characteristic)
            } else {
                disconnectFromGatt(gatt)
            }
        }
    }



    fun hasBle(c: Context): Boolean {
        return c.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    fun isBtEnabled(): Boolean {
        return btManager.isBluetoothEnabled()
    }

    fun startScanning() {
        if (isScanning) {
            stopScanning()
        }

        if (!isBtEnabled()) {
            L.d("Bluetooth disabled, can't start scanning")
            return
        }

        L.d("Starting BLE scanning in mode: ${AppConfig.scanMode}")

        val androidScannerSettings: ScanSettings = ScanSettings.Builder()
            .setLegacy(true)
            .setScanMode(AppConfig.scanMode)
            .setUseHardwareFilteringIfSupported(true)
            .setUseHardwareBatchingIfSupported(true)
            .setMatchMode(MATCH_MODE_STICKY)
            .setReportDelay(15000)
            .build()

        val iOSScannerSettings: ScanSettings = ScanSettings.Builder()
            .setLegacy(false)
            .setScanMode(AppConfig.scanMode)
            .setUseHardwareFilteringIfSupported(true)
            .setUseHardwareBatchingIfSupported(true)
            .setReportDelay(15000)
            .setMatchMode(MATCH_MODE_STICKY)
            .build()

        val androidIntent = Intent(context, BtScanReceiver::class.java)
        androidIntent.action = BtScanReceiver.ACTION_ANDROID
        androidPendingIntent =
            PendingIntent.getBroadcast(context, 1, androidIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        val iosIntent = Intent(context, BtScanReceiver::class.java)
        iosIntent.action = BtScanReceiver.ACTION_IOS
        iosPendingIntent =
            PendingIntent.getBroadcast(context, 2, iosIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        BluetoothLeScannerCompat.getScanner().startScan(
            listOf(
                ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            ),
            androidScannerSettings,
            context,
            androidPendingIntent!!
        )

        BluetoothLeScannerCompat.getScanner().startScan(
            listOf(
                ScanFilter.Builder()
                    .setManufacturerData(APPLE_MANUFACTURER_ID, byteArrayOf(), byteArrayOf())
                    .build()
            ),
            iOSScannerSettings,
            context,
            iosPendingIntent!!
        )
        isScanning = true
    }

    fun stopScanning() {
        L.d("Stopping BLE scanning")
        if (btManager.isBluetoothEnabled()) {
            isScanning = false
            iosPendingIntent?.let { BluetoothLeScannerCompat.getScanner().stopScan(context, it) }
            androidPendingIntent?.let { BluetoothLeScannerCompat.getScanner().stopScan(context, it) }

        }
        saveDataAndClearScanResults()
        gattFailDisposable?.dispose()
    }

    private fun saveDataAndClearScanResults() {
        L.d("Saving data to database")
        Observable.just(scanResultsMap.values.toTypedArray())
            .map {
                it.map { scanSession -> scanSession.fold(1000 * 120) }.flatten()
                    .forEach { item ->
                        with (item) {
                            calculate()
                            val scanResult = ScanDataEntity(
                                0,
                                deviceId,
                                timestampStart,
                                timestampEnd,
                                avgRssi,
                                medRssi,
                                rssiCount
                            )
                            L.d("Saving: $scanResult")
                            db.add(scanResult)
                        }
                    }
                dbCleanup()
                it.size
            }.execute({
                L.d("$it records saved")
                clearScanResults()
            }, { L.e(it) })
    }

    private fun onScanResult(result: ScanResult) {
        result.scanRecord?.bytes?.let { bytes ->
            if (isServiceUUIDMatch(result) || canBeIosOnBackground(result.scanRecord)) {
                val deviceId = getBuidFromAdvertising(result.scanRecord!!)
                if (deviceId != null) {
                    // It's time to handle Android Device
                    handleAndroidDevice(result, deviceId)
                } else {
                    // It's time to handle iOS Device
                    handleIosDevice(result)
                }
            }
        }
    }

    private fun onScanIosOnBackgroundResult(result: ScanResult) {
        result.scanRecord?.let {
            if (!isServiceUUIDMatch(result) && canBeIosOnBackground(it)) {
                // It's time to handle iOS Device in background
                handleIosDevice(result)
            }
        }
    }

    private fun isServiceUUIDMatch(result: ScanResult): Boolean {
        return result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true
    }

    /**
     * iOS doesn't send UUID with screen off and app in background so we have to find another way
     * how to detect if there is an iOS device on the other side.
     *
     * iOS devices send manufacturer specific data that look something like this:
     * 0x4C000100000000000000000200000000000000
     *
     * This checks for the apple inc. key and then matches the data against the above
     */
    private fun canBeIosOnBackground(scanRecord: ScanRecord?): Boolean {
        return scanRecord?.manufacturerSpecificData?.get(APPLE_MANUFACTURER_ID)?.let { data ->
            data.size > 10 && data[0] == 0x01.toByte() && data[1] == 0x00.toByte() && data[9] == 0x02.toByte()
        } ?: false
    }

    private fun handleAndroidDevice(result: ScanResult, deviceId: String) {
        if (!scanResultsMap.containsKey(deviceId)) {
            val newEntity = ObservableScanSession(deviceId, result.device.address)
            newEntity.addRssi(result.rssi)
            scanResultsList.add(newEntity)
            scanResultsMap[deviceId] = newEntity
            L.d("Found new Android device: $deviceId")
        }

        scanResultsMap[deviceId]?.let { entity ->
            entity.addRssi(result.rssi)
            L.d("Device (Android) $deviceId - RSSI ${result.rssi}")
        }
    }

    private fun handleIosDevice(result: ScanResult) {
        if (!discoveredIosDevices.containsKey(result.device.address)) {
            L.d("Found new iOS: Mac: ${result.device.address}")
            registerIOSDevice(result)
        } else {
            discoveredIosDevices[result.device.address]?.let {
                if (it.deviceId == ScanSession.UNKNOWN_TUID) {
                    if (it.gattAttemptTimestamp + 5000 < System.currentTimeMillis()) {
                        getTuidFromGatt(it)
                    }
                } else if (!scanResultsMap.containsKey(it.deviceId)) {
                    scanResultsMap[it.deviceId] = it
                } else {
                    if (!scanResultsList.contains(it)) {
                        scanResultsList.add(it)
                    }
                }
                it.addRssi(result.rssi)
                L.d("Device (iOS) ${it.deviceId} - RSSI ${result.rssi}")
            }
            return
        }
    }

    private fun registerIOSDevice(result: ScanResult) {
        val mac = result.device.address
        val session = ObservableScanSession(mac = mac)
        session.addRssi(result.rssi)
        discoveredIosDevices[mac] = session
        scanResultsList.add(session)
    }

    private fun getTuidFromGatt(session: ScanSession) {
        connectToGatt(session.mac)
        session.gattAttemptTimestamp = System.currentTimeMillis()
    }

    private fun connectToGatt(mac: String) {
        L.d("Connecting to GATT . Mac:${mac}")
        val device = btManager.adapter?.getRemoteDevice(mac)
        device?.connectGatt(context, false, gattCallback)
    }

    private fun disconnectFromGatt(gatt: BluetoothGatt) {
        L.d("Disconnecting from GATT . Mac:${gatt.device.address}")
        gatt.disconnect()
        gatt.close()
    }

    private fun getBuidFromAdvertising(scanRecord: ScanRecord): String? {
        scanRecord.serviceData?.apply {
            return get(ParcelUuid(SERVICE_UUID))?.asHexLower ?: get(keys.first())?.asHexLower
        }
        return null
    }

    fun clearScanResults() {
        scanResultsList.clear()
        scanResultsMap.clear()
        clearIosDevices()
    }

    private fun clearIosDevices() {
        // Don't clear whole iOS device cache to preventing DDOS GATT, but remove UNKNOWN devices
        discoveredIosDevices = discoveredIosDevices.filterValues {
            it.deviceId != ScanSession.UNKNOWN_TUID
        }.apply {
            forEach { it.value.reset() }
        }.toMutableMap()
    }

    fun supportsAdvertising(): Boolean {
        return btManager.adapter?.isMultipleAdvertisementSupported ?: false
    }

    fun startAdvertising(tuid: String) {
        val power = AppConfig.advertiseTxPower

        if (isAdvertising) {
            stopAdvertising()
        }

        if (!isBtEnabled()) {
            L.d("Bluetooth disabled, can't start advertising")
            return
        }

        L.d("Starting BLE advertising with power $power")

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AppConfig.advertiseMode)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(power)
            .build()

        val parcelUuid = ParcelUuid(SERVICE_UUID)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(parcelUuid)
            .build()

        val scanData = AdvertiseData.Builder()
            .addServiceData(parcelUuid, tuid.hexAsByteArray).build()

        btManager.adapter?.bluetoothLeAdvertiser?.startAdvertising(
            settings,
            data,
            scanData,
            advertisingCallback
        )
    }

    fun stopAdvertising() {
        L.d("Stopping BLE advertising")
        isAdvertising = false
        btManager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertisingCallback)
    }

    private fun dbCleanup(){
        if (System.currentTimeMillis() - prefs.getLastDbCleanupTimestamp() > 24.hoursToMilis()) {
            L.d("Deleting data older than ${AppConfig.persistDataDays} days")
            val rows = db.deleteOldData()
            L.d("$rows records deleted")
            prefs.saveLastDbCleanupTimestamp(System.currentTimeMillis())
        }
    }

    data class GattConnectionQueueEntry(val macAddress: String, var isRunning: Boolean = false)
}