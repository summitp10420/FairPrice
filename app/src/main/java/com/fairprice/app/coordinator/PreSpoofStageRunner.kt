package com.fairprice.app.coordinator

import android.util.Log
import com.fairprice.app.data.models.PriceCheckAttempt
import com.fairprice.app.engine.ExtractionEngine
import com.fairprice.app.engine.ExtractionRequest
import com.fairprice.app.engine.ExtractionResult
import kotlin.system.measureTimeMillis

class PreSpoofStageRunner(
    private val extractionEngine: ExtractionEngine,
    private val telemetryAssembler: TelemetryAssembler,
    private val shadowCleanControlSampler: (String) -> Boolean,
) {
    companion object {
        private const val TAG = "PreSpoofStageRunner"
        private const val ENGINE_HASH_KEY = "fp_engine"
        private const val SNIFFER_INTEL_TOKEN = "sniffer_intel"
        private const val CLEAN_CONTROL_INTEL_TOKEN = "clean_control_intel"
        private const val TRACKING_PROTECTION_OFF = "off"
    }

    sealed interface Result {
        data class Success(
            val snifferExtraction: ExtractionResult?,
            val cleanControlExtraction: ExtractionResult?,
            val tacticSourceExtraction: ExtractionResult,
            val tacticSourcePass: String,
            val cleanControlExecutionMode: String,
            val shadowSampled: Boolean,
            val attemptRows: List<PriceCheckAttempt>,
            val diagnostics: List<String>,
        ) : Result

        data class TerminalFailure(
            val snifferExtraction: ExtractionResult?,
            val cleanControlExtraction: ExtractionResult?,
            val tacticSourcePass: String?,
            val cleanControlExecutionMode: String,
            val shadowSampled: Boolean,
            val attemptRows: List<PriceCheckAttempt>,
            val diagnostics: List<String>,
            val terminalError: String,
        ) : Result
    }

    suspend fun run(
        submittedUrl: String,
        onProcessing: (String) -> Unit,
        throwableToMessage: (Throwable?) -> String,
    ): Result {
        val attemptRows = mutableListOf<PriceCheckAttempt>()
        val diagnostics = mutableListOf<String>()
        var snifferExtraction: ExtractionResult? = null
        var cleanControlExtraction: ExtractionResult? = null
        var shadowSampled = false
        var cleanControlExecutionMode = DefaultPriceCheckCoordinator.CLEAN_CONTROL_MODE_NONE
        var tacticSourcePass = DefaultPriceCheckCoordinator.PHASE_SNIFFER

        onProcessing("Running Sniffer Pass...")
        var snifferResultValue: kotlin.Result<ExtractionResult> =
            kotlin.Result.failure(IllegalStateException("Sniffer pass did not run."))
        val snifferNavigationUrl = buildSnifferNavigationUrl(submittedUrl)
        val snifferLatencyMs = measureTimeMillis {
            snifferResultValue = extractionEngine.loadAndExtract(
                snifferNavigationUrl,
                request = ExtractionRequest(
                    cleanSessionRequired = true,
                    phase = DefaultPriceCheckCoordinator.PHASE_SNIFFER,
                    strictTrackingProtection = false,
                ),
            )
        }
        val snifferResult = snifferResultValue
        val snifferCandidate = snifferResult.getOrNull()
        val snifferWafBlocked = snifferCandidate?.isWafBlockDetected() == true
        if (snifferResult.isFailure || snifferWafBlocked) {
            val throwable = if (snifferResult.isFailure) snifferResult.exceptionOrNull()
            else IllegalStateException("waf_block detected during Sniffer Pass")
            snifferExtraction = snifferCandidate
            attemptRows += telemetryAssembler.buildAttemptRow(
                phase = DefaultPriceCheckCoordinator.PHASE_SNIFFER,
                attemptIndex = 0,
                vpnConfig = null,
                success = false,
                throwable = throwable,
                extracted = snifferCandidate,
                latencyMs = snifferLatencyMs,
                executionUrl = submittedUrl,
                appliedLevers = telemetryAssembler.buildAppliedLevers(
                    urlSanitized = false,
                    amnesiaProtocol = false,
                    trackingProtection = TRACKING_PROTECTION_OFF,
                ),
            )
            if (snifferWafBlocked) {
                diagnostics += "Sniffer Pass blocked by WAF. Running Clean Control fallback."
                Log.w(TAG, "Sniffer pass returned waf_block; routing to Clean Control fallback.")
            } else {
                diagnostics += "Sniffer Pass failed: ${throwableToMessage(throwable)}"
                Log.e(TAG, "Sniffer pass failed", throwable)
            }

            onProcessing("Running Clean Control fallback...")
            cleanControlExecutionMode = DefaultPriceCheckCoordinator.CLEAN_CONTROL_MODE_FALLBACK
            var cleanControlResultValue: kotlin.Result<ExtractionResult> =
                kotlin.Result.failure(IllegalStateException("Clean Control fallback did not run."))
            val cleanControlNavigationUrl = buildCleanControlNavigationUrl(submittedUrl)
            val cleanControlLatencyMs = measureTimeMillis {
                cleanControlResultValue = extractionEngine.loadAndExtract(
                    cleanControlNavigationUrl,
                    request = ExtractionRequest(
                        cleanSessionRequired = true,
                        phase = DefaultPriceCheckCoordinator.PHASE_CLEAN_CONTROL,
                        strictTrackingProtection = false,
                    ),
                )
            }
            val cleanControlResult = cleanControlResultValue
            if (cleanControlResult.isFailure) {
                val cleanControlThrowable = cleanControlResult.exceptionOrNull()
                attemptRows += telemetryAssembler.buildAttemptRow(
                    phase = DefaultPriceCheckCoordinator.PHASE_CLEAN_CONTROL,
                    attemptIndex = 0,
                    vpnConfig = null,
                    success = false,
                    throwable = cleanControlThrowable,
                    extracted = null,
                    latencyMs = cleanControlLatencyMs,
                    executionUrl = submittedUrl,
                    appliedLevers = telemetryAssembler.buildAppliedLevers(
                        urlSanitized = false,
                        amnesiaProtocol = false,
                        trackingProtection = TRACKING_PROTECTION_OFF,
                    ),
                )
                val terminalError =
                    "Sniffer pre-spoof stage failed and Clean Control fallback failed: ${
                        throwableToMessage(cleanControlThrowable)
                    }. You can continue shopping manually."
                diagnostics += terminalError
                return Result.TerminalFailure(
                    snifferExtraction = snifferExtraction,
                    cleanControlExtraction = cleanControlExtraction,
                    tacticSourcePass = null,
                    cleanControlExecutionMode = cleanControlExecutionMode,
                    shadowSampled = false,
                    attemptRows = attemptRows,
                    diagnostics = diagnostics,
                    terminalError = terminalError,
                )
            }

            cleanControlExtraction = cleanControlResult.getOrThrow()
            attemptRows += telemetryAssembler.buildAttemptRow(
                phase = DefaultPriceCheckCoordinator.PHASE_CLEAN_CONTROL,
                attemptIndex = 0,
                vpnConfig = null,
                success = true,
                throwable = null,
                extracted = cleanControlExtraction,
                latencyMs = cleanControlLatencyMs,
                executionUrl = submittedUrl,
                appliedLevers = telemetryAssembler.buildAppliedLevers(
                    urlSanitized = false,
                    amnesiaProtocol = true,
                    trackingProtection = TRACKING_PROTECTION_OFF,
                ),
            )
            tacticSourcePass = DefaultPriceCheckCoordinator.PHASE_CLEAN_CONTROL
            diagnostics += "Sniffer Pass failed. Clean Control fallback succeeded."
        } else {
            snifferExtraction = snifferResult.getOrThrow()
            attemptRows += telemetryAssembler.buildAttemptRow(
                phase = DefaultPriceCheckCoordinator.PHASE_SNIFFER,
                attemptIndex = 0,
                vpnConfig = null,
                success = true,
                throwable = null,
                extracted = snifferExtraction,
                latencyMs = snifferLatencyMs,
                executionUrl = submittedUrl,
                appliedLevers = telemetryAssembler.buildAppliedLevers(
                    urlSanitized = false,
                    amnesiaProtocol = true,
                    trackingProtection = TRACKING_PROTECTION_OFF,
                ),
            )
            shadowSampled = shadowCleanControlSampler(submittedUrl)
            if (shadowSampled) {
                cleanControlExecutionMode = DefaultPriceCheckCoordinator.CLEAN_CONTROL_MODE_SHADOW
                onProcessing("Running Clean Control shadow pass...")
                var cleanControlShadowValue: kotlin.Result<ExtractionResult> =
                    kotlin.Result.failure(IllegalStateException("Clean Control shadow pass did not run."))
                val cleanControlNavigationUrl = buildCleanControlNavigationUrl(submittedUrl)
                val cleanControlShadowLatencyMs = measureTimeMillis {
                    cleanControlShadowValue = extractionEngine.loadAndExtract(
                        cleanControlNavigationUrl,
                        request = ExtractionRequest(
                            cleanSessionRequired = true,
                            phase = DefaultPriceCheckCoordinator.PHASE_CLEAN_CONTROL,
                            strictTrackingProtection = false,
                        ),
                    )
                }
                val cleanControlShadowResult = cleanControlShadowValue
                if (cleanControlShadowResult.isSuccess) {
                    cleanControlExtraction = cleanControlShadowResult.getOrThrow()
                    attemptRows += telemetryAssembler.buildAttemptRow(
                        phase = DefaultPriceCheckCoordinator.PHASE_CLEAN_CONTROL,
                        attemptIndex = 0,
                        vpnConfig = null,
                        success = true,
                        throwable = null,
                        extracted = cleanControlExtraction,
                        latencyMs = cleanControlShadowLatencyMs,
                        executionUrl = submittedUrl,
                        appliedLevers = telemetryAssembler.buildAppliedLevers(
                            urlSanitized = false,
                            amnesiaProtocol = true,
                            trackingProtection = TRACKING_PROTECTION_OFF,
                        ),
                    )
                    diagnostics += "Clean Control Pass shadow telemetry captured."
                } else {
                    val shadowThrowable = cleanControlShadowResult.exceptionOrNull()
                    attemptRows += telemetryAssembler.buildAttemptRow(
                        phase = DefaultPriceCheckCoordinator.PHASE_CLEAN_CONTROL,
                        attemptIndex = 0,
                        vpnConfig = null,
                        success = false,
                        throwable = shadowThrowable,
                        extracted = null,
                        latencyMs = cleanControlShadowLatencyMs,
                        executionUrl = submittedUrl,
                        appliedLevers = telemetryAssembler.buildAppliedLevers(
                            urlSanitized = false,
                            amnesiaProtocol = false,
                            trackingProtection = TRACKING_PROTECTION_OFF,
                        ),
                    )
                    diagnostics +=
                        "Clean Control Pass shadow failed (non-terminal): ${throwableToMessage(shadowThrowable)}"
                }
            }
        }

        val tacticSourceExtraction = if (tacticSourcePass == DefaultPriceCheckCoordinator.PHASE_CLEAN_CONTROL) {
            cleanControlExtraction
        } else {
            snifferExtraction
        }
        if (tacticSourceExtraction == null) {
            val terminalError = "No successful pre-spoof pass available. You can continue shopping manually."
            diagnostics += terminalError
            return Result.TerminalFailure(
                snifferExtraction = snifferExtraction,
                cleanControlExtraction = cleanControlExtraction,
                tacticSourcePass = null,
                cleanControlExecutionMode = cleanControlExecutionMode,
                shadowSampled = shadowSampled,
                attemptRows = attemptRows,
                diagnostics = diagnostics,
                terminalError = terminalError,
            )
        }

        return Result.Success(
            snifferExtraction = snifferExtraction,
            cleanControlExtraction = cleanControlExtraction,
            tacticSourceExtraction = tacticSourceExtraction,
            tacticSourcePass = tacticSourcePass,
            cleanControlExecutionMode = cleanControlExecutionMode,
            shadowSampled = shadowSampled,
            attemptRows = attemptRows,
            diagnostics = diagnostics,
        )
    }

    private fun buildSnifferNavigationUrl(executionUrl: String): String {
        return appendEngineBootstrapToken(executionUrl, SNIFFER_INTEL_TOKEN)
    }

    private fun buildCleanControlNavigationUrl(executionUrl: String): String {
        return appendEngineBootstrapToken(executionUrl, CLEAN_CONTROL_INTEL_TOKEN)
    }

    private fun appendEngineBootstrapToken(executionUrl: String, tokenValue: String): String {
        val hashIndex = executionUrl.indexOf('#')
        if (hashIndex < 0) return "$executionUrl#$ENGINE_HASH_KEY=$tokenValue"
        val base = executionUrl.substring(0, hashIndex)
        val existingHash = executionUrl.substring(hashIndex + 1)
        val hashParts = existingHash
            .split("&")
            .filter { it.isNotBlank() }
            .filterNot { part ->
                part.substringBefore('=').trim().equals(ENGINE_HASH_KEY, ignoreCase = true)
            }
            .toMutableList()
        hashParts += "$ENGINE_HASH_KEY=$tokenValue"
        return "$base#${hashParts.joinToString("&")}"
    }

    private fun ExtractionResult.isWafBlockDetected(): Boolean {
        if (debugExtractionPath.equals("waf_block", ignoreCase = true)) return true
        return tactics.any { it.startsWith("block_", ignoreCase = true) }
    }
}
