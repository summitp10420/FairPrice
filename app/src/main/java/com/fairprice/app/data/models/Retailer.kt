package com.fairprice.app.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Retailer(
    val id: String,
    val domain: String,
    val name: String,
    @SerialName("created_at")
    val createdAt: String,
)
