package com.fairprice.app.engine

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
)

interface PricingStrategyEngine {
    suspend fun determineStrategy(url: String, baselineTactics: List<String>): Result<StrategyResult>
}

class DefaultPricingStrategyEngine : PricingStrategyEngine {
    override suspend fun determineStrategy(url: String, baselineTactics: List<String>): Result<StrategyResult> {
        return Result.success(
            StrategyResult(
                strategyId = null,
                wireguardConfig = "",
                engineProfile = EngineProfile.YALE_SMART,
            ),
        )
    }
}
