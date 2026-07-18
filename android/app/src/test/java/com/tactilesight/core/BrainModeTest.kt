package com.tactilesight.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Privacy mode is hard rule #7: it must *actually* block the cloud, not
 * relabel the UI. These tests pin that rule to the type that expresses it,
 * so it cannot be softened by an unrelated change to a screen.
 */
class BrainModeTest {

    @Test
    fun `only on-device keeps imagery on the phone`() {
        assertFalse(BrainMode.ON_DEVICE_NPU.sendsImageryOffDevice)
        assertTrue(BrainMode.PRIVATE_SERVER.sendsImageryOffDevice)
        assertTrue(BrainMode.CLOUD.sendsImageryOffDevice)
    }

    @Test
    fun `privacy mode blocks the cloud`() {
        assertFalse(BrainMode.CLOUD.isAllowedUnderPrivacy(privacyOn = true))
    }

    @Test
    fun `privacy mode still allows on-device and our own server`() {
        // Our own machine is not a third party. The claim is about the
        // destination, not the transit — a tunnel terminates TLS at its edge,
        // which is why the LAN address is the version that is private
        // end-to-end. See BrainMode's docs.
        assertTrue(BrainMode.ON_DEVICE_NPU.isAllowedUnderPrivacy(privacyOn = true))
        assertTrue(BrainMode.PRIVATE_SERVER.isAllowedUnderPrivacy(privacyOn = true))
    }

    @Test
    fun `with privacy off every destination is allowed`() {
        BrainMode.entries.forEach {
            assertTrue(it.name, it.isAllowedUnderPrivacy(privacyOn = false))
        }
    }

    @Test
    fun `every mode is labelled for the picker`() {
        assertEquals(BrainMode.entries.size, BrainMode.entries.map { it.displayName }.toSet().size)
        BrainMode.entries.forEach { assertTrue(it.displayName.isNotBlank()) }
    }
}
