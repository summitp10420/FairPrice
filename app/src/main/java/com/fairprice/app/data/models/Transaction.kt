package com.fairprice.app.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Transaction(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("price_check_id")
    val priceCheckId: String,
    @SerialName("final_paid_price_cents")
    val finalPaidPriceCents: Int,
    @SerialName("calculated_savings_cents")
    val calculatedSavingsCents: Int,
    @SerialName("user_notes")
    val userNotes: String,
    @SerialName("purchased_in_app")
    val purchasedInApp: Boolean,
    @SerialName("created_at")
    val createdAt: String,
)
