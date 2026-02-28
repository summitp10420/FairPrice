package com.fairprice.app.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PriceCheckAttempt(
    val id: String? = null,
    @SerialName("price_check_id")
    val priceCheckId: String? = null,
    val phase: String,
    @SerialName("attempt_index")
    val attemptIndex: Int,
    @SerialName("vpn_config")
    val vpnConfig: String? = null,
    val success: Boolean,
    @SerialName("error_type")
    val errorType: String? = null,
    @SerialName("error_message")
    val errorMessage: String? = null,
    @SerialName("extracted_price_cents")
    val extractedPriceCents: Int? = null,
    @SerialName("detected_tactics")
    val detectedTactics: List<String>? = null,
    @SerialName("debug_extraction_path")
    val debugExtractionPath: String? = null,
    @SerialName("latency_ms")
    val latencyMs: Long? = null,
)
