package com.fairprice.app.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStrategyFallbackTest {
    @Test
    fun sameDomainAndSession_isStableAcrossCalls() = runTest {
        val fallback = LocalStrategyFallback()
        val sessionId = "test-session-id"

        val first = fallback.resolveStrategy("https://www.example.com/p/1", baselineTactics = emptyList(), shoppingSessionId = sessionId).getOrThrow()
        val second = fallback.resolveStrategy("https://example.com/p/2", baselineTactics = emptyList(), shoppingSessionId = sessionId).getOrThrow()

        assertEquals("clean_baseline", first.effectiveStrategyCode())
        assertEquals("clean_baseline", second.effectiveStrategyCode())
        assertEquals("local_fallback_clean_baseline", first.engineSelectionPolicy)
        assertEquals("domain+session", first.engineSelectionKeyScope)
    }

    @Test
    fun alwaysReturnsCleanBaseline_includesSessionInReason() = runTest {
        val fallback = LocalStrategyFallback()
        val sessionId = "test-session-id"

        val resultA = fallback.resolveStrategy("https://walmart.com/p/123", baselineTactics = emptyList(), shoppingSessionId = sessionId).getOrThrow()
        val resultB = fallback.resolveStrategy("https://walmart.com/p/123", baselineTactics = emptyList(), shoppingSessionId = "other-session").getOrThrow()

        assertEquals("clean_baseline", resultA.effectiveStrategyCode())
        assertEquals("clean_baseline", resultB.effectiveStrategyCode())
        assertFalse(resultA.amnesiaWipeRequired)
        assertFalse(resultA.strictTrackingProtection)
        assertFalse(resultA.canvasSpoofingActive)
        assertFalse(resultA.urlSanitize)
        assertTrue(resultA.engineSelectionReason?.contains("domain=walmart.com") == true)
        assertTrue(resultA.engineSelectionReason?.contains("session=$sessionId") == true)
        assertTrue(resultB.engineSelectionReason?.contains("domain=walmart.com") == true)
        assertEquals("domain+session", resultA.engineSelectionKeyScope)
        assertEquals("domain+session", resultB.engineSelectionKeyScope)
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
