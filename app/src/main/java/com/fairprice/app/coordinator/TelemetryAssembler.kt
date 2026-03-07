package com.fairprice.app.coordinator

import com.fairprice.app.data.RunLogResult
import com.fairprice.app.data.models.PriceCheck
import com.fairprice.app.data.models.PriceCheckAttempt
import com.fairprice.app.engine.ExtractionResult
import com.fairprice.app.engine.StrategyResult
import com.fairprice.app.viewmodel.SummaryData
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TelemetryAssembler {
    companion object {
        internal const val CLEAR_NET = "clear_net"
        internal const val CLEAR_NET_DISPLAY = "Clear Net"
        private const val ENGINE_VERSION = "11.5a"
        private const val ENGINE_BUILD_ID = "local-dev"
    }

    fun buildPriceCheck(
        url: String,
        strategyId: String?,
        strategyName: String?,
        baselinePriceCents: Int,
        foundPriceCents: Int,
        extractionSuccessful: Boolean,
        tactics: List<String>,
        attemptedConfigs: List<String>,
        finalConfig: String?,
        retryCount: Int,
        outcome: String,
        degraded: Boolean,
        baselineSuccess: Boolean,
        spoofSuccess: Boolean,
        dirtyBaselinePriceCents: Int?,
        diagnostics: List<String>,
        snifferPriceCents: Int?,
        cleanControlPriceCents: Int?,
        tacticSourcePass: String?,
        cleanControlExecutionMode: String,
        shadowSampled: Boolean,
        selectionMode: String? = null,
    ): PriceCheck {
        val domain = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        return PriceCheck(
            productUrl = url,
            domain = domain,
            baselinePriceCents = baselinePriceCents,
            foundPriceCents = foundPriceCents,
            strategyId = strategyId,
            strategyName = strategyName,
            extractionSuccessful = extractionSuccessful,
            attemptedConfigs = attemptedConfigs,
            finalConfig = finalConfig,
            finalConfigSource = resolveConfigSource(finalConfig),
            finalConfigProvider = resolveConfigProvider(finalConfig),
            retryCount = retryCount,
            outcome = outcome,
            degraded = degraded,
            baselineSuccess = baselineSuccess,
            spoofSuccess = spoofSuccess,
            dirtyBaselinePriceCents = dirtyBaselinePriceCents,
            selectionMode = selectionMode,
            rawExtractionData = buildJsonObject {
                put("detected_tactics", JsonArray(tactics.map { JsonPrimitive(it) }))
                put("diagnostics", JsonArray(diagnostics.map { JsonPrimitive(it) }))
                snifferPriceCents?.let { put("sniffer_price_cents", JsonPrimitive(it)) }
                cleanControlPriceCents?.let { put("clean_control_price_cents", JsonPrimitive(it)) }
                tacticSourcePass?.let { put("tactic_source_pass", JsonPrimitive(it)) }
                put("clean_control_execution_mode", JsonPrimitive(cleanControlExecutionMode))
                put("shadow_sampled", JsonPrimitive(shadowSampled))
            },
        )
    }

    fun buildAttemptRow(
        phase: String,
        attemptIndex: Int,
        vpnConfig: String?,
        success: Boolean,
        throwable: Throwable?,
        extracted: ExtractionResult?,
        latencyMs: Long,
        executionUrl: String?,
        appliedLevers: JsonObject?,
    ): PriceCheckAttempt {
        return PriceCheckAttempt(
            phase = phase,
            attemptIndex = attemptIndex,
            vpnConfig = vpnConfig,
            vpnConfigSource = resolveConfigSource(vpnConfig),
            vpnConfigProvider = resolveConfigProvider(vpnConfig),
            success = success,
            errorType = throwable?.javaClass?.simpleName,
            errorMessage = throwable?.message,
            extractedPriceCents = extracted?.priceCents,
            detectedTactics = extracted?.tactics,
            debugExtractionPath = extracted?.debugExtractionPath,
            latencyMs = latencyMs,
            executionUrl = executionUrl,
            appliedLevers = appliedLevers,
        )
    }

    fun buildAppliedLevers(
        urlSanitized: Boolean,
        amnesiaProtocol: Boolean? = null,
        trackingProtection: String? = null,
        strategy: StrategyResult? = null,
        strategyProfile: String? = null,
        engineSelectionSource: String? = null,
    ): JsonObject {
        return buildJsonObject {
            put("url_sanitized", JsonPrimitive(urlSanitized))
            if (amnesiaProtocol != null) put("amnesia_protocol", JsonPrimitive(amnesiaProtocol))
            if (trackingProtection != null) put("tracking_protection", JsonPrimitive(trackingProtection))
            if (strategy != null) {
                strategy.strategyId?.let { put("strategy_id", JsonPrimitive(it)) }
                put("strategy_code", JsonPrimitive(strategy.effectiveStrategyCode()))
                put("strategy_name", JsonPrimitive(strategy.strategyName))
                put("strategy_engine", JsonPrimitive(strategy.strategyEngineName))
                put("strategy_version", JsonPrimitive(strategy.strategyVersion))
                strategy.engineSelectionPolicy?.let { put("strategy_profile_policy", JsonPrimitive(it)) }
                strategy.engineSelectionReason?.let { put("strategy_profile_reason", JsonPrimitive(it)) }
                strategy.engineSelectionKeyScope?.let { put("strategy_profile_key_scope", JsonPrimitive(it)) }
                strategy.engineSelectionBucket?.let { put("strategy_profile_bucket", JsonPrimitive(it)) }
            }
            if (strategyProfile != null) {
                put("strategy_profile", JsonPrimitive(strategyProfile))
                put("engine_version", JsonPrimitive(ENGINE_VERSION))
                put("engine_build_id", JsonPrimitive(ENGINE_BUILD_ID))
            }
            if (engineSelectionSource != null) {
                put("engine_selection_source", JsonPrimitive(engineSelectionSource))
            }
        }
    }

    fun retryCountFromAttempts(attemptRows: List<PriceCheckAttempt>): Int {
        val spoofAttempts = attemptRows.count { it.phase == DefaultPriceCheckCoordinator.PHASE_SPOOF }
        return (spoofAttempts - 1).coerceAtLeast(0)
    }

    fun logResultDiagnostics(result: RunLogResult): List<String> {
        val diagnostics = mutableListOf<String>()
        if (!result.attemptsInserted) {
            val details = result.attemptInsertError?.takeIf { it.isNotBlank() } ?: "unknown error"
            diagnostics += "Partial telemetry: attempt rows failed to persist ($details)."
        }
        if (!result.retailerIntelInserted) {
            val details = result.retailerIntelError?.takeIf { it.isNotBlank() } ?: "unknown error"
            diagnostics += "Retailer intel write skipped ($details)."
        }
        return diagnostics
    }

    fun buildSuccessSummary(
        tacticSourceResult: ExtractionResult,
        snifferResult: ExtractionResult?,
        cleanControlResult: ExtractionResult?,
        tacticSourcePass: String,
        cleanControlExecutionMode: String,
        shadowSampled: Boolean,
        spoofedResult: ExtractionResult,
        dirtyBaselinePriceCents: Int?,
        attemptedConfigs: List<String>,
        finalConfig: String,
        retryCount: Int,
        outcome: String,
        diagnostics: List<String>,
        lifetimePotentialSavingsCents: Int,
        strategyName: String,
    ): SummaryData {
        val snifferPrice = snifferResult?.priceCents?.let(::formatUsd) ?: "N/A"
        val cleanControlPrice = cleanControlResult?.priceCents?.let(::formatUsd)
        val spoofed = formatUsd(spoofedResult.priceCents)
        val potentialSavingsCents = dirtyBaselinePriceCents?.minus(spoofedResult.priceCents)
        val isVictory = (potentialSavingsCents ?: 0) > 0
        val deployedConfigDisplay = displayConfigLabel(finalConfig)
        val attemptedDisplay = attemptedConfigs.map(::displayConfigLabel)
        val finalDisplay = displayConfigLabel(finalConfig)
        return SummaryData(
            lifetimePotentialSavings = formatUsd(lifetimePotentialSavingsCents),
            baselineConfig = "Not set",
            outcome = outcome,
            snifferPrice = snifferPrice,
            cleanControlPrice = cleanControlPrice,
            spoofedPrice = spoofed,
            dirtyBaselinePrice = dirtyBaselinePriceCents?.let(::formatUsd),
            potentialSavings = potentialSavingsCents?.takeIf { it > 0 }?.let(::formatUsd),
            isVictory = isVictory,
            tactics = tacticSourceResult.tactics,
            tacticSourcePass = tacticSourcePass,
            cleanControlExecutionMode = cleanControlExecutionMode,
            shadowSampled = shadowSampled,
            strategyName = strategyName,
            vpnConfig = deployedConfigDisplay,
            attemptedConfigs = attemptedDisplay,
            finalConfig = finalDisplay,
            retryCount = retryCount,
            diagnostics = diagnostics,
        )
    }

    fun resolveConfigSource(configId: String?): String? {
        if (configId.isNullOrBlank()) return null
        return if (configId == CLEAR_NET) "clear_net" else "asset"
    }

    fun resolveConfigProvider(configId: String?): String? {
        if (configId.isNullOrBlank()) return null
        return if (configId == CLEAR_NET) "clear_net" else "asset"
    }

    private fun formatUsd(cents: Int): String {
        return String.format(Locale.US, "$%.2f", cents / 100.0)
    }

    private fun displayConfigLabel(configId: String): String {
        return if (configId == CLEAR_NET) CLEAR_NET_DISPLAY else configId.removeSuffix(".conf").trim()
    }
}
