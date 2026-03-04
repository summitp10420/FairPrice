package com.fairprice.app.coordinator

import android.content.Intent
import android.util.Log
import com.fairprice.app.data.models.PriceCheckAttempt
import com.fairprice.app.engine.CleanSessionPreparationException
import com.fairprice.app.engine.EngineProfile
import com.fairprice.app.engine.ExtractionEngine
import com.fairprice.app.engine.ExtractionRequest
import com.fairprice.app.engine.ExtractionResult
import com.fairprice.app.engine.StrategyResult
import com.fairprice.app.engine.VpnEngine
import com.fairprice.app.engine.VpnPermissionRequiredException
import com.fairprice.app.engine.VpnRotationEngine
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.system.measureTimeMillis

class SpoofAttemptRunner(
    private val vpnEngine: VpnEngine,
    private val vpnRotationEngine: VpnRotationEngine,
    private val extractionEngine: ExtractionEngine,
    private val telemetryAssembler: TelemetryAssembler,
) {
    companion object {
        private const val TAG = "SpoofAttemptRunner"
        private const val VPN_STABILIZATION_DELAY_MS = 2_000L
        private const val SPOOF_ATTEMPT_MAX = 2
        private const val TRACKING_PROTECTION_STRICT = "strict"
        private const val TRACKING_PROTECTION_OFF = "off"
        private const val ENGINE_HASH_KEY = "fp_engine"
        private const val CONTROL_PROFILE_TOKEN = "clean_control_v1"
        private const val YALE_SMART_PROFILE_TOKEN = "yale_smart"
    }

    data class PendingVpnContinuation(
        val strategy: StrategyResult,
        val attemptIndex: Int,
        val waitingConfig: String,
        val spoofExecutionUrl: String,
        val spoofUrlSanitized: Boolean,
        val spoofEngineProfile: EngineProfile,
        val spoofEngineSelectionSource: String,
        val attemptedConfigs: List<String>,
        val diagnostics: List<String>,
        val attemptRows: List<PriceCheckAttempt>,
    )

    sealed interface Result {
        data class Completed(
            val spoofedResult: ExtractionResult?,
            val finalConfig: String?,
            val attemptRows: List<PriceCheckAttempt>,
            val attemptedConfigs: List<String>,
            val diagnostics: List<String>,
            val terminalError: String?,
            val vpnConnectedThisRun: Boolean,
            val activeVpnConfig: String?,
        ) : Result

        data class AwaitingPermission(
            val intent: Intent,
            val pending: PendingVpnContinuation,
        ) : Result
    }

    suspend fun execute(
        strategy: StrategyResult,
        engineProfile: EngineProfile,
        engineSelectionSource: String,
        spoofExecutionUrl: String,
        spoofUrlSanitized: Boolean,
        attemptedConfigs: List<String>,
        attemptRows: List<PriceCheckAttempt>,
        diagnostics: List<String>,
        onProcessing: (String) -> Unit,
        throwableToMessage: (Throwable?) -> String,
    ): Result {
        return runLoop(
            strategy = strategy,
            engineProfile = engineProfile,
            engineSelectionSource = engineSelectionSource,
            spoofExecutionUrl = spoofExecutionUrl,
            spoofUrlSanitized = spoofUrlSanitized,
            attemptedConfigs = attemptedConfigs.toMutableList(),
            attemptRows = attemptRows.toMutableList(),
            diagnostics = diagnostics.toMutableList(),
            startAttempt = 0,
            fixedFirstConfig = null,
            onProcessing = onProcessing,
            throwableToMessage = throwableToMessage,
        )
    }

    suspend fun continueAfterPermission(
        pending: PendingVpnContinuation,
        onProcessing: (String) -> Unit,
        throwableToMessage: (Throwable?) -> String,
    ): Result {
        return runLoop(
            strategy = pending.strategy,
            engineProfile = pending.spoofEngineProfile,
            engineSelectionSource = pending.spoofEngineSelectionSource,
            spoofExecutionUrl = pending.spoofExecutionUrl,
            spoofUrlSanitized = pending.spoofUrlSanitized,
            attemptedConfigs = pending.attemptedConfigs.toMutableList(),
            attemptRows = pending.attemptRows.toMutableList(),
            diagnostics = pending.diagnostics.toMutableList(),
            startAttempt = pending.attemptIndex,
            fixedFirstConfig = pending.waitingConfig,
            onProcessing = onProcessing,
            throwableToMessage = throwableToMessage,
        )
    }

    private suspend fun runLoop(
        strategy: StrategyResult,
        engineProfile: EngineProfile,
        engineSelectionSource: String,
        spoofExecutionUrl: String,
        spoofUrlSanitized: Boolean,
        attemptedConfigs: MutableList<String>,
        attemptRows: MutableList<PriceCheckAttempt>,
        diagnostics: MutableList<String>,
        startAttempt: Int,
        fixedFirstConfig: String?,
        onProcessing: (String) -> Unit,
        throwableToMessage: (Throwable?) -> String,
    ): Result {
        var spoofedResult: ExtractionResult? = null
        var finalConfig: String? = null
        var terminalError: String? = null
        var vpnConnectedThisRun = false
        var activeVpnConfig: String? = null

        for (attempt in startAttempt until SPOOF_ATTEMPT_MAX) {
            val config = if (attempt == startAttempt && fixedFirstConfig != null) {
                fixedFirstConfig
            } else {
                val resolved = resolveSpoofConfig(
                    excludedConfigs = attemptedConfigs.toSet(),
                    strategyConfig = strategy.wireguardConfig,
                )
                if (resolved == null) {
                    terminalError = "No enabled VPN configs available for spoof attempts."
                    diagnostics += terminalError.orEmpty()
                    break
                }
                resolved
            }
            if (config !in attemptedConfigs) attemptedConfigs += config
            val attemptNumber = attempt + 1
            when (val execution = runSpoofAttempt(
                executionUrl = spoofExecutionUrl,
                urlSanitized = spoofUrlSanitized,
                strategy = strategy,
                engineProfile = engineProfile,
                engineSelectionSource = engineSelectionSource,
                config = config,
                attemptNumber = attemptNumber,
                onProcessing = onProcessing,
                throwableToMessage = throwableToMessage,
            )) {
                is SpoofAttemptExecution.PermissionRequired -> {
                    return Result.AwaitingPermission(
                        intent = execution.intent,
                        pending = PendingVpnContinuation(
                            strategy = strategy,
                            attemptIndex = attempt,
                            waitingConfig = config,
                            spoofExecutionUrl = spoofExecutionUrl,
                            spoofUrlSanitized = spoofUrlSanitized,
                            spoofEngineProfile = engineProfile,
                            spoofEngineSelectionSource = engineSelectionSource,
                            attemptedConfigs = attemptedConfigs.toList(),
                            diagnostics = diagnostics.toList(),
                            attemptRows = attemptRows.toList(),
                        ),
                    )
                }

                is SpoofAttemptExecution.Failure -> {
                    vpnConnectedThisRun = vpnConnectedThisRun || execution.connected
                    attemptRows += telemetryAssembler.buildAttemptRow(
                        phase = DefaultPriceCheckCoordinator.PHASE_SPOOF,
                        attemptIndex = attemptNumber,
                        vpnConfig = config,
                        success = false,
                        throwable = execution.throwable,
                        extracted = execution.extracted,
                        latencyMs = execution.latencyMs,
                        executionUrl = spoofExecutionUrl,
                        appliedLevers = execution.appliedLevers,
                    )
                    diagnostics += execution.userMessage
                    if (execution.throwable != null) {
                        Log.e(TAG, "Spoof attempt failed (attempt=$attemptNumber, config=$config)", execution.throwable)
                    }
                }

                is SpoofAttemptExecution.Success -> {
                    vpnConnectedThisRun = true
                    attemptRows += telemetryAssembler.buildAttemptRow(
                        phase = DefaultPriceCheckCoordinator.PHASE_SPOOF,
                        attemptIndex = attemptNumber,
                        vpnConfig = config,
                        success = true,
                        throwable = null,
                        extracted = execution.result,
                        latencyMs = execution.latencyMs,
                        executionUrl = spoofExecutionUrl,
                        appliedLevers = execution.appliedLevers,
                    )
                    spoofedResult = execution.result
                    finalConfig = config
                    activeVpnConfig = config
                    break
                }
            }
        }

        if (spoofedResult == null && terminalError == null) {
            terminalError = if (diagnostics.any { it.contains("Secure tunnel unavailable", ignoreCase = true) }) {
                "Spoofed extraction failed after bounded retry. Secure tunnel unavailable; reconnect VPN and retry."
            } else {
                "Spoofed extraction failed after bounded retry."
            }
        }

        return Result.Completed(
            spoofedResult = spoofedResult,
            finalConfig = finalConfig,
            attemptRows = attemptRows,
            attemptedConfigs = attemptedConfigs,
            diagnostics = diagnostics,
            terminalError = terminalError,
            vpnConnectedThisRun = vpnConnectedThisRun,
            activeVpnConfig = activeVpnConfig,
        )
    }

    private sealed interface SpoofAttemptExecution {
        val latencyMs: Long

        data class Success(
            val result: ExtractionResult,
            val appliedLevers: JsonObject,
            override val latencyMs: Long,
        ) : SpoofAttemptExecution

        data class Failure(
            val throwable: Throwable?,
            val connected: Boolean,
            val userMessage: String,
            val extracted: ExtractionResult?,
            val appliedLevers: JsonObject,
            override val latencyMs: Long,
        ) : SpoofAttemptExecution

        data class PermissionRequired(
            val intent: Intent,
            val appliedLevers: JsonObject,
            override val latencyMs: Long,
        ) : SpoofAttemptExecution
    }

    private suspend fun runSpoofAttempt(
        executionUrl: String,
        urlSanitized: Boolean,
        strategy: StrategyResult,
        engineProfile: EngineProfile,
        engineSelectionSource: String,
        config: String,
        attemptNumber: Int,
        onProcessing: (String) -> Unit,
        throwableToMessage: (Throwable?) -> String,
    ): SpoofAttemptExecution {
        var connected = false
        var extractionResult: kotlin.Result<ExtractionResult>? = null
        var permissionIntent: Intent? = null
        var failureUserMessage: String? = null
        val strictTrackingProtection = engineProfile == EngineProfile.YALE_SMART
        val trackingProtection = trackingProtectionForProfile(engineProfile)
        val navigationUrl = buildEngineBootstrapNavigationUrl(executionUrl, engineProfile)
        val latencyMs = measureTimeMillis {
            onProcessing("Connecting VPN ($attemptNumber/$SPOOF_ATTEMPT_MAX)...")
            val connectResult = vpnEngine.connect(config)
            if (connectResult.isFailure) {
                val throwable = connectResult.exceptionOrNull()
                if (throwable is VpnPermissionRequiredException) {
                    permissionIntent = throwable.intent
                    return@measureTimeMillis
                }
                vpnRotationEngine.reportAttemptResult(config, success = false)
                failureUserMessage = if (isLikelyVpnConnectivityIssue(throwable)) {
                    "Secure tunnel unavailable. Verify device VPN is connected, then retry."
                } else {
                    "Spoof attempt failed: ${throwableToMessage(throwable)}"
                }
                extractionResult = kotlin.Result.failure(throwable ?: IllegalStateException("VPN connect failed"))
                return@measureTimeMillis
            }
            connected = true
            onProcessing("Stabilizing secure tunnel...")
            delay(VPN_STABILIZATION_DELAY_MS)
            onProcessing("Extracting spoofed price ($attemptNumber/$SPOOF_ATTEMPT_MAX)...")
            Log.i(TAG, "Spoof execution URL prepared (sanitized=$urlSanitized): $executionUrl")
            extractionResult = extractionEngine.loadAndExtract(
                navigationUrl,
                request = ExtractionRequest(
                    cleanSessionRequired = true,
                    phase = DefaultPriceCheckCoordinator.PHASE_SPOOF,
                    strictTrackingProtection = strictTrackingProtection,
                ),
            )
        }

        val result = extractionResult
        if (result == null) {
            if (permissionIntent != null) {
                return SpoofAttemptExecution.PermissionRequired(
                    intent = permissionIntent!!,
                    appliedLevers = telemetryAssembler.buildAppliedLevers(
                        urlSanitized = urlSanitized,
                        amnesiaProtocol = false,
                        trackingProtection = trackingProtection,
                        strategy = strategy,
                        engineProfile = engineProfile,
                        engineSelectionSource = engineSelectionSource,
                    ),
                    latencyMs = latencyMs,
                )
            }
            return SpoofAttemptExecution.Failure(
                throwable = IllegalStateException("Spoof attempt interrupted."),
                connected = connected,
                userMessage = "Spoof attempt interrupted.",
                extracted = null,
                appliedLevers = telemetryAssembler.buildAppliedLevers(
                    urlSanitized = urlSanitized,
                    amnesiaProtocol = false,
                    trackingProtection = trackingProtection,
                    strategy = strategy,
                    engineProfile = engineProfile,
                    engineSelectionSource = engineSelectionSource,
                ),
                latencyMs = latencyMs,
            )
        }

        if (result.isSuccess) {
            val extracted = result.getOrThrow()
            if (extracted.isWafBlockDetected()) {
                val spoofWafThrowable = IllegalStateException("waf_block detected during Spoof Pass")
                vpnRotationEngine.reportAttemptResult(config, success = false)
                return SpoofAttemptExecution.Failure(
                    throwable = spoofWafThrowable,
                    connected = connected,
                    userMessage = "Spoof attempt blocked by WAF. Rotating to the next config.",
                    extracted = extracted,
                    appliedLevers = telemetryAssembler.buildAppliedLevers(
                        urlSanitized = urlSanitized,
                        amnesiaProtocol = true,
                        trackingProtection = trackingProtection,
                        strategy = strategy,
                        engineProfile = engineProfile,
                        engineSelectionSource = engineSelectionSource,
                    ),
                    latencyMs = latencyMs,
                )
            }
            vpnRotationEngine.reportAttemptResult(config, success = true)
            return SpoofAttemptExecution.Success(
                result = extracted,
                appliedLevers = telemetryAssembler.buildAppliedLevers(
                    urlSanitized = urlSanitized,
                    amnesiaProtocol = true,
                    trackingProtection = trackingProtection,
                    strategy = strategy,
                    engineProfile = engineProfile,
                    engineSelectionSource = engineSelectionSource,
                ),
                latencyMs = latencyMs,
            )
        }

        val throwable = result.exceptionOrNull()
        vpnRotationEngine.reportAttemptResult(config, success = false)
        val resolvedUserMessage = when {
            failureUserMessage != null -> failureUserMessage.orEmpty()
            isCleanSessionPreparationFailure(throwable) ->
                "Spoof attempt blocked: unable to prepare a clean session. Reconnect VPN and retry."
            else -> "Spoof attempt failed: ${throwableToMessage(throwable)}"
        }
        return SpoofAttemptExecution.Failure(
            throwable = throwable,
            connected = connected,
            userMessage = resolvedUserMessage,
            extracted = null,
            appliedLevers = telemetryAssembler.buildAppliedLevers(
                urlSanitized = urlSanitized,
                amnesiaProtocol = !isCleanSessionPreparationFailure(throwable),
                trackingProtection = trackingProtection,
                strategy = strategy,
                engineProfile = engineProfile,
                engineSelectionSource = engineSelectionSource,
            ),
            latencyMs = latencyMs,
        )
    }

    suspend fun disconnectVpn(): kotlin.Result<Unit> = vpnEngine.disconnect()

    private fun resolveSpoofConfig(excludedConfigs: Set<String>, strategyConfig: String?): String? {
        val fromRotation = vpnRotationEngine.nextConfig(excludedConfigs)
        if (fromRotation != null) return fromRotation
        return strategyConfig?.takeIf { it.isNotBlank() }
    }

    private fun trackingProtectionForProfile(profile: EngineProfile): String {
        return if (profile == EngineProfile.YALE_SMART) TRACKING_PROTECTION_STRICT else TRACKING_PROTECTION_OFF
    }

    private fun isLikelyVpnConnectivityIssue(throwable: Throwable?): Boolean {
        val message = throwable?.message?.lowercase(Locale.US).orEmpty()
        if (message.contains("internet route is not ready")) return true
        if (message.contains("network is unreachable")) return true
        if (message.contains("timed out")) return true
        if (message.contains("uapi")) return true
        if (message.contains("backend")) return true
        return false
    }

    private fun isCleanSessionPreparationFailure(throwable: Throwable?): Boolean {
        return throwable is CleanSessionPreparationException || throwable?.cause is CleanSessionPreparationException
    }

    private fun ExtractionResult.isWafBlockDetected(): Boolean {
        if (debugExtractionPath.equals("waf_block", ignoreCase = true)) return true
        return tactics.any { it.startsWith("block_", ignoreCase = true) }
    }

    private fun buildEngineBootstrapNavigationUrl(executionUrl: String, profile: EngineProfile): String {
        return appendEngineBootstrapToken(executionUrl, profile.toTelemetryValue())
    }

    private fun appendEngineBootstrapToken(executionUrl: String, tokenValue: String): String {
        val hashIndex = executionUrl.indexOf('#')
        if (hashIndex < 0) {
            return "$executionUrl#$ENGINE_HASH_KEY=$tokenValue"
        }
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

    private fun EngineProfile.toTelemetryValue(): String {
        return when (this) {
            EngineProfile.LEGACY -> CONTROL_PROFILE_TOKEN
            EngineProfile.YALE_SMART -> YALE_SMART_PROFILE_TOKEN
        }
    }
}
