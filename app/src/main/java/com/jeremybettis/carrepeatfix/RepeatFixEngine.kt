package com.jeremybettis.carrepeatfix

/**
 * Pure decision logic for determining when CarRepeatFix should intervene
 * and disable Repeat One Track mode.
 */
object RepeatFixEngine {

    // Matches PlaybackStateCompat.REPEAT_MODE_NONE (0), REPEAT_MODE_ONE (1),
    // REPEAT_MODE_ALL (2), REPEAT_MODE_GROUP (3)
    const val REPEAT_MODE_NONE = 0
    const val REPEAT_MODE_ONE = 1
    const val REPEAT_MODE_ALL = 2
    const val REPEAT_MODE_GROUP = 3

    data class ProtectionConfig(
        val isServiceEnabled: Boolean,
        val triggerScope: RepeatTriggerScope,
        val selectedDeviceAddresses: Set<String>,
        val isCarConnected: Boolean,
        val lastConnectTimestampMs: Long,
        val isContinuousWhileConnected: Boolean,
        val watchWindowSeconds: Int
    )

    /**
     * Checks whether a given Bluetooth device address (and class/profile status) matches
     * the user's configured car trigger scope.
     */
    fun isTargetCarDevice(
        address: String?,
        triggerScope: RepeatTriggerScope,
        selectedAddresses: Set<String>
    ): Boolean {
        return when (triggerScope) {
            RepeatTriggerScope.ALWAYS_ACTIVE -> true
            RepeatTriggerScope.ANY_BLUETOOTH_AUDIO -> !address.isNullOrBlank()
            RepeatTriggerScope.SELECTED_DEVICES -> {
                if (address.isNullOrBlank()) false
                else if (selectedAddresses.isEmpty()) true // Fallback if user hasn't picked one yet
                else selectedAddresses.any { it.equals(address, ignoreCase = true) }
            }
        }
    }

    /**
     * Determines if the protection window is currently active at [nowMs].
     */
    fun isProtectionActive(config: ProtectionConfig, nowMs: Long): Boolean {
        if (!config.isServiceEnabled) return false

        if (config.triggerScope == RepeatTriggerScope.ALWAYS_ACTIVE) {
            return true
        }

        // Within the post-connect watch window, always protect even if profile state fluctuates
        val elapsedSinceConnectMs = nowMs - config.lastConnectTimestampMs
        val watchWindowMs = config.watchWindowSeconds * 1000L
        val inWatchWindow = config.lastConnectTimestampMs > 0L &&
            elapsedSinceConnectMs in 0..watchWindowMs

        if (inWatchWindow) {
            return true
        }

        return config.isCarConnected && config.isContinuousWhileConnected
    }

    /**
     * Keywords commonly found in PlaybackState custom actions or Notification actions
     * when a media app (like YouTube Music or Castbox) is currently in Repeat One mode.
     */
    private val REPEAT_ONE_ACTION_INDICATORS = listOf(
        "repeat one",
        "repeat_one",
        "repeat current",
        "repeat track",
        "repeat single",
        "repeat 1",
        "repeating one"
    )

    /**
     * Checks whether a custom action name or notification action label indicates
     * that the player is currently in "Repeat One" state and tapping it will cycle it off.
     */
    fun isRepeatOneActionLabel(label: CharSequence?): Boolean {
        if (label.isNullOrBlank()) return false
        val normalized = label.toString().trim().lowercase()
        return REPEAT_ONE_ACTION_INDICATORS.any { normalized.contains(it) }
    }
}
