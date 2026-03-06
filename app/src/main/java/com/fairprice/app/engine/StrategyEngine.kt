package com.fairprice.app.engine

import java.net.URI
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Strategy resolution interface. The app depends on this abstraction.
 * The strategy engine lives on Railway; implementations may call Railway or use a local fallback.
 * See DOCS/STRATEGY_ENGINE_TERMINOLOGY.MD.
 */
interface StrategyResolver {
    suspend fun resolveStrategy(url: String, baselineTactics: List<String>): Result<StrategyResult>
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
    val engineSelectionPolicy: String? = null,
    val engineSelectionReason: String? = null,
    val engineSelectionKeyScope: String? = null,
    val engineSelectionBucket: Int? = null,
    /** Reserved for Sprint 14 residential proxies. */
    val proxyConfig: JsonObject? = null,
) {
    /** Effective profile code: from strategy_code or, for old payloads, strategy_profile. */
    fun effectiveStrategyCode(): String =
        strategyCode.ifBlank { strategyProfile.ifBlank { StrategyProfileBehavior.LEGACY } }

    /**
     * When backend sends only strategy_profile (old shape), derive strategyCode and booleans.
     * Call after parsing so execution and telemetry use a full payload.
     */
    fun normalized(): StrategyResult {
        if (strategyCode.isNotBlank()) return this
        val code = strategyProfile.ifBlank { StrategyProfileBehavior.LEGACY }
        return copy(
            strategyId = null,
            strategyCode = code,
            amnesiaWipeRequired = StrategyProfileBehavior.amnesiaWipeRequired(code),
            strictTrackingProtection = StrategyProfileBehavior.strictTrackingProtection(code),
            canvasSpoofingActive = StrategyProfileBehavior.canvasSpoofingActive(code),
            urlSanitize = StrategyProfileBehavior.requiresUrlSanitize(code),
        )
    }
}

/**
 * Local strategy fallback. Used when the Railway strategy engine is unreachable.
 * Not an engine — it provides a strategy so the spoof run can proceed.
 */
class LocalStrategyFallback(
    private val installationIdProvider: () -> String = { DEFAULT_INSTALLATION_ID },
    private val bucketCalculator: (String) -> Int = { assignmentKey ->
        (assignmentKey.hashCode() and Int.MAX_VALUE) % BUCKET_MODULUS
    },
) : StrategyResolver {
    companion object {
        private const val DEFAULT_INSTALLATION_ID = "default_installation"
        private const val BUCKET_MODULUS = 100
        private const val YALE_SMART_PERCENT = 50
        private const val ENGINE_SELECTION_POLICY = "domain_installation_bucket_v1_50_50"
        private const val ENGINE_SELECTION_SCOPE = "domain+installation"
    }

    override suspend fun resolveStrategy(url: String, baselineTactics: List<String>): Result<StrategyResult> {
        val domain = normalizeDomain(url)
        val installationId = installationIdProvider().ifBlank { DEFAULT_INSTALLATION_ID }
        val assignmentKey = "$domain|$installationId"
        val bucket = bucketCalculator(assignmentKey).coerceIn(0, BUCKET_MODULUS - 1)
        val strategyCode = if (bucket < YALE_SMART_PERCENT) StrategyProfileBehavior.YALE_SMART else StrategyProfileBehavior.LEGACY
        val isYale = strategyCode == StrategyProfileBehavior.YALE_SMART
        return Result.success(
            StrategyResult(
                strategyId = null,
                strategyCode = strategyCode,
                amnesiaWipeRequired = isYale,
                strictTrackingProtection = isYale,
                canvasSpoofingActive = isYale,
                urlSanitize = isYale,
                wireguardConfig = "",
                strategyProfile = strategyCode,
                engineSelectionPolicy = ENGINE_SELECTION_POLICY,
                engineSelectionReason = "bucket=$bucket domain=$domain",
                engineSelectionKeyScope = ENGINE_SELECTION_SCOPE,
                engineSelectionBucket = bucket,
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
