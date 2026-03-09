package com.fairprice.app.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStrategyFallbackTest {
    @Test
    fun sameDomainAndInstallation_isStableAcrossCalls() = runTest {
        val fallback = LocalStrategyFallback(installationIdProvider = { "install-alpha" })

        val first = fallback.resolveStrategy("https://www.example.com/p/1", baselineTactics = emptyList()).getOrThrow()
        val second = fallback.resolveStrategy("https://example.com/p/2", baselineTactics = emptyList()).getOrThrow()

        assertEquals("clean_baseline", first.effectiveStrategyCode())
        assertEquals("clean_baseline", second.effectiveStrategyCode())
        assertEquals("local_fallback_clean_baseline", first.engineSelectionPolicy)
        assertEquals("domain+installation", first.engineSelectionKeyScope)
    }

    @Test
    fun alwaysReturnsCleanBaseline_regardlessOfDomainOrInstallation() = runTest {
        val fallbackA = LocalStrategyFallback(installationIdProvider = { "install-A" })
        val fallbackB = LocalStrategyFallback(installationIdProvider = { "install-B" })

        val resultA = fallbackA.resolveStrategy("https://walmart.com/p/123", baselineTactics = emptyList()).getOrThrow()
        val resultB = fallbackB.resolveStrategy("https://walmart.com/p/123", baselineTactics = emptyList()).getOrThrow()

        assertEquals("clean_baseline", resultA.effectiveStrategyCode())
        assertEquals("clean_baseline", resultB.effectiveStrategyCode())
        assertFalse(resultA.amnesiaWipeRequired)
        assertFalse(resultA.strictTrackingProtection)
        assertFalse(resultA.canvasSpoofingActive)
        assertFalse(resultA.urlSanitize)
        assertTrue(resultA.engineSelectionReason?.contains("domain=walmart.com") == true)
        assertTrue(resultB.engineSelectionReason?.contains("domain=walmart.com") == true)
    }

    @Test
    fun normalized_derivesBooleansFromStrategyProfileWhenStrategyCodeBlank() {
        val cleanBaselineOnly = StrategyResult(strategyProfile = "clean_baseline")
        val normalizedClean = cleanBaselineOnly.normalized()
        assertEquals("clean_baseline", normalizedClean.effectiveStrategyCode())
        assertEquals(null, normalizedClean.strategyId)
        assertFalse(normalizedClean.amnesiaWipeRequired)
        assertFalse(normalizedClean.strictTrackingProtection)
        assertFalse(normalizedClean.canvasSpoofingActive)
        assertFalse(normalizedClean.urlSanitize)

        val stealthMaxOnly = StrategyResult(strategyProfile = "stealth_max")
        val normalizedStealth = stealthMaxOnly.normalized()
        assertEquals("stealth_max", normalizedStealth.effectiveStrategyCode())
        assertTrue(normalizedStealth.amnesiaWipeRequired)
        assertTrue(normalizedStealth.strictTrackingProtection)
        assertTrue(normalizedStealth.canvasSpoofingActive)
        assertTrue(normalizedStealth.urlSanitize)
    }
}
