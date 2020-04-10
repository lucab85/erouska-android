package cz.covid19cz.erouska.ui.main

import android.R.id
import android.app.Activity
import android.content.*
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.NavDestination
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import androidx.navigation.ui.NavigationUI
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.ActivityResult
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import cz.covid19cz.erouska.AppConfig
import cz.covid19cz.erouska.BuildConfig
import cz.covid19cz.erouska.R
import cz.covid19cz.erouska.R.string
import cz.covid19cz.erouska.bt.BluetoothRepository
import cz.covid19cz.erouska.databinding.ActivityMainBinding
import cz.covid19cz.erouska.ext.hasLocationPermission
import cz.covid19cz.erouska.ext.isLocationEnabled
import cz.covid19cz.erouska.service.CovidService
import cz.covid19cz.erouska.ui.base.BaseActivity
import cz.covid19cz.erouska.utils.CustomTabHelper
import kotlinx.android.synthetic.main.activity_main.*
import org.koin.android.ext.android.inject

class MainActivity :
    BaseActivity<ActivityMainBinding, MainVM>(R.layout.activity_main, MainVM::class), InstallStateUpdatedListener {

    companion object {
        private const val REQUEST_CODE_IMMEDIATE_UPDATE = 2000
    }

    private val localBroadcastManager by inject<LocalBroadcastManager>()
    private val bluetoothRepository by inject<BluetoothRepository>()
    private val customTabsConnection = object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(
            name: ComponentName,
            client: CustomTabsClient
        ) {
            connectedToCustomTabsService = true
            client.warmup(0)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connectedToCustomTabsService = false
        }
    }
    private var connectedToCustomTabsService = false

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                when (it.action) {
                    CovidService.ACTION_MASK_STARTED -> viewModel.serviceRunning.value = true
                    CovidService.ACTION_MASK_STOPPED -> viewModel.serviceRunning.value = false
                }
            }
        }
    }
    private lateinit var appUpdateManager: AppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        setSupportActionBar(toolbar)
        registerServiceStateReceivers()
        findNavController(R.id.nav_host_fragment).let {
            bottom_navigation.setOnNavigationItemSelectedListener { item ->
                navigate(
                    item.itemId,
                    navOptions = NavOptions.Builder().setPopUpTo(R.id.nav_graph, false).build()
                )
                true
            }
            it.addOnDestinationChangedListener { _, destination, arguments ->
                updateTitle(destination)
                updateBottomNavigation(destination, arguments)
            }
        }

        appUpdateManager = AppUpdateManagerFactory.create(this)
        appUpdateManager.appUpdateInfo.addOnSuccessListener {
            if (AppConfig.isImmediateUpdateOn &&
                it.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                it.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) &&
                BuildConfig.VERSION_CODE < AppConfig.minSupportedVersionCode &&
                it.availableVersionCode() > BuildConfig.VERSION_CODE
            ) {
                requestUpdate(it)
            }
        }
        viewModel.serviceRunning.observe(this, Observer { isRunning ->
            ContextCompat.getColor(
                this,
                if (isRunning && passesRequirements()) R.color.green else R.color.red
            ).let {
                bottom_navigation.getOrCreateBadge(R.id.nav_dashboard).backgroundColor = it
            }
        })

        viewModel.serviceRunning.value = CovidService.isRunning(this)
    }

    override fun onDestroy() {
        localBroadcastManager.unregisterReceiver(serviceStateReceiver)
        appUpdateManager.unregisterListener(this)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.nav_about -> {
                false
            }
            R.id.nav_help -> {
                navigate(R.id.nav_help, Bundle().apply { putBoolean("fullscreen", true) })
                true
            }
            else -> {
                NavigationUI.onNavDestinationSelected(
                    item,
                    findNavController(R.id.nav_host_fragment)
                ) || super.onOptionsItemSelected(item)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (CustomTabHelper.chromePackageName != null) {
            CustomTabsClient.bindCustomTabsService(
                this,
                CustomTabHelper.chromePackageName,
                customTabsConnection
            )
        }
    }

    override fun onStop() {
        if (connectedToCustomTabsService) {
            unbindService(customTabsConnection)
        }
        super.onStop()
    }

    private fun updateTitle(destination: NavDestination) {
        if (destination.label != null) {
            title = destination.label
        } else {
            setTitle(R.string.app_name)
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdateManager.appUpdateInfo.addOnSuccessListener {
            if (it.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                appUpdateManager.startUpdateFlowForResult(
                    it,
                    AppUpdateType.IMMEDIATE,
                    this,
                    REQUEST_CODE_IMMEDIATE_UPDATE
                )
            }
        }
    }

    override fun onStateUpdate(state: InstallState?) {
        if (state?.installStatus() == InstallStatus.DOWNLOADED) {
            notifyUserAboutUpdate()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_IMMEDIATE_UPDATE) {
            when (resultCode) {
                Activity.RESULT_OK -> { //  handle user's approval
                }
                Activity.RESULT_CANCELED -> { //  handle user's rejection
                }
                ActivityResult.RESULT_IN_APP_UPDATE_FAILED -> { //  handle update failure
                }
            }
        }
    }

    private fun updateBottomNavigation(
        destination: NavDestination,
        arguments: Bundle?
    ) {
        bottom_navigation.visibility =
            if (destination.arguments["fullscreen"]?.defaultValue == true
                || arguments?.getBoolean("fullscreen") == true
            ) {
                GONE
            } else {
                VISIBLE
            }
    }

    private fun registerServiceStateReceivers() {
        localBroadcastManager.registerReceiver(
            serviceStateReceiver,
            IntentFilter(CovidService.ACTION_MASK_STARTED)
        )
        localBroadcastManager.registerReceiver(
            serviceStateReceiver,
            IntentFilter(CovidService.ACTION_MASK_STOPPED)
        )
    }

    private fun passesRequirements(): Boolean {
        return bluetoothRepository.isBtEnabled() && isLocationEnabled() && hasLocationPermission()
    }


    private fun requestUpdate(appUpdateInfo: AppUpdateInfo?) {
        appUpdateManager.startUpdateFlowForResult(
            appUpdateInfo,
            AppUpdateType.IMMEDIATE,
            this,
            REQUEST_CODE_IMMEDIATE_UPDATE
        )
    }

    private fun notifyUserAboutUpdate() {
        Snackbar
            .make(findViewById<View>(id.content), string.restart_to_update, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.action_restart) {
                appUpdateManager.completeUpdate()
                appUpdateManager.unregisterListener(this)
            }
            .show()
    }

}
