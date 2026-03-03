package com.fairprice.app.engine

import java.net.URI
import java.util.Locale

enum class EngineProfile {
    LEGACY,
    YALE_SMART,
}

data class StrategyResult(
    val strategyId: String?,
    val strategyName: String = "clean_strategy_v1.0",
    val strategyEngineName: String = "strategy_engine_v1.0",
    val strategyVersion: String = "1.0",
    val wireguardConfig: String = "",
    val engineProfile: EngineProfile = EngineProfile.YALE_SMART,
    val engineSelectionPolicy: String? = null,
    val engineSelectionReason: String? = null,
    val engineSelectionKeyScope: String? = null,
    val engineSelectionBucket: Int? = null,
)

interface PricingStrategyEngine {
    suspend fun determineStrategy(url: String, baselineTactics: List<String>): Result<StrategyResult>
}

class DefaultPricingStrategyEngine(
    private val installationIdProvider: () -> String = { DEFAULT_INSTALLATION_ID },
    private val bucketCalculator: (String) -> Int = { assignmentKey ->
        (assignmentKey.hashCode() and Int.MAX_VALUE) % BUCKET_MODULUS
    },
) : PricingStrategyEngine {
    companion object {
        private const val DEFAULT_INSTALLATION_ID = "default_installation"
        private const val BUCKET_MODULUS = 100
        private const val YALE_SMART_PERCENT = 50
        private const val ENGINE_SELECTION_POLICY = "domain_installation_bucket_v1_50_50"
        private const val ENGINE_SELECTION_SCOPE = "domain+installation"
    }

    override suspend fun determineStrategy(url: String, baselineTactics: List<String>): Result<StrategyResult> {
        val domain = normalizeDomain(url)
        val installationId = installationIdProvider().ifBlank { DEFAULT_INSTALLATION_ID }
        val assignmentKey = "$domain|$installationId"
        val bucket = bucketCalculator(assignmentKey).coerceIn(0, BUCKET_MODULUS - 1)
        val profile = if (bucket < YALE_SMART_PERCENT) EngineProfile.YALE_SMART else EngineProfile.LEGACY
        return Result.success(
            StrategyResult(
                strategyId = null,
                wireguardConfig = "",
                engineProfile = profile,
                engineSelectionPolicy = ENGINE_SELECTION_POLICY,
                engineSelectionReason = "bucket=$bucket domain=$domain",
                engineSelectionKeyScope = ENGINE_SELECTION_SCOPE,
                engineSelectionBucket = bucket,
            ),
        )
    }

    private fun normalizeDomain(url: String): String {
        val rawHost = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        val lowerHost = rawHost.trim().lowercase(Locale.US)
        if (lowerHost.isBlank()) return "unknown-domain"
        return lowerHost.removePrefix("www.").ifBlank { "unknown-domain" }
    }
}
