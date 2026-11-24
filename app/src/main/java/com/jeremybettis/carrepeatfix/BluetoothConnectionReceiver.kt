package com.jeremybettis.carrepeatfix

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BluetoothConnectionReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = AppPreferences(context)
        if (!prefs.isServiceEnabled) return

        val action = intent.action ?: return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        val address = try {
            device?.address
        } catch (_: SecurityException) {
            null
        }
        val name = getSafeDeviceName(context, device) ?: address ?: "Car Bluetooth"

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (isTargetDevice(address, prefs)) {
                    markCarConnected(context, prefs, address, name)
                }
            }
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (state == BluetoothProfile.STATE_CONNECTED && isTargetDevice(address, prefs)) {
                    markCarConnected(context, prefs, address, name)
                } else if (state == BluetoothProfile.STATE_DISCONNECTED && isTargetDevice(address, prefs)) {
                    markCarDisconnected(context, prefs, address, name)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (isTargetDevice(address, prefs)) {
                    markCarDisconnected(context, prefs, address, name)
                }
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                // Request NotificationListenerService rebind / refresh
                context.sendBroadcast(
                    Intent(MediaRepeatMonitorService.ACTION_REFRESH_MONITORS)
                        .setPackage(context.packageName)
                )
            }
        }
    }

    private fun isTargetDevice(address: String?, prefs: AppPreferences): Boolean {
        return RepeatFixEngine.isTargetCarDevice(
            address = address,
            triggerScope = prefs.triggerScope,
            selectedAddresses = prefs.selectedDeviceAddresses
        )
    }

    private fun markCarConnected(
        context: Context,
        prefs: AppPreferences,
        address: String?,
        name: String
    ) {
        Log.i(TAG, "Target car Bluetooth connected: $name ($address)")
        prefs.isCarCurrentlyConnected = true
        prefs.lastConnectedCarAddress = address
        prefs.lastConnectedCarName = name
        prefs.lastConnectTimestampMs = System.currentTimeMillis()

        val serviceIntent = Intent(MediaRepeatMonitorService.ACTION_CAR_BLUETOOTH_CONNECTED).apply {
            setPackage(context.packageName)
            putExtra(MediaRepeatMonitorService.EXTRA_DEVICE_NAME, name)
            putExtra(MediaRepeatMonitorService.EXTRA_DEVICE_ADDRESS, address ?: "")
        }
        context.sendBroadcast(serviceIntent)
    }

    private fun markCarDisconnected(
        context: Context,
        prefs: AppPreferences,
        address: String?,
        name: String
    ) {
        Log.i(TAG, "Target car Bluetooth disconnected: $name ($address)")
        prefs.isCarCurrentlyConnected = false

        val serviceIntent = Intent(MediaRepeatMonitorService.ACTION_CAR_BLUETOOTH_DISCONNECTED).apply {
            setPackage(context.packageName)
            putExtra(MediaRepeatMonitorService.EXTRA_DEVICE_NAME, name)
            putExtra(MediaRepeatMonitorService.EXTRA_DEVICE_ADDRESS, address ?: "")
        }
        context.sendBroadcast(serviceIntent)
    }

    companion object {
        private const val TAG = "BtConnectionReceiver"

        fun hasBluetoothConnectPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        @SuppressLint("MissingPermission")
        fun getSafeDeviceName(context: Context, device: BluetoothDevice?): String? {
            if (device == null) return null
            if (!hasBluetoothConnectPermission(context)) return null
            return try {
                device.name?.takeIf { it.isNotBlank() }
            } catch (_: SecurityException) {
                null
            }
        }

        data class BondedBtDevice(
            val name: String,
            val address: String
        )

        @SuppressLint("MissingPermission")
        fun getBondedDevices(context: Context): List<BondedBtDevice> {
            if (!hasBluetoothConnectPermission(context)) return emptyList()
            return try {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter: BluetoothAdapter? = manager?.adapter
                val bonded = adapter?.bondedDevices ?: emptySet()
                bonded.map { dev ->
                    BondedBtDevice(
                        name = dev.name?.takeIf { it.isNotBlank() } ?: "Unnamed Bluetooth Device",
                        address = dev.address
                    )
                }.sortedBy { it.name.lowercase() }
            } catch (_: SecurityException) {
                emptyList()
            }
        }
    }
}
