package com.fairprice.app.engine

data class StrategyResult(
    val strategyId: String,
    val wireguardConfig: String,
)

interface PricingStrategyEngine {
    suspend fun determineStrategy(url: String): Result<StrategyResult>
}

class DefaultPricingStrategyEngine : PricingStrategyEngine {
    override suspend fun determineStrategy(url: String): Result<StrategyResult> {
        return Result.success(
            StrategyResult(
                strategyId = "strat_stub_001",
                wireguardConfig = "phase3-placeholder-config",
            ),
        )
    }
}
