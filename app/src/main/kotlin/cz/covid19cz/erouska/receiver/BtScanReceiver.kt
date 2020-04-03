package cz.covid19cz.erouska.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cz.covid19cz.erouska.bt.BluetoothRepository
import no.nordicsemi.android.support.v18.scanner.ScanResult
import org.koin.core.KoinComponent
import org.koin.core.inject

class BtScanReceiver: BroadcastReceiver(), KoinComponent {

    private val bluetoothRepository by inject<BluetoothRepository>()

    companion object {
        const val ACTION_ANDROID = "cz.erouska.ACTION_FOUND_ANDROID"
        const val ACTION_IOS = "cz.erouska.ACTION_FOUND_IOS"
        const val SCAN_RECORDS_EXTRA_KEY = "android.bluetooth.le.extra.LIST_SCAN_RESULT"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        intent?.action?.let {
            when (it) {
                ACTION_ANDROID -> bluetoothRepository.scanCallback.onBatchScanResults(intent.extras!!.get(SCAN_RECORDS_EXTRA_KEY) as List<ScanResult>)
                ACTION_IOS -> bluetoothRepository.scanIosOnBackgroundCallback.onBatchScanResults(intent.extras!!.get(SCAN_RECORDS_EXTRA_KEY) as List<ScanResult>)
            }
        }
    }
}