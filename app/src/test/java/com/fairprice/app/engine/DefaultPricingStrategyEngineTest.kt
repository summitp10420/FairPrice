package com.fairprice.app.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPricingStrategyEngineTest {
    @Test
    fun sameDomainAndInstallation_isStableAcrossCalls() = runTest {
        val engine = DefaultPricingStrategyEngine(
            installationIdProvider = { "install-alpha" },
            bucketCalculator = { key ->
                // Deterministic test bucket based on assignment key shape.
                if (key.contains("example.com")) 37 else 62
            },
        )

        val first = engine.determineStrategy("https://www.example.com/p/1", baselineTactics = emptyList()).getOrThrow()
        val second = engine.determineStrategy("https://example.com/p/2", baselineTactics = emptyList()).getOrThrow()

        assertEquals(first.engineProfile, second.engineProfile)
        assertEquals(37, first.engineSelectionBucket)
        assertEquals(37, second.engineSelectionBucket)
        assertEquals("domain_installation_bucket_v1_50_50", first.engineSelectionPolicy)
        assertEquals("domain+installation", first.engineSelectionKeyScope)
    }

    @Test
    fun sameDomainDifferentInstallations_canMapToDifferentProfiles() = runTest {
        val yaleEngine = DefaultPricingStrategyEngine(
            installationIdProvider = { "install-A" },
            bucketCalculator = { key ->
                if (key.contains("install-A")) 12 else 88
            },
        )
        val legacyEngine = DefaultPricingStrategyEngine(
            installationIdProvider = { "install-B" },
            bucketCalculator = { key ->
                if (key.contains("install-A")) 12 else 88
            },
        )

        val yaleResult = yaleEngine.determineStrategy("https://walmart.com/p/123", baselineTactics = emptyList()).getOrThrow()
        val legacyResult = legacyEngine.determineStrategy("https://walmart.com/p/123", baselineTactics = emptyList()).getOrThrow()

        assertEquals(EngineProfile.YALE_SMART, yaleResult.engineProfile)
        assertEquals(EngineProfile.LEGACY, legacyResult.engineProfile)
        assertNotEquals(yaleResult.engineSelectionBucket, legacyResult.engineSelectionBucket)
        assertTrue(yaleResult.engineSelectionReason?.contains("domain=walmart.com") == true)
        assertTrue(legacyResult.engineSelectionReason?.contains("domain=walmart.com") == true)
    }
}
