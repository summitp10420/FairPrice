package com.fairprice.app.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Strategy(
    val id: String,
    val name: String,
    val description: String,
    @SerialName("is_active")
    val isActive: Boolean,
    @SerialName("vpn_location_code")
    val vpnLocationCode: String,
    @SerialName("browser_config")
    val browserConfig: JsonObject,
    @SerialName("created_at")
    val createdAt: String,
)
