package com.fairprice.app.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStrategyFallbackTest {
    @Test
    fun sameDomainAndInstallation_isStableAcrossCalls() = runTest {
        val fallback = LocalStrategyFallback(
            installationIdProvider = { "install-alpha" },
            bucketCalculator = { key ->
                if (key.contains("example.com") && key.contains("install-alpha")) 37 else 62
            },
        )

        val first = fallback.resolveStrategy("https://www.example.com/p/1", baselineTactics = emptyList()).getOrThrow()
        val second = fallback.resolveStrategy("https://example.com/p/2", baselineTactics = emptyList()).getOrThrow()

        assertEquals(first.strategyProfile, second.strategyProfile)
        assertEquals(37, first.engineSelectionBucket)
        assertEquals(37, second.engineSelectionBucket)
        assertEquals("domain_installation_bucket_v1_50_50", first.engineSelectionPolicy)
        assertEquals("domain+installation", first.engineSelectionKeyScope)
    }

    @Test
    fun sameDomainDifferentInstallations_canMapToDifferentProfiles() = runTest {
        val yaleFallback = LocalStrategyFallback(
            installationIdProvider = { "install-A" },
            bucketCalculator = { key ->
                if (key.contains("install-A")) 12 else 88
            },
        )
        val legacyFallback = LocalStrategyFallback(
            installationIdProvider = { "install-B" },
            bucketCalculator = { key ->
                if (key.contains("install-A")) 12 else 88
            },
        )

        val yaleResult = yaleFallback.resolveStrategy("https://walmart.com/p/123", baselineTactics = emptyList()).getOrThrow()
        val legacyResult = legacyFallback.resolveStrategy("https://walmart.com/p/123", baselineTactics = emptyList()).getOrThrow()

        assertEquals("yale_smart", yaleResult.strategyProfile)
        assertEquals("clean_control_v1", legacyResult.strategyProfile)
        assertNotEquals(yaleResult.engineSelectionBucket, legacyResult.engineSelectionBucket)
        assertTrue(yaleResult.engineSelectionReason?.contains("domain=walmart.com") == true)
        assertTrue(legacyResult.engineSelectionReason?.contains("domain=walmart.com") == true)
    }
}
