package com.fairprice.app.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class RetailerTacticsLog(
    val id: String,
    @SerialName("retailer_id")
    val retailerId: String,
    @SerialName("price_check_id")
    val priceCheckId: String,
    @SerialName("detected_tactics")
    val detectedTactics: JsonObject,
    @SerialName("recommended_counter_strategy_id")
    val recommendedCounterStrategyId: String,
    @SerialName("observed_at")
    val observedAt: String,
)
