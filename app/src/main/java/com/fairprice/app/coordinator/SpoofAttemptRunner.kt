package com.fairprice.app.coordinator

import android.util.Log
import com.fairprice.app.data.models.PriceCheckAttempt
import com.fairprice.app.engine.CleanSessionPreparationException
import com.fairprice.app.engine.EngineProfile
import com.fairprice.app.engine.ExtractionEngine
import com.fairprice.app.engine.ExtractionRequest
import com.fairprice.app.engine.ExtractionResult
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
        engineProfile: EngineProfile,
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
        val strictTrackingProtection = engineProfile == EngineProfile.YALE_SMART
        val navigationUrl = buildEngineBootstrapNavigationUrl(spoofExecutionUrl, engineProfile)

        for (attempt in 0 until SPOOF_ATTEMPT_MAX) {
            val attemptNumber = attempt + 1
            onProcessing("Extracting spoofed price ($attemptNumber/$SPOOF_ATTEMPT_MAX)...")
            Log.i(TAG, "Spoof execution URL (sanitized=$spoofUrlSanitized): $spoofExecutionUrl")
            val latencyMs = measureTimeMillis {
                val extractionResult = extractionEngine.loadAndExtract(
                    navigationUrl,
                    request = ExtractionRequest(
                        cleanSessionRequired = true,
                        phase = DefaultPriceCheckCoordinator.PHASE_SPOOF,
                        strictTrackingProtection = strictTrackingProtection,
                    ),
                )
                when {
                    extractionResult.isSuccess -> {
                        val extracted = extractionResult.getOrThrow()
                        if (extracted.isWafBlockDetected()) {
                            val wafThrowable = IllegalStateException("waf_block detected during Spoof Pass")
                            mutableAttemptRows += telemetryAssembler.buildAttemptRow(
                                phase = DefaultPriceCheckCoordinator.PHASE_SPOOF,
                                attemptIndex = attemptNumber,
                                vpnConfig = TelemetryAssembler.CLEAR_NET,
                                success = false,
                                throwable = wafThrowable,
                                extracted = extracted,
                                latencyMs = 0L,
                                executionUrl = spoofExecutionUrl,
                                appliedLevers = telemetryAssembler.buildAppliedLevers(
                                    urlSanitized = spoofUrlSanitized,
                                    amnesiaProtocol = true,
                                    trackingProtection = trackingProtectionForProfile(engineProfile),
                                    strategy = strategy,
                                    engineProfile = engineProfile,
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
                                latencyMs = 0L,
                                executionUrl = spoofExecutionUrl,
                                appliedLevers = telemetryAssembler.buildAppliedLevers(
                                    urlSanitized = spoofUrlSanitized,
                                    amnesiaProtocol = true,
                                    trackingProtection = trackingProtectionForProfile(engineProfile),
                                    strategy = strategy,
                                    engineProfile = engineProfile,
                                    engineSelectionSource = engineSelectionSource,
                                ),
                            )
                            spoofedResult = extracted
                        }
                    }
                    else -> {
                        val throwable = extractionResult.exceptionOrNull()
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
                            latencyMs = 0L,
                            executionUrl = spoofExecutionUrl,
                            appliedLevers = telemetryAssembler.buildAppliedLevers(
                                urlSanitized = spoofUrlSanitized,
                                amnesiaProtocol = !isCleanSessionPreparationFailure(throwable),
                                trackingProtection = trackingProtectionForProfile(engineProfile),
                                strategy = strategy,
                                engineProfile = engineProfile,
                                engineSelectionSource = engineSelectionSource,
                            ),
                        )
                        mutableDiagnostics += userMessage
                        if (throwable != null) {
                            Log.e(TAG, "Spoof attempt failed (attempt=$attemptNumber)", throwable)
                        }
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

    private fun trackingProtectionForProfile(profile: EngineProfile): String {
        return if (profile == EngineProfile.YALE_SMART) "strict" else "off"
    }

    private fun isCleanSessionPreparationFailure(throwable: Throwable?): Boolean {
        return throwable is CleanSessionPreparationException ||
            throwable?.cause is CleanSessionPreparationException
    }

    private fun ExtractionResult.isWafBlockDetected(): Boolean {
        if (debugExtractionPath.equals("waf_block", ignoreCase = true)) return true
        return tactics.any { it.startsWith("block_", ignoreCase = true) }
    }

    private fun buildEngineBootstrapNavigationUrl(executionUrl: String, profile: EngineProfile): String {
        return appendEngineBootstrapToken(executionUrl, profile.toTelemetryValue())
    }

    private fun appendEngineBootstrapToken(executionUrl: String, tokenValue: String): String {
        val engineHashKey = "fp_engine"
        val hashIndex = executionUrl.indexOf('#')
        if (hashIndex < 0) {
            return "$executionUrl#$engineHashKey=$tokenValue"
        }
        val base = executionUrl.substring(0, hashIndex)
        val existingHash = executionUrl.substring(hashIndex + 1)
        val hashParts = existingHash
            .split("&")
            .filter { it.isNotBlank() }
            .filterNot { part ->
                part.substringBefore('=').trim().equals(engineHashKey, ignoreCase = true)
            }
            .toMutableList()
        hashParts += "$engineHashKey=$tokenValue"
        return "$base#${hashParts.joinToString("&")}"
    }

    private fun EngineProfile.toTelemetryValue(): String {
        return when (this) {
            EngineProfile.LEGACY -> "clean_control_v1"
            EngineProfile.YALE_SMART -> "yale_smart"
        }
    }
}
