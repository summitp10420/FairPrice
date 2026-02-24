package com.fairprice.app.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val role: String,
    @SerialName("preferred_currency")
    val preferredCurrency: String,
    @SerialName("total_savings_cents")
    val totalSavingsCents: Int,
    @SerialName("created_at")
    val createdAt: String,
)
