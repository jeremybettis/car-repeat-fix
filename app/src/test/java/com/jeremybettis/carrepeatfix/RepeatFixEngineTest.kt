package com.jeremybettis.carrepeatfix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatFixEngineTest {

    @Test
    fun `isTargetCarDevice matches selected address case-insensitively`() {
        val selected = setOf("AA:BB:CC:DD:EE:FF")
        assertTrue(
            RepeatFixEngine.isTargetCarDevice(
                address = "aa:bb:cc:dd:ee:ff",
                triggerScope = RepeatTriggerScope.SELECTED_DEVICES,
                selectedAddresses = selected
            )
        )
        assertFalse(
            RepeatFixEngine.isTargetCarDevice(
                address = "11:22:33:44:55:66",
                triggerScope = RepeatTriggerScope.SELECTED_DEVICES,
                selectedAddresses = selected
            )
        )
    }

    @Test
    fun `isTargetCarDevice falls back to any device when selected set is empty`() {
        assertTrue(
            RepeatFixEngine.isTargetCarDevice(
                address = "11:22:33:44:55:66",
                triggerScope = RepeatTriggerScope.SELECTED_DEVICES,
                selectedAddresses = emptySet()
            )
        )
    }

    @Test
    fun `isProtectionActive returns true within post-connect watch window`() {
        val connectTime = 1_000_000L
        val config = RepeatFixEngine.ProtectionConfig(
            isServiceEnabled = true,
            triggerScope = RepeatTriggerScope.SELECTED_DEVICES,
            selectedDeviceAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            isCarConnected = false, // even if profile state hasn't settled yet
            lastConnectTimestampMs = connectTime,
            isContinuousWhileConnected = false,
            watchWindowSeconds = 60
        )

        // 15 seconds after connect -> active
        assertTrue(RepeatFixEngine.isProtectionActive(config, connectTime + 15_000L))
        // 75 seconds after connect with continuous=false -> inactive
        assertFalse(RepeatFixEngine.isProtectionActive(config, connectTime + 75_000L))
    }

    @Test
    fun `isProtectionActive stays active continuously while car is connected`() {
        val connectTime = 1_000_000L
        val config = RepeatFixEngine.ProtectionConfig(
            isServiceEnabled = true,
            triggerScope = RepeatTriggerScope.SELECTED_DEVICES,
            selectedDeviceAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            isCarConnected = true,
            lastConnectTimestampMs = connectTime,
            isContinuousWhileConnected = true,
            watchWindowSeconds = 30
        )

        // 10 minutes into the drive -> still active
        assertTrue(RepeatFixEngine.isProtectionActive(config, connectTime + 600_000L))
    }

    @Test
    fun `isRepeatOneActionLabel detects repeat one labels and ignores repeat off or all`() {
        assertTrue(RepeatFixEngine.isRepeatOneActionLabel("Repeat one"))
        assertTrue(RepeatFixEngine.isRepeatOneActionLabel("REPEAT_ONE"))
        assertTrue(RepeatFixEngine.isRepeatOneActionLabel("Repeat current track"))
        assertTrue(RepeatFixEngine.isRepeatOneActionLabel("Repeat single"))

        assertFalse(RepeatFixEngine.isRepeatOneActionLabel("Repeat off"))
        assertFalse(RepeatFixEngine.isRepeatOneActionLabel("Repeat all"))
        assertFalse(RepeatFixEngine.isRepeatOneActionLabel("Shuffle"))
        assertFalse(RepeatFixEngine.isRepeatOneActionLabel(null))
    }
}
