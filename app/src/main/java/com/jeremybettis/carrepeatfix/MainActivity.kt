package com.jeremybettis.carrepeatfix

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.slider.Slider
import com.jeremybettis.carrepeatfix.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            populateBluetoothDevices()
        } else {
            Toast.makeText(
                this,
                R.string.bt_permission_denied_toast,
                Toast.LENGTH_LONG
            ).show()
        }
        refreshStatusUi()
    }

    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshStatusUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPreferences(this)

        setupControls()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MediaRepeatMonitorService.ACTION_UI_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiUpdateReceiver, filter)
        }

        if (isNotificationListenerGranted()) {
            try {
                android.service.notification.NotificationListenerService.requestRebind(
                    ComponentName(this, MediaRepeatMonitorService::class.java)
                )
            } catch (_: Exception) {
            }
        }

        populateBluetoothDevices()
        refreshStatusUi()
    }

    override fun onPause() {
        try {
            unregisterReceiver(uiUpdateReceiver)
        } catch (_: Exception) {
        }
        super.onPause()
    }

    private fun setupControls() {
        binding.switchServiceEnabled.isChecked = prefs.isServiceEnabled
        binding.switchServiceEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.isServiceEnabled = isChecked
            notifyServiceToRefresh()
            refreshStatusUi()
        }

        binding.btnGrantNotificationAccess.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.open_notif_settings_fallback, Toast.LENGTH_LONG).show()
            }
        }

        binding.btnGrantBluetoothPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        when (prefs.triggerScope) {
            RepeatTriggerScope.SELECTED_DEVICES -> binding.radioScopeSelected.isChecked = true
            RepeatTriggerScope.ANY_BLUETOOTH_AUDIO -> binding.radioScopeAnyBt.isChecked = true
            RepeatTriggerScope.ALWAYS_ACTIVE -> binding.radioScopeAlways.isChecked = true
        }

        binding.radioGroupScope.setOnCheckedChangeListener { _, checkedId ->
            prefs.triggerScope = when (checkedId) {
                R.id.radioScopeSelected -> RepeatTriggerScope.SELECTED_DEVICES
                R.id.radioScopeAnyBt -> RepeatTriggerScope.ANY_BLUETOOTH_AUDIO
                R.id.radioScopeAlways -> RepeatTriggerScope.ALWAYS_ACTIVE
                else -> RepeatTriggerScope.SELECTED_DEVICES
            }
            updateDeviceListVisibility()
            notifyServiceToRefresh()
            refreshStatusUi()
        }

        binding.sliderWatchWindow.value = prefs.watchWindowSeconds.toFloat().coerceIn(15f, 180f)
        updateWatchWindowLabel(prefs.watchWindowSeconds)
        binding.sliderWatchWindow.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            val seconds = value.toInt()
            prefs.watchWindowSeconds = seconds
            updateWatchWindowLabel(seconds)
        })

        binding.switchContinuousConnected.isChecked = prefs.isContinuousWhileConnected
        binding.switchContinuousConnected.setOnCheckedChangeListener { _, isChecked ->
            prefs.isContinuousWhileConnected = isChecked
            notifyServiceToRefresh()
            refreshStatusUi()
        }

        binding.switchCustomActionFallback.isChecked = prefs.useActionClickFallback
        binding.switchCustomActionFallback.setOnCheckedChangeListener { _, isChecked ->
            prefs.useActionClickFallback = isChecked
        }

        binding.btnForceResetNow.setOnClickListener {
            val intent = Intent(MediaRepeatMonitorService.ACTION_FORCE_CHECK_NOW).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
            Toast.makeText(this, R.string.force_reset_sent_toast, Toast.LENGTH_SHORT).show()
        }

        binding.btnSimulateCarConnect.setOnClickListener {
            prefs.isCarCurrentlyConnected = true
            prefs.lastConnectedCarName = "Simulated Car Audio"
            prefs.lastConnectTimestampMs = System.currentTimeMillis()
            val intent = Intent(MediaRepeatMonitorService.ACTION_CAR_BLUETOOTH_CONNECTED).apply {
                setPackage(packageName)
                putExtra(MediaRepeatMonitorService.EXTRA_DEVICE_NAME, "Simulated Car Audio")
                putExtra(MediaRepeatMonitorService.EXTRA_DEVICE_ADDRESS, "00:11:22:33:44:55")
            }
            sendBroadcast(intent)
            refreshStatusUi()
        }

        binding.btnClearLog.setOnClickListener {
            prefs.clearLogs()
            refreshStatusUi()
        }

        updateDeviceListVisibility()
    }

    private fun updateWatchWindowLabel(seconds: Int) {
        binding.tvWatchWindowValue.text = getString(R.string.watch_window_format, seconds)
    }

    private fun updateDeviceListVisibility() {
        val showDevices = prefs.triggerScope == RepeatTriggerScope.SELECTED_DEVICES
        binding.cardDeviceSelection.visibility = if (showDevices) View.VISIBLE else View.GONE
    }

    private fun populateBluetoothDevices() {
        val hasBtPerm = BluetoothConnectionReceiver.hasBluetoothConnectPermission(this)
        binding.btnGrantBluetoothPermission.visibility = if (hasBtPerm) View.GONE else View.VISIBLE

        binding.containerPairedDevices.removeAllViews()
        if (!hasBtPerm) {
            return
        }

        val devices = BluetoothConnectionReceiver.getBondedDevices(this)
        val selected = prefs.selectedDeviceAddresses

        if (devices.isEmpty()) {
            binding.tvNoPairedDevices.visibility = View.VISIBLE
            return
        }

        binding.tvNoPairedDevices.visibility = View.GONE
        for (device in devices) {
            val cb = CheckBox(this).apply {
                text = "${device.name} (${device.address})"
                isChecked = selected.any { it.equals(device.address, ignoreCase = true) }
                textSize = 15f
                setPadding(16, 16, 16, 16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnCheckedChangeListener { _, _ ->
                    prefs.toggleSelectedDevice(device.address)
                    refreshStatusUi()
                }
            }
            binding.containerPairedDevices.addView(cb)
        }
    }

    private fun refreshStatusUi() {
        val notifGranted = isNotificationListenerGranted()
        binding.cardPermissionWarning.visibility = if (notifGranted) View.GONE else View.VISIBLE

        val config = RepeatFixEngine.ProtectionConfig(
            isServiceEnabled = prefs.isServiceEnabled,
            triggerScope = prefs.triggerScope,
            selectedDeviceAddresses = prefs.selectedDeviceAddresses,
            isCarConnected = prefs.isCarCurrentlyConnected,
            lastConnectTimestampMs = prefs.lastConnectTimestampMs,
            isContinuousWhileConnected = prefs.isContinuousWhileConnected,
            watchWindowSeconds = prefs.watchWindowSeconds
        )
        val isProtecting = notifGranted &&
            RepeatFixEngine.isProtectionActive(config, System.currentTimeMillis())

        val statusTitle = when {
            !notifGranted -> getString(R.string.status_action_required)
            !prefs.isServiceEnabled -> getString(R.string.status_paused)
            isProtecting -> getString(R.string.status_actively_guarding)
            else -> getString(R.string.status_standby_waiting)
        }
        binding.tvStatusHeadline.text = statusTitle

        val lastCar = prefs.lastConnectedCarName ?: getString(R.string.none_yet)
        val connState = if (prefs.isCarCurrentlyConnected) {
            getString(R.string.connected_to_format, lastCar)
        } else {
            getString(R.string.last_car_format, lastCar)
        }
        binding.tvStatusSubtitle.text = getString(
            R.string.status_subtitle_format,
            connState,
            prefs.fixCount
        )

        val logs = prefs.getLogs()
        binding.tvActivityLog.text = if (logs.isEmpty()) {
            getString(R.string.no_events_logged_yet)
        } else {
            logs.joinToString("\n\n") { "• [${it.formattedTime()}] ${it.message}" }
        }
    }

    private fun isNotificationListenerGranted(): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledPackages.contains(packageName)
    }

    private fun notifyServiceToRefresh() {
        val intent = Intent(MediaRepeatMonitorService.ACTION_REFRESH_MONITORS).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}
