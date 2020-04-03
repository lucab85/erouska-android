package cz.covid19cz.erouska.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cz.covid19cz.erouska.bt.BluetoothRepository
import no.nordicsemi.android.support.v18.scanner.ScanResult
import org.koin.core.KoinComponent
import org.koin.core.inject

class IOSScanReceiver: BroadcastReceiver() , KoinComponent {

    private val bluetoothRepository by inject<BluetoothRepository>()

    override fun onReceive(context: Context?, intent: Intent?) {
        intent?.action?.let {
            when (it) {
                BtScanReceiver.ACTION_ANDROID -> bluetoothRepository.scanCallback.onBatchScanResults(intent.extras!!.get(
                    BtScanReceiver.SCAN_RECORDS_EXTRA_KEY
                ) as List<ScanResult>)
                BtScanReceiver.ACTION_IOS -> bluetoothRepository.scanIosOnBackgroundCallback.onBatchScanResults(intent.extras!!.get(
                    BtScanReceiver.SCAN_RECORDS_EXTRA_KEY
                ) as List<ScanResult>)
            }
        }    }

}