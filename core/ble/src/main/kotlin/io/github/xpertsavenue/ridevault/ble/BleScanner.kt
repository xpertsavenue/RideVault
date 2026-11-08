package io.github.xpertsavenue.ridevault.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import io.github.xpertsavenue.ridevault.device.ScanInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class BleException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object BlePermissions {
    /** Benötigte Laufzeit-Berechtigungen; ab Android 12 ohne Standort. */
    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
}

class BleScanner(private val context: Context) {

    /** Liefert jeden Scan-Treffer (auch wiederholte). Der Aufrufer begrenzt die Dauer und dedupliziert. */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<ScanInfo> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            close(BleException("Bluetooth is turned off"))
            return@callbackFlow
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            close(BleException("BLE scanner unavailable"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.toScanInfo())
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it.toScanInfo()) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleException("Scan failed (code $errorCode)"))
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toScanInfo() = ScanInfo(
        name = scanRecord?.deviceName ?: runCatching { device.name }.getOrNull(),
        address = device.address,
        serviceUuids = scanRecord?.serviceUuids?.map { it.toString() }.orEmpty(),
    )
}
