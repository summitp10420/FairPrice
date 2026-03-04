package com.fairprice.app.coordinator

import android.util.Log
import com.fairprice.app.coordinator.model.CoordinatorCommand
import com.fairprice.app.coordinator.model.CoordinatorProcessState
import com.fairprice.app.coordinator.model.CoordinatorState
import com.fairprice.app.coordinator.model.StartPriceCheckParams
import com.fairprice.app.data.FairPriceRepository
import com.fairprice.app.engine.EngineProfile
import com.fairprice.app.engine.ExtractionResult
import com.fairprice.app.engine.PricingStrategyEngine
import com.fairprice.app.engine.StrategyResult
import com.fairprice.app.engine.VpnEngine
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DefaultPriceCheckCoordinator(
    private val scope: CoroutineScope,
    private val repository: FairPriceRepository,
    private val strategyEngine: PricingStrategyEngine,
    private val vpnEngine: VpnEngine,
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

    private val _commands = MutableSharedFlow<CoordinatorCommand>(extraBufferCapacity = 1)
    override val commands: SharedFlow<CoordinatorCommand> = _commands.asSharedFlow()

    private var shoppingVpnActive: Boolean = false
    private var activeVpnConfig: String? = null
    private var pendingContinuation: PendingContinuationContext? = null

    override fun startPriceCheck(params: StartPriceCheckParams) {
        val rawSubmittedUrl = params.rawSubmittedUrl.trim()
        _state.update { it.copy(processState = CoordinatorProcessState.Idle, showBrowser = false) }
        if (rawSubmittedUrl.isBlank()) return

        scope.launch {
            var terminalError: String? = null
            var successSummary: com.fairprice.app.viewmodel.SummaryData? = null
            var vpnConnectedThisRun = false
            var keepVpnForShopping = false
            var finalShowBrowser = false
            var awaitingVpnPermission = false

            try {
                pendingContinuation = null
                if (shoppingVpnActive) {
                    val disconnectResult = spoofAttemptRunner.disconnectVpn()
                    if (disconnectResult.isFailure) {
                        val throwable = disconnectResult.exceptionOrNull()
                        terminalError = "VPN disconnect failed before new check: ${throwable.toUserMessage()}"
                        Log.e(TAG, "VPN disconnect failed before restart", throwable)
                        return@launch
                    }
                    shoppingVpnActive = false
                }

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
                            strategyEngine.determineStrategy(
                                url = submittedUrl,
                                baselineTactics = preSpoof.tacticSourceExtraction.tactics,
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
                                )
                                val failedLogResult =
                                    repository.logPriceCheckRun(failedPriceCheck, preSpoof.attemptRows)
                                if (failedLogResult.isSuccess) {
                                    diagnostics += telemetryAssembler.logResultDiagnostics(failedLogResult.getOrThrow())
                                }
                                return@launch
                            }

                        val profileResolution = resolveActiveEngineProfile(
                            strategyProfile = strategy.engineProfile,
                            forceLegacy = params.adminOverrideForceLegacy,
                            forceYaleSmart = params.adminOverrideForceYaleSmart,
                            isAdmin = params.isAdmin,
                        )
                        val spoofUrlPlan = when (profileResolution.profile) {
                            EngineProfile.LEGACY -> SanitizedUrl(url = submittedUrl, wasSanitized = false)
                            EngineProfile.YALE_SMART -> sanitizeUrlForSpoof(submittedUrl)
                        }

                        when (
                            val spoofResult = spoofAttemptRunner.execute(
                                strategy = strategy,
                                engineProfile = profileResolution.profile,
                                engineSelectionSource = profileResolution.selectionSource,
                                spoofExecutionUrl = spoofUrlPlan.url,
                                spoofUrlSanitized = spoofUrlPlan.wasSanitized,
                                attemptedConfigs = emptyList(),
                                attemptRows = preSpoof.attemptRows,
                                diagnostics = preSpoof.diagnostics,
                                onProcessing = ::emitProcessing,
                                throwableToMessage = { it.toUserMessage() },
                            )
                        ) {
                            is SpoofAttemptRunner.Result.AwaitingPermission -> {
                                pendingContinuation = PendingContinuationContext(
                                    submittedUrl = submittedUrl,
                                    preSpoof = preSpoof,
                                    strategy = strategy,
                                    dirtyBaselinePriceCents = params.dirtyBaselinePriceCents,
                                    pending = spoofResult.pending,
                                )
                                emitProcessing("Waiting for VPN permission...")
                                _commands.tryEmit(
                                    CoordinatorCommand.RequestVpnPermission(spoofResult.intent),
                                )
                                awaitingVpnPermission = true
                                return@launch
                            }

                            is SpoofAttemptRunner.Result.Completed -> {
                                vpnConnectedThisRun = spoofResult.vpnConnectedThisRun
                                activeVpnConfig = spoofResult.activeVpnConfig
                                val completion = completeRunAfterSpoof(
                                    submittedUrl = submittedUrl,
                                    preSpoof = preSpoof,
                                    strategy = strategy,
                                    dirtyBaselinePriceCents = params.dirtyBaselinePriceCents,
                                    spoofResult = spoofResult,
                                )
                                terminalError = completion.terminalError
                                successSummary = completion.successSummary
                                keepVpnForShopping = completion.keepVpnForShopping
                                finalShowBrowser = completion.finalShowBrowser
                            }
                        }
                    }
                }
            } finally {
                if (!awaitingVpnPermission) {
                    if (vpnConnectedThisRun && !keepVpnForShopping) {
                        val disconnectResult = spoofAttemptRunner.disconnectVpn()
                        if (disconnectResult.isFailure) {
                            val throwable = disconnectResult.exceptionOrNull()
                            val disconnectMessage = "VPN disconnect failed: ${throwable.toUserMessage()}"
                            Log.e(TAG, "VPN disconnect failed", throwable)
                            terminalError = terminalError?.let { "$it | $disconnectMessage" } ?: disconnectMessage
                        }
                        shoppingVpnActive = false
                    }
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
    }

    override fun onVpnPermissionResult(granted: Boolean) {
        val pending = pendingContinuation ?: return
        pendingContinuation = null

        if (!granted) {
            val diagnostics = pending.pending.diagnostics.toMutableList().apply {
                add("VPN permission denied. Continuing without VPN optimization.")
            }
            val attemptRows = pending.pending.attemptRows.toMutableList().apply {
                add(
                    telemetryAssembler.buildAttemptRow(
                        phase = PHASE_SPOOF,
                        attemptIndex = pending.pending.attemptIndex + 1,
                        vpnConfig = pending.pending.waitingConfig,
                        success = false,
                        throwable = IllegalStateException("vpn_permission_denied"),
                        extracted = null,
                        latencyMs = 0L,
                        executionUrl = pending.pending.spoofExecutionUrl,
                        appliedLevers = telemetryAssembler.buildAppliedLevers(
                            urlSanitized = pending.pending.spoofUrlSanitized,
                            amnesiaProtocol = false,
                            trackingProtection = if (pending.pending.spoofEngineProfile == EngineProfile.YALE_SMART) "strict" else "off",
                            strategy = pending.strategy,
                            engineProfile = pending.pending.spoofEngineProfile,
                            engineSelectionSource = pending.pending.spoofEngineSelectionSource,
                        ),
                    ),
                )
            }
            scope.launch {
                val deniedPriceCheck = telemetryAssembler.buildPriceCheck(
                    url = pending.submittedUrl,
                    strategyId = pending.strategy.strategyId,
                    strategyName = pending.strategy.strategyName,
                    baselinePriceCents = pending.preSpoof.tacticSourceExtraction.priceCents,
                    foundPriceCents = pending.preSpoof.tacticSourceExtraction.priceCents,
                    extractionSuccessful = false,
                    tactics = pending.preSpoof.tacticSourceExtraction.tactics,
                    attemptedConfigs = pending.pending.attemptedConfigs,
                    finalConfig = null,
                    retryCount = telemetryAssembler.retryCountFromAttempts(attemptRows),
                    outcome = "vpn_permission_denied",
                    degraded = true,
                    baselineSuccess = true,
                    spoofSuccess = false,
                    dirtyBaselinePriceCents = pending.dirtyBaselinePriceCents,
                    diagnostics = diagnostics,
                    snifferPriceCents = pending.preSpoof.snifferExtraction?.priceCents,
                    cleanControlPriceCents = pending.preSpoof.cleanControlExtraction?.priceCents,
                    tacticSourcePass = pending.preSpoof.tacticSourcePass,
                    cleanControlExecutionMode = pending.preSpoof.cleanControlExecutionMode,
                    shadowSampled = pending.preSpoof.shadowSampled,
                )
                val deniedLogResult = repository.logPriceCheckRun(deniedPriceCheck, attemptRows)
                if (deniedLogResult.isSuccess) {
                    diagnostics += telemetryAssembler.logResultDiagnostics(deniedLogResult.getOrThrow())
                }
                _state.update {
                    it.copy(
                        showBrowser = true,
                        processState = CoordinatorProcessState.Error(
                            "VPN permission denied. Continuing without VPN optimization.",
                        ),
                    )
                }
            }
            return
        }

        scope.launch {
            var terminalError: String? = null
            var successSummary: com.fairprice.app.viewmodel.SummaryData? = null
            var vpnConnectedThisRun = false
            var keepVpnForShopping = false
            try {
                when (
                    val continuationResult = spoofAttemptRunner.continueAfterPermission(
                        pending = pending.pending,
                        onProcessing = ::emitProcessing,
                        throwableToMessage = { it.toUserMessage() },
                    )
                ) {
                    is SpoofAttemptRunner.Result.AwaitingPermission -> {
                        pendingContinuation = pending.copy(pending = continuationResult.pending)
                        emitProcessing("Waiting for VPN permission...")
                        _commands.tryEmit(CoordinatorCommand.RequestVpnPermission(continuationResult.intent))
                        return@launch
                    }

                    is SpoofAttemptRunner.Result.Completed -> {
                        vpnConnectedThisRun = continuationResult.vpnConnectedThisRun
                        activeVpnConfig = continuationResult.activeVpnConfig
                        val completion = completeRunAfterSpoof(
                            submittedUrl = pending.submittedUrl,
                            preSpoof = pending.preSpoof,
                            strategy = pending.strategy,
                            dirtyBaselinePriceCents = pending.dirtyBaselinePriceCents,
                            spoofResult = continuationResult,
                        )
                        terminalError = completion.terminalError
                        successSummary = completion.successSummary
                        keepVpnForShopping = completion.keepVpnForShopping
                    }
                }
            } finally {
                if (vpnConnectedThisRun && !keepVpnForShopping) {
                    val disconnectResult = spoofAttemptRunner.disconnectVpn()
                    if (disconnectResult.isFailure) {
                        val throwable = disconnectResult.exceptionOrNull()
                        val disconnectMessage = "VPN disconnect failed: ${throwable.toUserMessage()}"
                        terminalError = terminalError?.let { "$it | $disconnectMessage" } ?: disconnectMessage
                    }
                    shoppingVpnActive = false
                }

                _state.update {
                    it.copy(
                        showBrowser = false,
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
        scope.launch {
            var terminalError: String? = null
            val baselineError = ensureBaselineVpnActive()
            if (baselineError != null) {
                terminalError = baselineError
            } else {
                shoppingVpnActive = false
            }
            _state.update {
                it.copy(
                    showBrowser = false,
                    processState = terminalError?.let(CoordinatorProcessState::Error) ?: CoordinatorProcessState.Idle,
                )
            }
        }
    }

    override fun onAppClosing() {
        scope.launch {
            val baselineError = ensureBaselineVpnActive()
            if (baselineError != null) {
                Log.e(TAG, baselineError)
            } else {
                shoppingVpnActive = false
            }
        }
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
                keepVpnForShopping = false,
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
        )

        emitProcessing("Logging to database...")
        val logResult = repository.logPriceCheckRun(priceCheck, attemptRows)
        if (logResult.isFailure) {
            val throwable = logResult.exceptionOrNull()
            return CompletionResult(
                terminalError = "Supabase log failed: ${throwable.toUserMessage()}",
                successSummary = null,
                keepVpnForShopping = false,
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
            baselineConfigId = telemetryAssembler.resolveBaselineConfigId(),
        )
        shoppingVpnActive = true
        return CompletionResult(
            terminalError = null,
            successSummary = successSummary,
            keepVpnForShopping = true,
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

    private fun resolveActiveEngineProfile(
        strategyProfile: EngineProfile,
        forceLegacy: Boolean,
        forceYaleSmart: Boolean,
        isAdmin: Boolean,
    ): ActiveEngineProfile {
        if (!isAdmin || (!forceLegacy && !forceYaleSmart)) {
            return ActiveEngineProfile(profile = strategyProfile, selectionSource = "strategy")
        }
        return when {
            forceLegacy -> ActiveEngineProfile(EngineProfile.LEGACY, "admin_override")
            forceYaleSmart -> ActiveEngineProfile(EngineProfile.YALE_SMART, "admin_override")
            else -> ActiveEngineProfile(strategyProfile, "strategy")
        }
    }

    private suspend fun ensureBaselineVpnActive(): String? {
        val baselineId = telemetryAssembler.resolveBaselineConfigId()
            ?: return "No baseline VPN config selected. Set a baseline in Manage VPN."
        val connectResult = vpnEngine.connect(baselineId)
        if (connectResult.isFailure) {
            val throwable = connectResult.exceptionOrNull()
            Log.e(TAG, "Failed to revert VPN to baseline config", throwable)
            return "Failed to revert VPN to baseline: ${throwable.toUserMessage()}"
        }
        activeVpnConfig = baselineId
        Log.i(TAG, "Reverted VPN to baseline config: $baselineId")
        return null
    }

    private fun emitProcessing(message: String) {
        _state.update { it.copy(processState = CoordinatorProcessState.Processing(message)) }
    }

    private fun Throwable?.toUserMessage(): String {
        val throwable = this ?: return "Unknown error"
        return throwable.message ?: throwable::class.java.simpleName
    }

    private data class PendingContinuationContext(
        val submittedUrl: String,
        val preSpoof: PreSpoofStageRunner.Result.Success,
        val strategy: StrategyResult,
        val dirtyBaselinePriceCents: Int?,
        val pending: SpoofAttemptRunner.PendingVpnContinuation,
    )

    private data class ActiveEngineProfile(
        val profile: EngineProfile,
        val selectionSource: String,
    )

    private data class SanitizedUrl(
        val url: String,
        val wasSanitized: Boolean,
    )

    private data class CompletionResult(
        val terminalError: String?,
        val successSummary: com.fairprice.app.viewmodel.SummaryData?,
        val keepVpnForShopping: Boolean,
        val finalShowBrowser: Boolean,
    )
}
