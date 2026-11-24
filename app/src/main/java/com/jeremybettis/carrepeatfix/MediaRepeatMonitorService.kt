package com.jeremybettis.carrepeatfix

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log

class MediaRepeatMonitorService : NotificationListenerService() {

    private lateinit var prefs: AppPreferences
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaSessionManager: MediaSessionManager? = null

    private val activeCompatControllers = mutableMapOf<String, MediaControllerCompat>()
    private val activeCompatCallbacks = mutableMapOf<String, MediaControllerCompat.Callback>()
    private val lastFixTimestampByPackage = mutableMapOf<String, Long>()

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            syncControllers(controllers ?: emptyList())
        }

    private val periodicSweepRunnable = object : Runnable {
        override fun run() {
            if (isProtectionCurrentlyActive()) {
                inspectAllSessionsAndNotifications(reason = "sweep")
                mainHandler.postDelayed(this, SWEEP_INTERVAL_MS)
            }
        }
    }

    private val internalCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CAR_BLUETOOTH_CONNECTED -> {
                    val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Car Bluetooth"
                    val deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: ""
                    prefs.appendLog("Bluetooth connected: $deviceName ($deviceAddress) — watching for Repeat One")
                    broadcastUiUpdate()
                    scheduleBurstChecks()
                }
                ACTION_CAR_BLUETOOTH_DISCONNECTED -> {
                    val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Car Bluetooth"
                    prefs.appendLog("Bluetooth disconnected: $deviceName")
                    broadcastUiUpdate()
                }
                ACTION_FORCE_CHECK_NOW -> {
                    val fixed = inspectAllSessionsAndNotifications(reason = "manual", forceEvenIfOutsideWindow = true)
                    if (fixed == 0) {
                        sendRepeatOffToAllActiveSessions(reason = "manual reset")
                    }
                    broadcastUiUpdate()
                }
                ACTION_REFRESH_MONITORS -> {
                    refreshActiveSessions()
                    if (isProtectionCurrentlyActive()) {
                        scheduleBurstChecks()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

        val filter = IntentFilter().apply {
            addAction(ACTION_CAR_BLUETOOTH_CONNECTED)
            addAction(ACTION_CAR_BLUETOOTH_DISCONNECTED)
            addAction(ACTION_FORCE_CHECK_NOW)
            addAction(ACTION_REFRESH_MONITORS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(internalCommandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(internalCommandReceiver, filter)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListenerService connected")
        registerSessionListener()
        if (isProtectionCurrentlyActive()) {
            scheduleBurstChecks()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        unregisterSessionListener()
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        unregisterSessionListener()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(internalCommandReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val extras = sbn.notification?.extras ?: return
        val hasMediaSession = extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)
        if (hasMediaSession) {
            refreshActiveSessions()
            if (isProtectionCurrentlyActive()) {
                inspectStatusBarNotification(sbn, reason = "notification")
            }
        }
    }

    private fun registerSessionListener() {
        try {
            val component = ComponentName(this, MediaRepeatMonitorService::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                component,
                mainHandler
            )
            refreshActiveSessions()
        } catch (se: SecurityException) {
            Log.w(TAG, "Cannot register session listener yet: ${se.message}")
        }
    }

    private fun unregisterSessionListener() {
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {
        }
        clearCompatCallbacks()
    }

    private fun refreshActiveSessions() {
        try {
            val component = ComponentName(this, MediaRepeatMonitorService::class.java)
            val controllers = mediaSessionManager?.getActiveSessions(component) ?: emptyList()
            syncControllers(controllers)
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException getting active sessions: ${se.message}")
        }
    }

    private fun clearCompatCallbacks() {
        for ((pkg, controller) in activeCompatControllers) {
            activeCompatCallbacks[pkg]?.let { callback ->
                try {
                    controller.unregisterCallback(callback)
                } catch (_: Exception) {
                }
            }
        }
        activeCompatControllers.clear()
        activeCompatCallbacks.clear()
    }

    private fun syncControllers(frameworkControllers: List<MediaController>) {
        clearCompatCallbacks()

        for (fwController in frameworkControllers) {
            val pkg = fwController.packageName ?: continue
            try {
                val compatToken = MediaSessionCompat.Token.fromToken(fwController.sessionToken)
                val compatController = MediaControllerCompat(this, compatToken)
                val callback = object : MediaControllerCompat.Callback() {
                    override fun onRepeatModeChanged(repeatMode: Int) {
                        if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) {
                            handleRepeatOneDetected(compatController, pkg, "onRepeatModeChanged")
                        }
                    }

                    override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
                        checkControllerForRepeatOne(compatController, pkg, "onPlaybackStateChanged")
                    }

                    override fun onExtrasChanged(extras: Bundle?) {
                        checkControllerForRepeatOne(compatController, pkg, "onExtrasChanged")
                    }
                }
                compatController.registerCallback(callback, mainHandler)
                activeCompatControllers[pkg] = compatController
                activeCompatCallbacks[pkg] = callback

                if (isProtectionCurrentlyActive()) {
                    checkControllerForRepeatOne(compatController, pkg, "sessionSync")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to attach MediaControllerCompat for $pkg: ${e.message}")
            }
        }
    }

    private fun scheduleBurstChecks() {
        mainHandler.removeCallbacks(periodicSweepRunnable)
        // Burst check intervals after Bluetooth connection to catch head unit AVRCP commands immediately
        val delaysMs = longArrayOf(0L, 400L, 1000L, 2000L, 3500L, 5000L, 8000L, 12000L, 20000L)
        for (delay in delaysMs) {
            mainHandler.postDelayed({
                if (isProtectionCurrentlyActive()) {
                    refreshActiveSessions()
                    inspectAllSessionsAndNotifications(reason = "post-connect burst")
                }
            }, delay)
        }
        mainHandler.postDelayed(periodicSweepRunnable, SWEEP_INTERVAL_MS)
    }

    private fun isProtectionCurrentlyActive(): Boolean {
        val config = RepeatFixEngine.ProtectionConfig(
            isServiceEnabled = prefs.isServiceEnabled,
            triggerScope = prefs.triggerScope,
            selectedDeviceAddresses = prefs.selectedDeviceAddresses,
            isCarConnected = prefs.isCarCurrentlyConnected,
            lastConnectTimestampMs = prefs.lastConnectTimestampMs,
            isContinuousWhileConnected = prefs.isContinuousWhileConnected,
            watchWindowSeconds = prefs.watchWindowSeconds
        )
        return RepeatFixEngine.isProtectionActive(config, System.currentTimeMillis())
    }

    private fun inspectAllSessionsAndNotifications(
        reason: String,
        forceEvenIfOutsideWindow: Boolean = false
    ): Int {
        if (!forceEvenIfOutsideWindow && !isProtectionCurrentlyActive()) return 0

        var fixesApplied = 0
        refreshActiveSessions()

        for ((pkg, controller) in activeCompatControllers) {
            if (checkControllerForRepeatOne(controller, pkg, reason, forceEvenIfOutsideWindow)) {
                fixesApplied++
            }
        }

        try {
            val notifications = activeNotifications ?: emptyArray()
            for (sbn in notifications) {
                if (inspectStatusBarNotification(sbn, reason, forceEvenIfOutsideWindow)) {
                    fixesApplied++
                }
            }
        } catch (_: Exception) {
        }

        return fixesApplied
    }

    private fun checkControllerForRepeatOne(
        controller: MediaControllerCompat,
        pkg: String,
        reason: String,
        forceEvenIfOutsideWindow: Boolean = false
    ): Boolean {
        if (!forceEvenIfOutsideWindow && !isProtectionCurrentlyActive()) return false

        // 1. Standard MediaSession Repeat Mode check
        val repeatMode = controller.repeatMode
        if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) {
            return handleRepeatOneDetected(controller, pkg, "$reason (repeatMode=ONE)")
        }

        // 2. Check session extras or playback state extras for repeat_mode == 1
        val stateExtrasRepeat = controller.playbackState?.extras?.getInt("android.media.session.extra.REPEAT_MODE", -1) ?: -1
        if (stateExtrasRepeat == PlaybackStateCompat.REPEAT_MODE_ONE) {
            return handleRepeatOneDetected(controller, pkg, "$reason (extras repeatMode=ONE)")
        }

        // 3. Check CustomActions on PlaybackState (used by YouTube Music, Castbox, etc.)
        if (prefs.useActionClickFallback) {
            val customActions = controller.playbackState?.customActions ?: emptyList()
            for (customAction in customActions) {
                if (RepeatFixEngine.isRepeatOneActionLabel(customAction.name)) {
                    if (!canApplyFixNow(pkg)) return false
                    controller.transportControls.sendCustomAction(customAction.action, customAction.extras)
                    controller.transportControls.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_NONE)
                    recordFixApplied(pkg, "Turned off Repeat One via custom action '${customAction.name}' ($reason)")
                    return true
                }
            }
        }

        return false
    }

    private fun inspectStatusBarNotification(
        sbn: StatusBarNotification,
        reason: String,
        forceEvenIfOutsideWindow: Boolean = false
    ): Boolean {
        if (!forceEvenIfOutsideWindow && !isProtectionCurrentlyActive()) return false

        val notification = sbn.notification ?: return false
        val extras = notification.extras ?: return false
        val pkg = sbn.packageName ?: return false

        // Check if notification has a MediaSession token not yet in activeCompatControllers
        @Suppress("DEPRECATION")
        val fwToken = extras.getParcelable<MediaSession.Token>(android.app.Notification.EXTRA_MEDIA_SESSION)
        if (fwToken != null && !activeCompatControllers.containsKey(pkg)) {
            try {
                val compatToken = MediaSessionCompat.Token.fromToken(fwToken)
                val compatController = MediaControllerCompat(this, compatToken)
                if (checkControllerForRepeatOne(compatController, pkg, "$reason-notif-token", forceEvenIfOutsideWindow)) {
                    return true
                }
            } catch (_: Exception) {
            }
        }

        // Fallback: Check notification action buttons for explicit "Repeat One" label
        if (prefs.useActionClickFallback) {
            val actions = notification.actions ?: emptyArray()
            for (action in actions) {
                if (RepeatFixEngine.isRepeatOneActionLabel(action.title) && action.actionIntent != null) {
                    if (!canApplyFixNow(pkg)) return false
                    try {
                        action.actionIntent.send()
                        recordFixApplied(pkg, "Turned off Repeat One via notification button '${action.title}' ($reason)")
                        return true
                    } catch (e: PendingIntent.CanceledException) {
                        Log.w(TAG, "PendingIntent canceled for $pkg: ${e.message}")
                    }
                }
            }
        }

        return false
    }

    private fun handleRepeatOneDetected(
        controller: MediaControllerCompat,
        pkg: String,
        source: String
    ): Boolean {
        if (!canApplyFixNow(pkg)) return false

        controller.transportControls.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_NONE)

        // Also check if there is a custom action for Repeat One if setRepeatMode is ignored by the player
        if (prefs.useActionClickFallback) {
            val customActions = controller.playbackState?.customActions ?: emptyList()
            for (customAction in customActions) {
                if (RepeatFixEngine.isRepeatOneActionLabel(customAction.name)) {
                    controller.transportControls.sendCustomAction(customAction.action, customAction.extras)
                    break
                }
            }
        }

        recordFixApplied(pkg, "Detected Repeat One in ${friendlyAppName(pkg)} ($source) -> Set to Repeat Off")
        return true
    }

    private fun sendRepeatOffToAllActiveSessions(reason: String) {
        refreshActiveSessions()
        if (activeCompatControllers.isEmpty()) {
            prefs.appendLog("Manual reset requested, but no active media sessions were found.")
            return
        }
        for ((pkg, controller) in activeCompatControllers) {
            controller.transportControls.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_NONE)
            prefs.appendLog("Sent Repeat Off command to ${friendlyAppName(pkg)} ($reason)")
        }
    }

    private fun canApplyFixNow(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastFixTimestampByPackage[pkg] ?: 0L
        if (now - last < FIX_COOLDOWN_MS) {
            return false
        }
        lastFixTimestampByPackage[pkg] = now
        return true
    }

    private fun recordFixApplied(pkg: String, logMessage: String) {
        Log.i(TAG, "Fix applied [$pkg]: $logMessage")
        prefs.fixCount += 1
        prefs.appendLog(logMessage)
        broadcastUiUpdate()
    }

    private fun friendlyAppName(pkg: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    private fun broadcastUiUpdate() {
        val intent = Intent(ACTION_UI_STATE_UPDATED).setPackage(packageName)
        sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "MediaRepeatMonitor"
        private const val SWEEP_INTERVAL_MS = 2500L
        private const val FIX_COOLDOWN_MS = 1200L

        const val ACTION_CAR_BLUETOOTH_CONNECTED = "com.jeremybettis.carrepeatfix.CAR_BT_CONNECTED"
        const val ACTION_CAR_BLUETOOTH_DISCONNECTED = "com.jeremybettis.carrepeatfix.CAR_BT_DISCONNECTED"
        const val ACTION_FORCE_CHECK_NOW = "com.jeremybettis.carrepeatfix.FORCE_CHECK_NOW"
        const val ACTION_REFRESH_MONITORS = "com.jeremybettis.carrepeatfix.REFRESH_MONITORS"
        const val ACTION_UI_STATE_UPDATED = "com.jeremybettis.carrepeatfix.UI_STATE_UPDATED"

        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
    }
}
