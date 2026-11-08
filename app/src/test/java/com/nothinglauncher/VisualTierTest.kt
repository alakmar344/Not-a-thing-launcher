package com.nothinglauncher

import com.nothinglauncher.perf.Effects
import com.nothinglauncher.perf.VisualTier
import com.nothinglauncher.perf.VisualTier.DeviceInfo
import com.nothinglauncher.perf.VisualTier.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device classification. RAM figures are what phones actually report (kernel-visible memory),
 * which is always below the marketed size.
 */
class VisualTierTest {

    private fun gib(v: Double) = (v * 1024 * 1024 * 1024).toLong()

    @Test
    fun threeAndAHalfGbPhoneIsLite() {
        // A "3.5 GB"/"4 GB" phone reports roughly 3.2–3.8 GiB.
        assertEquals(Tier.LITE, VisualTier.classify(DeviceInfo(gib(3.3), false, 8)))
        assertEquals(Tier.LITE, VisualTier.classify(DeviceInfo(gib(3.7), false, 8)))
    }

    @Test
    fun lowRamFlagOrFewCoresIsLite() {
        assertEquals(Tier.LITE, VisualTier.classify(DeviceInfo(gib(7.4), true, 8)))
        assertEquals(Tier.LITE, VisualTier.classify(DeviceInfo(gib(7.4), false, 4)))
    }

    @Test
    fun sixGbPhoneIsBalanced() {
        assertEquals(Tier.BALANCED, VisualTier.classify(DeviceInfo(gib(5.5), false, 8)))
    }

    @Test
    fun eightGbPhoneIsRich() {
        // "8 GB" phones report 7.0–7.6 GiB.
        assertEquals(Tier.RICH, VisualTier.classify(DeviceInfo(gib(7.0), false, 8)))
        assertEquals(Tier.RICH, VisualTier.classify(DeviceInfo(gib(7.6), false, 8)))
        assertEquals(Tier.RICH, VisualTier.classify(DeviceInfo(gib(11.3), false, 8)))
    }

    @Test
    fun unknownHardwareIsBalanced() {
        // Detection failed (0 RAM, 0 cores): neither cripple nor overload the device.
        assertEquals(Tier.BALANCED, VisualTier.classify(DeviceInfo(0L, false, 0)))
    }

    @Test
    fun liteTurnsOffEveryExtraLayer() {
        val fx = Effects.of(Tier.LITE)
        assertFalse(fx.dotGlow)
        assertFalse(fx.vignette)
        assertFalse(fx.backdropLight)
        assertFalse(fx.clockPowerOn)
        assertFalse(fx.sheetBlur)
        assertFalse(fx.entranceMotion)
        assertTrue(fx.pulseSteps in 1..8)
        assertTrue(fx.visualizerFrameMs >= 250L)
    }

    @Test
    fun richTurnsOnEverything() {
        val fx = Effects.of(Tier.RICH)
        assertTrue(fx.dotGlow && fx.vignette && fx.backdropLight && fx.clockPowerOn && fx.sheetBlur)
        assertEquals(0, fx.pulseSteps)
    }
}
