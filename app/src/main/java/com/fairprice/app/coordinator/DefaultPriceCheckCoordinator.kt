package com.fairprice.app.coordinator

import android.util.Log
import com.fairprice.app.viewmodel.EngineOverride
import com.fairprice.app.coordinator.model.CoordinatorProcessState
import com.fairprice.app.coordinator.model.CoordinatorState
import com.fairprice.app.coordinator.model.StartPriceCheckParams
import com.fairprice.app.data.FairPriceRepository
import com.fairprice.app.engine.StrategyProfileBehavior
import com.fairprice.app.engine.StrategyResolver
import com.fairprice.app.engine.StrategyResult
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DefaultPriceCheckCoordinator(
    private val scope: CoroutineScope,
    private val repository: FairPriceRepository,
    private val strategyResolver: StrategyResolver,
    private val telemetryAssembler: TelemetryAssembler,
    private val preSpoofStageRunner: PreSpoofStageRunner,
    private val spoofAttemptRunner: SpoofAttemptRunner,
    private val shortUrlResolver: suspend (String) -> String?,
) : PriceCheckCoordinator {
    companion object {
        private const val TAG = "PriceCheckCoordinator"
        const val PHASE_SNIFFER = "sniffer"
        const val PHASE_CLEAN_CONTROL = "clean_control"
        const val PHASE_SPOOF = "spoof"
        const val CLEAN_CONTROL_MODE_NONE = "none"
        const val CLEAN_CONTROL_MODE_FALLBACK = "fallback"
        const val CLEAN_CONTROL_MODE_SHADOW = "shadow"
    }

    private val _state = MutableStateFlow(CoordinatorState())
    override val state: StateFlow<CoordinatorState> = _state.asStateFlow()

    override fun startPriceCheck(params: StartPriceCheckParams) {
        val rawSubmittedUrl = params.rawSubmittedUrl.trim()
        _state.update { it.copy(processState = CoordinatorProcessState.Idle, showBrowser = false) }
        if (rawSubmittedUrl.isBlank()) return

        scope.launch {
            var terminalError: String? = null
            var successSummary: com.fairprice.app.viewmodel.SummaryData? = null
            var finalShowBrowser = false

            try {
                val submittedUrl = canonicalizeUrlIfNeeded(rawSubmittedUrl)
                val preSpoof = preSpoofStageRunner.run(
                    submittedUrl = submittedUrl,
                    onProcessing = ::emitProcessing,
                    throwableToMessage = { it.toUserMessage() },
                )
                when (preSpoof) {
                    is PreSpoofStageRunner.Result.TerminalFailure -> {
                        terminalError = preSpoof.terminalError
                        val failedTactics = buildList {
                            addAll(preSpoof.snifferExtraction?.tactics.orEmpty())
                            addAll(preSpoof.cleanControlExtraction?.tactics.orEmpty())
                        }.distinct()
                        emitProcessing("Logging fallback result...")
                        val failedPreSpoofCheck = telemetryAssembler.buildPriceCheck(
                            url = submittedUrl,
                            strategyId = null,
                            strategyName = null,
                            baselinePriceCents = 0,
                            foundPriceCents = 0,
                            extractionSuccessful = false,
                            tactics = failedTactics,
                            attemptedConfigs = emptyList(),
                            finalConfig = null,
                            retryCount = 0,
                            outcome = "degraded_pre_spoof_failed",
                            degraded = true,
                            baselineSuccess = false,
                            spoofSuccess = false,
                            dirtyBaselinePriceCents = params.dirtyBaselinePriceCents,
                            diagnostics = preSpoof.diagnostics,
                            snifferPriceCents = preSpoof.snifferExtraction?.priceCents,
                            cleanControlPriceCents = preSpoof.cleanControlExtraction?.priceCents,
                            tacticSourcePass = null,
                            cleanControlExecutionMode = preSpoof.cleanControlExecutionMode,
                            shadowSampled = preSpoof.shadowSampled,
                            selectionMode = null,
                        )
                        val fallbackLogResult =
                            repository.logPriceCheckRun(failedPreSpoofCheck, preSpoof.attemptRows)
                        if (fallbackLogResult.isFailure) {
                            val logThrowable = fallbackLogResult.exceptionOrNull()
                            val logMessage = "Supabase fallback log failed: ${logThrowable.toUserMessage()}"
                            Log.e(TAG, "price_checks fallback insert failed", logThrowable)
                            terminalError = "$terminalError | $logMessage"
                        }
                        finalShowBrowser = true
                        return@launch
                    }

                    is PreSpoofStageRunner.Result.Success -> {
                        emitProcessing("Determining strategy...")
                        val strategy =
                            strategyResolver.resolveStrategy(
                                url = submittedUrl,
                                baselineTactics = preSpoof.tacticSourceExtraction.tactics,
                                shoppingSessionId = params.shoppingSessionId,
                            ).getOrElse { throwable ->
                                terminalError = "Strategy resolution failed: ${throwable.toUserMessage()}"
                                val diagnostics = preSpoof.diagnostics.toMutableList().apply { add(terminalError.orEmpty()) }
                                Log.e(TAG, "Strategy resolution failed", throwable)
                                emitProcessing("Logging run result...")
                                val failedPriceCheck = telemetryAssembler.buildPriceCheck(
                                    url = submittedUrl,
                                    strategyId = null,
                                    strategyName = null,
                                    baselinePriceCents = preSpoof.tacticSourceExtraction.priceCents,
                                    foundPriceCents = preSpoof.tacticSourceExtraction.priceCents,
                                    extractionSuccessful = false,
                                    tactics = preSpoof.tacticSourceExtraction.tactics,
                                    attemptedConfigs = emptyList(),
                                    finalConfig = null,
                                    retryCount = 0,
                                    outcome = "strategy_failed",
                                    degraded = preSpoof.tacticSourcePass == PHASE_CLEAN_CONTROL,
                                    baselineSuccess = true,
                                    spoofSuccess = false,
                                    dirtyBaselinePriceCents = params.dirtyBaselinePriceCents,
                                    diagnostics = diagnostics,
                                    snifferPriceCents = preSpoof.snifferExtraction?.priceCents,
                                    cleanControlPriceCents = preSpoof.cleanControlExtraction?.priceCents,
                                    tacticSourcePass = preSpoof.tacticSourcePass,
                                    cleanControlExecutionMode = preSpoof.cleanControlExecutionMode,
                                    shadowSampled = preSpoof.shadowSampled,
                                    selectionMode = null,
                                )
                                val failedLogResult =
                                    repository.logPriceCheckRun(failedPriceCheck, preSpoof.attemptRows)
                                if (failedLogResult.isSuccess) {
                                    diagnostics += telemetryAssembler.logResultDiagnostics(failedLogResult.getOrThrow())
                                }
                                return@launch
                            }

                        val (activeStrategy, selectionSource) = resolveActiveStrategy(
                            strategy = strategy,
                            adminEngineOverride = params.adminEngineOverride,
                            isAdmin = params.isAdmin,
                        )
                        val spoofUrlPlan = if (activeStrategy.urlSanitize) {
                            sanitizeUrlForSpoof(submittedUrl)
                        } else {
                            SanitizedUrl(url = submittedUrl, wasSanitized = false)
                        }

                        val spoofResult = spoofAttemptRunner.execute(
                            strategy = activeStrategy,
                            engineSelectionSource = selectionSource,
                            spoofExecutionUrl = spoofUrlPlan.url,
                            spoofUrlSanitized = spoofUrlPlan.wasSanitized,
                            shoppingSessionId = params.shoppingSessionId,
                            attemptRows = preSpoof.attemptRows,
                            diagnostics = preSpoof.diagnostics,
                            onProcessing = ::emitProcessing,
                            throwableToMessage = { it.toUserMessage() },
                        )
                        val completion = completeRunAfterSpoof(
                            submittedUrl = submittedUrl,
                            preSpoof = preSpoof,
                            strategy = strategy,
                            dirtyBaselinePriceCents = params.dirtyBaselinePriceCents,
                            spoofResult = spoofResult,
                        )
                        terminalError = completion.terminalError
                        successSummary = completion.successSummary
                        finalShowBrowser = completion.finalShowBrowser
                    }
                }
            } finally {
                _state.update {
                    it.copy(
                        showBrowser = finalShowBrowser,
                        processState = terminalError?.let(CoordinatorProcessState::Error)
                            ?: successSummary?.let(CoordinatorProcessState::Success)
                            ?: CoordinatorProcessState.Idle,
                    )
                }
            }
        }
    }

    override fun onEnterShoppingMode() {
        _state.update { it.copy(showBrowser = true) }
    }

    override fun onBackToApp() {
        _state.update { it.copy(showBrowser = false) }
    }

    override fun onCloseShoppingSession() {
        _state.update {
            it.copy(showBrowser = false, processState = CoordinatorProcessState.Idle)
        }
    }

    override fun onAppClosing() {
        // No VPN to disconnect; no-op on clear-net.
    }

    private suspend fun completeRunAfterSpoof(
        submittedUrl: String,
        preSpoof: PreSpoofStageRunner.Result.Success,
        strategy: StrategyResult,
        dirtyBaselinePriceCents: Int?,
        spoofResult: SpoofAttemptRunner.Result.Completed,
    ): CompletionResult {
        val diagnostics = spoofResult.diagnostics.toMutableList()
        val attemptRows = spoofResult.attemptRows
        if (spoofResult.spoofedResult == null) {
            val terminalError = spoofResult.terminalError ?: "Spoofed extraction failed after bounded retry."
            val failedPriceCheck = telemetryAssembler.buildPriceCheck(
                url = submittedUrl,
                strategyId = strategy.strategyId,
                strategyName = strategy.strategyName,
                baselinePriceCents = preSpoof.tacticSourceExtraction.priceCents,
                foundPriceCents = preSpoof.tacticSourceExtraction.priceCents,
                extractionSuccessful = false,
                tactics = preSpoof.tacticSourceExtraction.tactics,
                attemptedConfigs = spoofResult.attemptedConfigs,
                finalConfig = spoofResult.finalConfig,
                retryCount = telemetryAssembler.retryCountFromAttempts(attemptRows),
                outcome = "spoof_failed",
                degraded = true,
                baselineSuccess = true,
                spoofSuccess = false,
                dirtyBaselinePriceCents = dirtyBaselinePriceCents,
                diagnostics = diagnostics,
                snifferPriceCents = preSpoof.snifferExtraction?.priceCents,
                cleanControlPriceCents = preSpoof.cleanControlExtraction?.priceCents,
                tacticSourcePass = preSpoof.tacticSourcePass,
                cleanControlExecutionMode = preSpoof.cleanControlExecutionMode,
                shadowSampled = preSpoof.shadowSampled,
                selectionMode = strategy.selectionMode,
            )
            emitProcessing("Logging run result...")
            val failedLogResult = repository.logPriceCheckRun(failedPriceCheck, attemptRows)
            if (failedLogResult.isFailure) {
                diagnostics += "Supabase log failed: ${failedLogResult.exceptionOrNull().toUserMessage()}"
            } else {
                diagnostics += telemetryAssembler.logResultDiagnostics(failedLogResult.getOrThrow())
            }
            return CompletionResult(
                terminalError = terminalError,
                successSummary = null,
                finalShowBrowser = false,
            )
        }

        val spoofedResult = spoofResult.spoofedResult
        val finalConfig = requireNotNull(spoofResult.finalConfig)
        val priceCheck = telemetryAssembler.buildPriceCheck(
            url = submittedUrl,
            strategyId = strategy.strategyId,
            strategyName = strategy.strategyName,
            baselinePriceCents = preSpoof.tacticSourceExtraction.priceCents,
            foundPriceCents = spoofedResult.priceCents,
            extractionSuccessful = true,
            tactics = preSpoof.tacticSourceExtraction.tactics,
            attemptedConfigs = spoofResult.attemptedConfigs,
            finalConfig = finalConfig,
            retryCount = telemetryAssembler.retryCountFromAttempts(attemptRows),
            outcome = if (preSpoof.tacticSourcePass == PHASE_CLEAN_CONTROL) {
                "degraded_sniffer_fallback_success"
            } else {
                "success"
            },
            degraded = preSpoof.tacticSourcePass == PHASE_CLEAN_CONTROL,
            baselineSuccess = true,
            spoofSuccess = true,
            dirtyBaselinePriceCents = dirtyBaselinePriceCents,
            diagnostics = diagnostics,
            snifferPriceCents = preSpoof.snifferExtraction?.priceCents,
            cleanControlPriceCents = preSpoof.cleanControlExtraction?.priceCents,
            tacticSourcePass = preSpoof.tacticSourcePass,
            cleanControlExecutionMode = preSpoof.cleanControlExecutionMode,
            shadowSampled = preSpoof.shadowSampled,
            selectionMode = strategy.selectionMode,
        )

        emitProcessing("Logging to database...")
        val logResult = repository.logPriceCheckRun(priceCheck, attemptRows)
        if (logResult.isFailure) {
            val throwable = logResult.exceptionOrNull()
            return CompletionResult(
                terminalError = "Supabase log failed: ${throwable.toUserMessage()}",
                successSummary = null,
                finalShowBrowser = false,
            )
        }
        val runLog = logResult.getOrThrow()
        diagnostics += telemetryAssembler.logResultDiagnostics(runLog)
        val lifetimeSavingsCents = repository.fetchLifetimePotentialSavingsCents().getOrDefault(0)
        val successSummary = telemetryAssembler.buildSuccessSummary(
            tacticSourceResult = preSpoof.tacticSourceExtraction,
            snifferResult = preSpoof.snifferExtraction,
            cleanControlResult = preSpoof.cleanControlExtraction,
            tacticSourcePass = preSpoof.tacticSourcePass,
            cleanControlExecutionMode = preSpoof.cleanControlExecutionMode,
            shadowSampled = preSpoof.shadowSampled,
            spoofedResult = spoofedResult,
            dirtyBaselinePriceCents = dirtyBaselinePriceCents,
            attemptedConfigs = spoofResult.attemptedConfigs,
            finalConfig = finalConfig,
            retryCount = telemetryAssembler.retryCountFromAttempts(attemptRows),
            outcome = if (runLog.attemptsInserted) {
                priceCheck.outcome ?: "success"
            } else {
                "partial_log_failure"
            },
            diagnostics = diagnostics,
            lifetimePotentialSavingsCents = lifetimeSavingsCents,
            strategyName = strategy.strategyName,
        )
        return CompletionResult(
            terminalError = null,
            successSummary = successSummary,
            finalShowBrowser = false,
        )
    }

    private suspend fun canonicalizeUrlIfNeeded(inputUrl: String): String {
        val host = runCatching { URI(inputUrl).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
        val isShortAmazonHost = host == "a.co" || host.endsWith(".a.co")
        if (!isShortAmazonHost) return inputUrl
        val resolved = shortUrlResolver(inputUrl)
        if (!resolved.isNullOrBlank()) {
            if (resolved != inputUrl) {
                Log.i(TAG, "Canonicalized short Amazon URL: $inputUrl -> $resolved")
            }
            return resolved
        }
        Log.w(TAG, "Failed to canonicalize short Amazon URL; using original: $inputUrl")
        return inputUrl
    }

    private fun sanitizeUrlForSpoof(inputUrl: String): SanitizedUrl {
        val fragmentIndex = inputUrl.indexOf('#')
        val baseWithQuery = if (fragmentIndex >= 0) inputUrl.substring(0, fragmentIndex) else inputUrl
        val fragmentSuffix = if (fragmentIndex >= 0) inputUrl.substring(fragmentIndex) else ""
        val queryIndex = baseWithQuery.indexOf('?')
        if (queryIndex < 0) return SanitizedUrl(url = inputUrl, wasSanitized = false)
        val base = baseWithQuery.substring(0, queryIndex)
        val rawQuery = baseWithQuery.substring(queryIndex + 1)
        if (rawQuery.isBlank()) return SanitizedUrl(url = inputUrl, wasSanitized = false)
        val keptParts = rawQuery
            .split("&")
            .filter { it.isNotBlank() }
            .filterNot { rawPart ->
                val rawKey = rawPart.substringBefore('=', rawPart).trim()
                if (rawKey.isBlank()) return@filterNot false
                val decodedKey = runCatching {
                    java.net.URLDecoder.decode(rawKey, Charsets.UTF_8.name())
                }.getOrDefault(rawKey)
                val normalizedKey = decodedKey.lowercase(Locale.US)
                normalizedKey.startsWith("utm_") || normalizedKey in setOf("gclid", "fbclid", "ref")
            }
        val sanitizedBase = if (keptParts.isEmpty()) base else "$base?${keptParts.joinToString("&")}"
        val sanitizedUrl = "$sanitizedBase$fragmentSuffix"
        return SanitizedUrl(url = sanitizedUrl, wasSanitized = sanitizedUrl != inputUrl)
    }

    private fun resolveActiveStrategy(
        strategy: StrategyResult,
        adminEngineOverride: EngineOverride,
        isAdmin: Boolean,
    ): Pair<StrategyResult, String> {
        if (!isAdmin || adminEngineOverride == EngineOverride.AUTO) {
            return strategy to "strategy"
        }
        return when (adminEngineOverride) {
            EngineOverride.AUTO -> strategy to "strategy"
            EngineOverride.FORCE_CLEAN_BASELINE -> strategy.copy(
                strategyCode = "clean_baseline",
                strategyProfile = "clean_baseline",
                strategyName = "Clean Baseline",
                amnesiaWipeRequired = false,
                strictTrackingProtection = false,
                canvasSpoofingActive = false,
                urlSanitize = false,
            ) to "admin_override"
            EngineOverride.FORCE_SHIELD_BASIC -> strategy.copy(
                strategyCode = "shield_basic",
                strategyProfile = "shield_basic",
                strategyName = "Shield Basic",
                amnesiaWipeRequired = false,
                strictTrackingProtection = true,
                canvasSpoofingActive = false,
                urlSanitize = true,
            ) to "admin_override"
            EngineOverride.FORCE_AMNESIA_STANDARD -> strategy.copy(
                strategyCode = "amnesia_standard",
                strategyProfile = "amnesia_standard",
                strategyName = "Amnesia Standard",
                amnesiaWipeRequired = true,
                strictTrackingProtection = true,
                canvasSpoofingActive = false,
                urlSanitize = true,
            ) to "admin_override"
            EngineOverride.FORCE_STEALTH_MAX -> strategy.copy(
                strategyCode = "stealth_max",
                strategyProfile = "stealth_max",
                strategyName = "Stealth Max",
                amnesiaWipeRequired = true,
                strictTrackingProtection = true,
                canvasSpoofingActive = true,
                urlSanitize = true,
            ) to "admin_override"
        }
    }

    private fun emitProcessing(message: String) {
        _state.update { it.copy(processState = CoordinatorProcessState.Processing(message)) }
    }

    private fun Throwable?.toUserMessage(): String {
        val throwable = this ?: return "Unknown error"
        return throwable.message ?: throwable::class.java.simpleName
    }

    private data class SanitizedUrl(
        val url: String,
        val wasSanitized: Boolean,
    )

    private data class CompletionResult(
        val terminalError: String?,
        val successSummary: com.fairprice.app.viewmodel.SummaryData?,
        val finalShowBrowser: Boolean,
    )
}
