package com.fairprice.app.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PriceCheck(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String? = null,
    @SerialName("product_url")
    val productUrl: String,
    val domain: String,
    @SerialName("baseline_price_cents")
    val baselinePriceCents: Int,
    @SerialName("found_price_cents")
    val foundPriceCents: Int,
    @SerialName("strategy_id")
    val strategyId: String? = null,
    @SerialName("extraction_successful")
    val extractionSuccessful: Boolean,
    @SerialName("raw_extraction_data")
    val rawExtractionData: JsonObject,
    @SerialName("created_at")
    val createdAt: String? = null,
)
