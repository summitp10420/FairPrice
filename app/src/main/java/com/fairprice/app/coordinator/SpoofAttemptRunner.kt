package com.fairprice.app.coordinator

import android.util.Log
import com.fairprice.app.data.models.PriceCheckAttempt
import com.fairprice.app.engine.CleanSessionPreparationException
import com.fairprice.app.engine.ExtractionEngine
import com.fairprice.app.engine.ExtractionRequest
import com.fairprice.app.engine.ExtractionResult
import com.fairprice.app.engine.StrategyProfileBehavior
import com.fairprice.app.engine.StrategyResult
import kotlin.system.measureTimeMillis

class SpoofAttemptRunner(
    private val extractionEngine: ExtractionEngine,
    private val telemetryAssembler: TelemetryAssembler,
) {
    companion object {
        private const val TAG = "SpoofAttemptRunner"
        private const val SPOOF_ATTEMPT_MAX = 2
    }

    sealed interface Result {
        data class Completed(
            val spoofedResult: ExtractionResult?,
            val finalConfig: String?,
            val attemptRows: List<PriceCheckAttempt>,
            val attemptedConfigs: List<String>,
            val diagnostics: List<String>,
            val terminalError: String?,
        ) : Result
    }

    suspend fun execute(
        strategy: StrategyResult,
        strategyProfile: String,
        engineSelectionSource: String,
        spoofExecutionUrl: String,
        spoofUrlSanitized: Boolean,
        attemptRows: List<PriceCheckAttempt>,
        diagnostics: List<String>,
        onProcessing: (String) -> Unit,
        throwableToMessage: (Throwable?) -> String,
    ): Result.Completed {
        val mutableAttemptRows = attemptRows.toMutableList()
        val mutableDiagnostics = diagnostics.toMutableList()
        var spoofedResult: ExtractionResult? = null
        var terminalError: String? = null
        val strictTrackingProtection = StrategyProfileBehavior.trackingProtection(strategyProfile) == "strict"
        val navigationUrl = buildEngineBootstrapNavigationUrl(spoofExecutionUrl, strategyProfile)

        for (attempt in 0 until SPOOF_ATTEMPT_MAX) {
            val attemptNumber = attempt + 1
            onProcessing("Extracting spoofed price ($attemptNumber/$SPOOF_ATTEMPT_MAX)...")
            Log.i(TAG, "Spoof execution URL (sanitized=$spoofUrlSanitized): $spoofExecutionUrl")
            var extractionResult: kotlin.Result<ExtractionResult>? = null
            val latencyMs = measureTimeMillis {
                extractionResult = extractionEngine.loadAndExtract(
                    navigationUrl,
                    request = ExtractionRequest(
                        cleanSessionRequired = true,
                        phase = DefaultPriceCheckCoordinator.PHASE_SPOOF,
                        strictTrackingProtection = strictTrackingProtection,
                    ),
                )
            }
            val result = extractionResult!!
            when {
                result.isSuccess -> {
                    val extracted = result.getOrThrow()
                    if (extracted.isWafBlockDetected()) {
                        val wafThrowable = IllegalStateException("waf_block detected during Spoof Pass")
                        mutableAttemptRows += telemetryAssembler.buildAttemptRow(
                            phase = DefaultPriceCheckCoordinator.PHASE_SPOOF,
                            attemptIndex = attemptNumber,
                            vpnConfig = TelemetryAssembler.CLEAR_NET,
                            success = false,
                            throwable = wafThrowable,
                            extracted = extracted,
                            latencyMs = latencyMs,
                            executionUrl = spoofExecutionUrl,
                            appliedLevers = telemetryAssembler.buildAppliedLevers(
                                urlSanitized = spoofUrlSanitized,
                                amnesiaProtocol = true,
                                trackingProtection = StrategyProfileBehavior.trackingProtection(strategyProfile),
                                strategy = strategy,
                                strategyProfile = strategyProfile,
                                engineSelectionSource = engineSelectionSource,
                            ),
                        )
                        mutableDiagnostics += "Spoof attempt blocked by WAF."
                    } else {
                        mutableAttemptRows += telemetryAssembler.buildAttemptRow(
                            phase = DefaultPriceCheckCoordinator.PHASE_SPOOF,
                            attemptIndex = attemptNumber,
                            vpnConfig = TelemetryAssembler.CLEAR_NET,
                            success = true,
                            throwable = null,
                            extracted = extracted,
                            latencyMs = latencyMs,
                            executionUrl = spoofExecutionUrl,
                            appliedLevers = telemetryAssembler.buildAppliedLevers(
                                urlSanitized = spoofUrlSanitized,
                                amnesiaProtocol = true,
                                trackingProtection = StrategyProfileBehavior.trackingProtection(strategyProfile),
                                strategy = strategy,
                                strategyProfile = strategyProfile,
                                engineSelectionSource = engineSelectionSource,
                            ),
                        )
                        spoofedResult = extracted
                    }
                }
                else -> {
                    val throwable = result.exceptionOrNull()
                    val userMessage = when {
                        isCleanSessionPreparationFailure(throwable) ->
                            "Spoof attempt blocked: unable to prepare a clean session."
                        else -> "Spoof attempt failed: ${throwableToMessage(throwable)}"
                    }
                    mutableAttemptRows += telemetryAssembler.buildAttemptRow(
                        phase = DefaultPriceCheckCoordinator.PHASE_SPOOF,
                        attemptIndex = attemptNumber,
                        vpnConfig = TelemetryAssembler.CLEAR_NET,
                        success = false,
                        throwable = throwable,
                        extracted = null,
                        latencyMs = latencyMs,
                        executionUrl = spoofExecutionUrl,
                        appliedLevers = telemetryAssembler.buildAppliedLevers(
                            urlSanitized = spoofUrlSanitized,
                            amnesiaProtocol = !isCleanSessionPreparationFailure(throwable),
                            trackingProtection = StrategyProfileBehavior.trackingProtection(strategyProfile),
                            strategy = strategy,
                            strategyProfile = strategyProfile,
                            engineSelectionSource = engineSelectionSource,
                        ),
                    )
                    mutableDiagnostics += userMessage
                    if (throwable != null) {
                        Log.e(TAG, "Spoof attempt failed (attempt=$attemptNumber)", throwable)
                    }
                }
            }
            if (spoofedResult != null) break
        }

        if (spoofedResult == null && terminalError == null) {
            terminalError = "Spoofed extraction failed after bounded retry."
        }

        return Result.Completed(
            spoofedResult = spoofedResult,
            finalConfig = if (spoofedResult != null) TelemetryAssembler.CLEAR_NET else null,
            attemptRows = mutableAttemptRows,
            attemptedConfigs = emptyList(),
            diagnostics = mutableDiagnostics,
            terminalError = terminalError,
        )
    }

    private fun isCleanSessionPreparationFailure(throwable: Throwable?): Boolean {
        return throwable is CleanSessionPreparationException ||
            throwable?.cause is CleanSessionPreparationException
    }

    private fun buildEngineBootstrapNavigationUrl(executionUrl: String, profileCode: String): String {
        return appendEngineBootstrapToken(executionUrl, StrategyProfileBehavior.bootstrapTokenValue(profileCode))
    }
}
