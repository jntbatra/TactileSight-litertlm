package com.tactilesight.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `sendsImageryOffDevice` is the one claim this app makes to a user who cannot
 * see where their camera is pointing. These tests pin it to the type that
 * expresses it, so it cannot be softened by an unrelated change to a screen.
 *
 * The privacy toggle these tests used to guard was removed on 2026-07-19 with
 * the cloud tier — see [BrainMode]. What survives is the flag itself, because
 * the honesty of the label does not depend on there being a switch.
 */
class BrainModeTest {

    @Test
    fun `only on-device keeps imagery on the phone`() {
        assertFalse(BrainMode.ON_DEVICE_NPU.sendsImageryOffDevice)
        assertTrue(BrainMode.PRIVATE_SERVER.sendsImageryOffDevice)
    }

    @Test
    fun `there is always a destination that keeps imagery on the phone`() {
        // Hard rule #4 says every press yields speech, and the on-device path is
        // the only one that works with no network at all. If this ever fails it
        // means the app cannot answer a press in a venue with no wifi.
        assertTrue(BrainMode.entries.any { !it.sendsImageryOffDevice })
    }

    @Test
    fun `a mode that calls itself on-device does not send imagery away`() {
        // Guards the wording, not the wiring. A mode labelled "on-device" whose
        // frame goes to a server is the one bug in this file a user could never
        // detect for themselves.
        BrainMode.entries
            .filter { it.displayName.contains("on-device", ignoreCase = true) }
            .forEach { assertFalse(it.displayName, it.sendsImageryOffDevice) }
    }

    @Test
    fun `every mode is labelled for the picker`() {
        assertEquals(BrainMode.entries.size, BrainMode.entries.map { it.displayName }.toSet().size)
        BrainMode.entries.forEach { assertTrue(it.displayName.isNotBlank()) }
    }
}
