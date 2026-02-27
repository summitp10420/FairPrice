package com.fairprice.app.engine

data class StrategyResult(
    val strategyId: String?,
    val wireguardConfig: String,
)

interface PricingStrategyEngine {
    suspend fun determineStrategy(url: String, baselineTactics: List<String>): Result<StrategyResult>
}

class DefaultPricingStrategyEngine : PricingStrategyEngine {
    override suspend fun determineStrategy(url: String, baselineTactics: List<String>): Result<StrategyResult> {
        return Result.success(
            StrategyResult(
                strategyId = null,
                wireguardConfig = "phase3-placeholder-config",
            ),
        )
    }
}
