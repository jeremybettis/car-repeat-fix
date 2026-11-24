package com.jeremybettis.carrepeatfix

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RepeatTriggerScope {
    SELECTED_DEVICES,
    ANY_BLUETOOTH_AUDIO,
    ALWAYS_ACTIVE
}

data class LogEntry(
    val timestampMs: Long,
    val message: String
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestampMs))
    }
}

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    var triggerScope: RepeatTriggerScope
        get() {
            val raw = prefs.getString(KEY_TRIGGER_SCOPE, RepeatTriggerScope.SELECTED_DEVICES.name)
            return try {
                RepeatTriggerScope.valueOf(raw ?: RepeatTriggerScope.SELECTED_DEVICES.name)
            } catch (_: IllegalArgumentException) {
                RepeatTriggerScope.SELECTED_DEVICES
            }
        }
        set(value) = prefs.edit().putString(KEY_TRIGGER_SCOPE, value.name).apply()

    var selectedDeviceAddresses: Set<String>
        get() = prefs.getStringSet(KEY_SELECTED_DEVICES, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SELECTED_DEVICES, value.toSet()).apply()

    /**
     * Watch window in seconds after Bluetooth connects.
     * If 0, continuous protection is active as long as the car Bluetooth is connected.
     */
    var watchWindowSeconds: Int
        get() = prefs.getInt(KEY_WATCH_WINDOW_SECONDS, DEFAULT_WATCH_WINDOW_SECONDS)
        set(value) = prefs.edit().putInt(KEY_WATCH_WINDOW_SECONDS, value).apply()

    var isContinuousWhileConnected: Boolean
        get() = prefs.getBoolean(KEY_CONTINUOUS_WHILE_CONNECTED, true)
        set(value) = prefs.edit().putBoolean(KEY_CONTINUOUS_WHILE_CONNECTED, value).apply()

    var useActionClickFallback: Boolean
        get() = prefs.getBoolean(KEY_ACTION_CLICK_FALLBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_ACTION_CLICK_FALLBACK, value).apply()

    var lastConnectedCarAddress: String?
        get() = prefs.getString(KEY_LAST_CONNECTED_CAR_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_LAST_CONNECTED_CAR_ADDRESS, value).apply()

    var lastConnectedCarName: String?
        get() = prefs.getString(KEY_LAST_CONNECTED_CAR_NAME, null)
        set(value) = prefs.edit().putString(KEY_LAST_CONNECTED_CAR_NAME, value).apply()

    var lastConnectTimestampMs: Long
        get() = prefs.getLong(KEY_LAST_CONNECT_TIMESTAMP_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CONNECT_TIMESTAMP_MS, value).apply()

    var isCarCurrentlyConnected: Boolean
        get() = prefs.getBoolean(KEY_CAR_CURRENTLY_CONNECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_CAR_CURRENTLY_CONNECTED, value).apply()

    var fixCount: Int
        get() = prefs.getInt(KEY_FIX_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_FIX_COUNT, value).apply()

    fun toggleSelectedDevice(address: String): Boolean {
        val current = selectedDeviceAddresses.toMutableSet()
        val added = if (current.contains(address)) {
            current.remove(address)
            false
        } else {
            current.add(address)
            true
        }
        selectedDeviceAddresses = current
        return added
    }

    fun appendLog(message: String, nowMs: Long = System.currentTimeMillis()) {
        val existing = getLogs().toMutableList()
        existing.add(0, LogEntry(nowMs, message))
        val trimmed = existing.take(MAX_LOG_ENTRIES)
        val serialized = trimmed.joinToString("\n") { "${it.timestampMs}|${it.message.replace("\n", " ")}" }
        prefs.edit().putString(KEY_EVENT_LOGS, serialized).apply()
    }

    fun getLogs(): List<LogEntry> {
        val raw = prefs.getString(KEY_EVENT_LOGS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val idx = line.indexOf('|')
            if (idx <= 0) null
            else {
                val ts = line.substring(0, idx).toLongOrNull() ?: return@mapNotNull null
                val msg = line.substring(idx + 1)
                LogEntry(ts, msg)
            }
        }
    }

    fun clearLogs() {
        prefs.edit().remove(KEY_EVENT_LOGS).apply()
    }

    companion object {
        const val PREFS_NAME = "car_repeat_fix_prefs"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_TRIGGER_SCOPE = "trigger_scope"
        private const val KEY_SELECTED_DEVICES = "selected_devices"
        private const val KEY_WATCH_WINDOW_SECONDS = "watch_window_seconds"
        private const val KEY_CONTINUOUS_WHILE_CONNECTED = "continuous_while_connected"
        private const val KEY_ACTION_CLICK_FALLBACK = "action_click_fallback"
        private const val KEY_LAST_CONNECTED_CAR_ADDRESS = "last_connected_car_address"
        private const val KEY_LAST_CONNECTED_CAR_NAME = "last_connected_car_name"
        private const val KEY_LAST_CONNECT_TIMESTAMP_MS = "last_connect_timestamp_ms"
        private const val KEY_CAR_CURRENTLY_CONNECTED = "car_currently_connected"
        private const val KEY_FIX_COUNT = "fix_count"
        private const val KEY_EVENT_LOGS = "event_logs"

        const val DEFAULT_WATCH_WINDOW_SECONDS = 60
        private const val MAX_LOG_ENTRIES = 60
    }
}
