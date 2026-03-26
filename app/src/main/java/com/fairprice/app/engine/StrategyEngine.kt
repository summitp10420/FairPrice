package com.fairprice.app.engine

import java.net.URI
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Strategy resolution interface. The app depends on this abstraction.
 * The strategy engine lives on Railway; implementations may call Railway or use a local fallback.
 * See DOCS/STRATEGY_ENGINE_TERMINOLOGY.MD.
 */
@Serializable
data class ProxyConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    @SerialName("zip_code") val zipCode: String,
)

interface StrategyResolver {
    suspend fun resolveStrategy(url: String, baselineTactics: List<String>, shoppingSessionId: String): Result<StrategyResult>
}

@Serializable
data class StrategyResult(
    @SerialName("strategy_id")
    val strategyId: String? = null,
    @SerialName("strategy_code")
    val strategyCode: String = "",
    @SerialName("amnesia_wipe_required")
    val amnesiaWipeRequired: Boolean = false,
    @SerialName("strict_tracking_protection")
    val strictTrackingProtection: Boolean = false,
    @SerialName("canvas_spoofing_active")
    val canvasSpoofingActive: Boolean = false,
    @SerialName("url_sanitize")
    val urlSanitize: Boolean = false,
    val strategyName: String = "clean_strategy_v1.0",
    val strategyEngineName: String = "strategy_engine_v1.0",
    val strategyVersion: String = "1.0",
    val wireguardConfig: String = "",
    @SerialName("strategy_profile")
    val strategyProfile: String = "",
    @SerialName("ua_spoofing_active")
    val uaSpoofingActive: Boolean = false,
    @SerialName("user_agent_override")
    val userAgentOverride: String? = null,
    @SerialName("persona_profile")
    val personaProfile: String? = null,
    @SerialName("proxy_config")
    val proxyConfig: ProxyConfig? = null,
    val engineSelectionPolicy: String? = null,
    val engineSelectionReason: String? = null,
    val engineSelectionKeyScope: String? = null,
    val engineSelectionBucket: Int? = null,
    @SerialName("selection_mode")
    val selectionMode: String? = null,
) {
    /** Effective profile code: from strategy_code or, for old payloads, strategy_profile. */
    fun effectiveStrategyCode(): String =
        strategyCode.ifBlank { strategyProfile.ifBlank { StrategyProfileBehavior.CLEAN_BASELINE } }

    /**
     * When backend sends only strategy_profile (old shape), derive strategyCode and booleans.
     * Call after parsing so execution and telemetry use a full payload.
     */
    fun normalized(): StrategyResult {
        if (strategyCode.isNotBlank()) return this
        val code = strategyProfile.ifBlank { StrategyProfileBehavior.CLEAN_BASELINE }
        return copy(
            strategyId = null,
            strategyCode = code,
            amnesiaWipeRequired = StrategyProfileBehavior.amnesiaWipeRequired(code),
            strictTrackingProtection = StrategyProfileBehavior.strictTrackingProtection(code),
            canvasSpoofingActive = StrategyProfileBehavior.canvasSpoofingActive(code),
            urlSanitize = StrategyProfileBehavior.requiresUrlSanitize(code),
            uaSpoofingActive = StrategyProfileBehavior.uaSpoofingActive(code),
            userAgentOverride = null,
            personaProfile = null,
        )
    }
}

/**
 * Local strategy fallback. Used when the Railway strategy engine is unreachable.
 * Always returns clean_baseline (least aggressive, clean session only).
 * Not an engine — it provides a strategy so the spoof run can proceed.
 */
class LocalStrategyFallback : StrategyResolver {
    companion object {
        private const val ENGINE_SELECTION_POLICY = "local_fallback_clean_baseline"
    }

    override suspend fun resolveStrategy(url: String, baselineTactics: List<String>, shoppingSessionId: String): Result<StrategyResult> {
        val domain = normalizeDomain(url)
        return Result.success(
            StrategyResult(
                strategyId = null,
                strategyCode = StrategyProfileBehavior.CLEAN_BASELINE,
                amnesiaWipeRequired = false,
                strictTrackingProtection = false,
                canvasSpoofingActive = false,
                urlSanitize = false,
                wireguardConfig = "",
                strategyProfile = StrategyProfileBehavior.CLEAN_BASELINE,
                engineSelectionPolicy = ENGINE_SELECTION_POLICY,
                engineSelectionReason = "domain=$domain session=$shoppingSessionId",
                engineSelectionKeyScope = "domain+session",
                engineSelectionBucket = null,
            ),
        )
    }

    private fun normalizeDomain(url: String): String {
        val rawHost = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        val lowerHost = rawHost.trim().lowercase(Locale.US)
        if (lowerHost.isBlank()) return "unknown-domain"
        return lowerHost.removePrefix("www.").ifBlank { "unknown-domain" }
    }
}
