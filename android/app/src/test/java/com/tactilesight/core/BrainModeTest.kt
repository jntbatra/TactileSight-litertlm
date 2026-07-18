package com.tactilesight.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Privacy mode is hard rule #7: it must *actually* block imagery leaving the
 * phone, not relabel the UI. These tests pin that rule to the type that
 * expresses it, so it cannot be softened by an unrelated change to a screen.
 */
class BrainModeTest {

    @Test
    fun `only on-device keeps imagery on the phone`() {
        assertFalse(BrainMode.ON_DEVICE_NPU.sendsImageryOffDevice)
        assertTrue(BrainMode.PRIVATE_SERVER.sendsImageryOffDevice)
    }

    @Test
    fun `privacy mode blocks every destination that sends imagery off-device`() {
        // Stated over the whole enum rather than by naming modes: a destination
        // added later is blocked by default unless it declares the frame stays
        // put, so a new mode cannot open a privacy hole by being forgotten here.
        BrainMode.entries
            .filter { it.sendsImageryOffDevice }
            .forEach { assertFalse(it.name, it.isAllowedUnderPrivacy(privacyOn = true)) }
    }

    @Test
    fun `privacy mode still allows on-device`() {
        assertTrue(BrainMode.ON_DEVICE_NPU.isAllowedUnderPrivacy(privacyOn = true))
    }

    @Test
    fun `there is always a destination left when privacy is on`() {
        // Hard rule #4: every press yields speech. If privacy ever blocked
        // everything, Settings.effectiveMode would fall back to a mode that is
        // itself blocked and the press would have nowhere to go.
        assertTrue(BrainMode.entries.any { it.isAllowedUnderPrivacy(privacyOn = true) })
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
