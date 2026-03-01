package com.fairprice.app.viewmodel

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairprice.app.data.FairPriceRepository
import com.fairprice.app.data.models.PriceCheck
import com.fairprice.app.data.models.PriceCheckAttempt
import com.fairprice.app.engine.ExtractionEngine
import com.fairprice.app.engine.ExtractionResult
import com.fairprice.app.engine.PricingStrategyEngine
import com.fairprice.app.engine.StrategyResult
import com.fairprice.app.engine.VpnEngine
import com.fairprice.app.engine.VpnConfigRecord
import com.fairprice.app.engine.VpnConfigStore
import com.fairprice.app.engine.VpnRotationEngine
import com.fairprice.app.engine.VpnPermissionRequiredException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mozilla.geckoview.GeckoSession
import kotlin.system.measureTimeMillis

sealed interface HomeProcessState {
    data object Idle : HomeProcessState
    data class Processing(val message: String) : HomeProcessState
    data class Success(val summary: SummaryData) : HomeProcessState
    data class Error(val message: String) : HomeProcessState
}

data class SummaryData(
    val baselinePrice: String,
    val spoofedPrice: String,
    val dirtyBaselinePrice: String?,
    val potentialSavings: String?,
    val isVictory: Boolean,
    val tactics: List<String>,
    val strategyName: String,
    val vpnConfig: String,
    val attemptedConfigs: List<String>,
    val finalConfig: String,
    val retryCount: Int,
    val outcome: String,
    val diagnostics: List<String>,
)

data class HomeUiState(
    val urlInput: String = "",
    val dirtyBaselineInputRaw: String = "",
    val lastSubmittedUrl: String? = null,
    val processState: HomeProcessState = HomeProcessState.Idle,
    val activeSession: GeckoSession? = null,
    val showBrowser: Boolean = false,
    val userVpnConfigs: List<VpnConfigRecord> = emptyList(),
    val baselineConfigId: String? = null,
)

class HomeViewModel(
    private val repository: FairPriceRepository,
    private val vpnEngine: VpnEngine,
    private val vpnConfigStore: VpnConfigStore = object : VpnConfigStore {
        override fun listUserConfigs() = emptyList<com.fairprice.app.engine.VpnConfigRecord>()
        override fun listEnabledUserConfigs() = emptyList<com.fairprice.app.engine.VpnConfigRecord>()
        override fun readUserConfigText(configId: String): Result<String> {
            return Result.failure(IllegalStateException("User VPN config store unavailable."))
        }
        override fun importUserConfig(displayName: String, rawConfigText: String) =
            Result.failure<com.fairprice.app.engine.VpnConfigRecord>(
                IllegalStateException("User VPN config store unavailable."),
            )
        override fun setUserConfigEnabled(configId: String, enabled: Boolean): Result<Unit> =
            Result.success(Unit)
        override fun getBaselineConfigId(): String? = null
        override fun setBaselineConfigId(configId: String): Result<Unit> = Result.success(Unit)
    },
    private val vpnRotationEngine: VpnRotationEngine = object : VpnRotationEngine {
        override fun availableConfigs(): List<String> = emptyList()

        override fun nextConfig(excludedConfigs: Set<String>): String? = null

        override fun reportAttemptResult(config: String, success: Boolean) = Unit
    },
    private val extractionEngine: ExtractionEngine,
    private val strategyEngine: PricingStrategyEngine,
    private val shortUrlResolver: suspend (String) -> String? = { inputUrl ->
        resolveAmazonShortUrlBestEffort(inputUrl)
    },
) : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
        private const val VPN_STABILIZATION_DELAY_MS = 2_000L
        private const val SPOOF_ATTEMPT_MAX = 2
        private const val BASELINE_VPN_CONFIG = "baseline_saltlake_ut-US-UT-137.conf"
        private const val DEFAULT_STRATEGY_NAME = "Default Strategy (stub)"
        private const val URL_RESOLVE_CONNECT_TIMEOUT_MS = 5_000
        private const val URL_RESOLVE_READ_TIMEOUT_MS = 5_000

        private suspend fun resolveAmazonShortUrlBestEffort(inputUrl: String): String? {
            return withContext(Dispatchers.IO) {
                resolveWithMethod(inputUrl, "HEAD") ?: resolveWithMethod(inputUrl, "GET")
            }
        }

        private fun resolveWithMethod(inputUrl: String, method: String): String? {
            return runCatching {
                val connection = (URL(inputUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    instanceFollowRedirects = true
                    connectTimeout = URL_RESOLVE_CONNECT_TIMEOUT_MS
                    readTimeout = URL_RESOLVE_READ_TIMEOUT_MS
                    useCaches = false
                }
                try {
                    connection.connect()
                    connection.url.toString()
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private val _vpnPermissionRequests = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val vpnPermissionRequests: SharedFlow<Intent> = _vpnPermissionRequests.asSharedFlow()
    private var shoppingVpnActive: Boolean = false
    private var pendingVpnContinuation: PendingVpnContinuation? = null
    private var activeVpnConfig: String? = null

    init {
        viewModelScope.launch {
            extractionEngine.currentSession.collect { session ->
                _uiState.update { current ->
                    current.copy(activeSession = session)
                }
            }
        }
        refreshVpnConfigs()
    }

    fun onUrlInputChanged(value: String) {
        _uiState.update { current ->
            current.copy(urlInput = value)
        }
    }

    fun onDirtyBaselineInputChanged(value: String) {
        val sanitized = sanitizeDigitsOnly(value)
        _uiState.update { current ->
            current.copy(dirtyBaselineInputRaw = sanitized)
        }
    }

    fun onSharedTextReceived(sharedText: String?) {
        val extractedUrl = extractFirstUrl(sharedText).orEmpty()
        if (extractedUrl.isNotBlank()) {
            _uiState.update { current ->
                current.copy(urlInput = extractedUrl)
            }
        }
    }

    fun onVpnConfigImportReceived(fileName: String, rawConfigText: String) {
        val result = vpnConfigStore.importUserConfig(fileName, rawConfigText)
        if (result.isFailure) {
            val message = "VPN config import failed: ${result.exceptionOrNull().toUserMessage()}"
            _uiState.update { current ->
                current.copy(processState = HomeProcessState.Error(message))
            }
            return
        }

        val imported = result.getOrThrow()
        _uiState.update { current ->
            current.copy(
                processState = HomeProcessState.Processing(
                    "Imported VPN config: ${imported.displayName}",
                ),
            )
        }
        refreshVpnConfigs()
    }

    fun onSetBaselineConfigClicked(configId: String) {
        val result = vpnConfigStore.setBaselineConfigId(configId)
        if (result.isFailure) {
            val message = "Failed to set baseline config: ${result.exceptionOrNull().toUserMessage()}"
            _uiState.update { current -> current.copy(processState = HomeProcessState.Error(message)) }
            return
        }
        refreshVpnConfigs()
    }

    fun onToggleUserConfigEnabled(configId: String, enabled: Boolean) {
        val result = vpnConfigStore.setUserConfigEnabled(configId, enabled)
        if (result.isFailure) {
            val message = "Failed to update VPN config: ${result.exceptionOrNull().toUserMessage()}"
            _uiState.update { current -> current.copy(processState = HomeProcessState.Error(message)) }
            return
        }
        refreshVpnConfigs()
    }

    fun onCheckPriceClicked() {
        val rawSubmittedUrl = _uiState.value.urlInput.trim()
        val dirtyBaselinePriceCents = parseDirtyBaselineCents(_uiState.value.dirtyBaselineInputRaw)
        _uiState.update { current ->
            current.copy(
                lastSubmittedUrl = rawSubmittedUrl,
                processState = HomeProcessState.Idle,
                showBrowser = false,
            )
        }

        if (rawSubmittedUrl.isBlank()) return

        viewModelScope.launch {
            var terminalError: String? = null
            var successSummary: SummaryData? = null
            var vpnConnectedThisRun = false
            var keepVpnForShopping = false
            var finalShowBrowser = false
            var awaitingVpnPermission = false
            val submittedUrl = canonicalizeUrlIfNeeded(rawSubmittedUrl)
            val attemptRows = mutableListOf<PriceCheckAttempt>()
            val attemptedConfigs = mutableListOf<String>()
            val diagnostics = mutableListOf<String>()

            try {
                pendingVpnContinuation = null
                if (shoppingVpnActive) {
                    val disconnectResult = vpnEngine.disconnect()
                    if (disconnectResult.isFailure) {
                        val throwable = disconnectResult.exceptionOrNull()
                        terminalError =
                            "VPN disconnect failed before new check: ${throwable.toUserMessage()}"
                        Log.e("HomeViewModel", "VPN disconnect failed before restart", throwable)
                        return@launch
                    }
                    shoppingVpnActive = false
                }

                _uiState.update { current ->
                    current.copy(
                        processState = HomeProcessState.Processing("Gathering baseline price..."),
                    )
                }
                var baselineResultValue: Result<ExtractionResult> =
                    Result.failure(IllegalStateException("Baseline extraction did not run."))
                val baselineLatencyMs = measureTimeMillis {
                    baselineResultValue = extractionEngine.loadAndExtract(submittedUrl)
                }
                val baselineResult = baselineResultValue
                if (baselineResult.isFailure) {
                    val throwable = baselineResult.exceptionOrNull()
                    attemptRows += buildAttemptRow(
                        phase = "baseline",
                        attemptIndex = 0,
                        vpnConfig = null,
                        success = false,
                        throwable = throwable,
                        extracted = null,
                        latencyMs = baselineLatencyMs,
                    )
                    terminalError =
                        "Baseline extraction failed: ${throwable.toUserMessage()}. You can continue shopping normally."
                    diagnostics += terminalError.orEmpty()
                    Log.e(TAG, "Baseline extraction failed", throwable)
                    _uiState.update { current ->
                        current.copy(processState = HomeProcessState.Processing("Logging fallback result..."))
                    }

                    val fallbackPriceCheck = buildPriceCheck(
                        url = submittedUrl,
                        strategyId = null,
                        strategyName = null,
                        baselinePriceCents = 0,
                        foundPriceCents = 0,
                        extractionSuccessful = false,
                        tactics = emptyList(),
                        attemptedConfigs = emptyList(),
                        finalConfig = null,
                        retryCount = 0,
                        outcome = "degraded_baseline_failed",
                        degraded = true,
                        baselineSuccess = false,
                        spoofSuccess = false,
                        dirtyBaselinePriceCents = dirtyBaselinePriceCents,
                        diagnostics = diagnostics,
                    )
                    val fallbackLogResult = repository.logPriceCheckRun(fallbackPriceCheck, attemptRows)
                    if (fallbackLogResult.isFailure) {
                        val logThrowable = fallbackLogResult.exceptionOrNull()
                        val logMessage =
                            "Supabase fallback log failed: ${logThrowable.toUserMessage()}"
                        Log.e(TAG, "price_checks fallback insert failed", logThrowable)
                        terminalError = "$terminalError | $logMessage"
                    }
                    finalShowBrowser = true
                    return@launch
                }

                val baselineExtraction = baselineResult.getOrThrow()
                attemptRows += buildAttemptRow(
                    phase = "baseline",
                    attemptIndex = 0,
                    vpnConfig = null,
                    success = true,
                    throwable = null,
                    extracted = baselineExtraction,
                    latencyMs = baselineLatencyMs,
                )

                _uiState.update { current ->
                    current.copy(processState = HomeProcessState.Processing("Determining strategy..."))
                }
                val strategy =
                    strategyEngine.determineStrategy(
                        url = submittedUrl,
                        baselineTactics = baselineExtraction.tactics,
                    ).getOrElse { throwable ->
                        terminalError = "Strategy resolution failed: ${throwable.toUserMessage()}"
                        diagnostics += terminalError.orEmpty()
                        Log.e(TAG, "Strategy resolution failed", throwable)
                        _uiState.update { current ->
                            current.copy(processState = HomeProcessState.Processing("Logging run result..."))
                        }
                        val failedPriceCheck = buildPriceCheck(
                            url = submittedUrl,
                            strategyId = null,
                            strategyName = null,
                            baselinePriceCents = baselineExtraction.priceCents,
                            foundPriceCents = baselineExtraction.priceCents,
                            extractionSuccessful = false,
                            tactics = baselineExtraction.tactics,
                            attemptedConfigs = emptyList(),
                            finalConfig = null,
                            retryCount = 0,
                            outcome = "strategy_failed",
                            degraded = false,
                            baselineSuccess = true,
                            spoofSuccess = false,
                            dirtyBaselinePriceCents = dirtyBaselinePriceCents,
                            diagnostics = diagnostics,
                        )
                        repository.logPriceCheckRun(failedPriceCheck, attemptRows)
                        return@launch
                    }
                var spoofedResult: ExtractionResult? = null
                var finalConfig: String? = null
                for (attempt in 0 until SPOOF_ATTEMPT_MAX) {
                    val config = vpnRotationEngine.nextConfig(attemptedConfigs.toSet())
                        ?: strategy.wireguardConfig
                    if (config == null) {
                        terminalError = "No healthy VPN configs available for spoof attempts."
                        diagnostics += terminalError.orEmpty()
                        break
                    }
                    if (config !in attemptedConfigs) {
                        attemptedConfigs += config
                    }
                    val attemptNumber = attempt + 1
                    val execution = runSpoofAttempt(
                        submittedUrl = submittedUrl,
                        config = config,
                        attemptNumber = attemptNumber,
                    )
                    when (execution) {
                        is SpoofAttemptExecution.PermissionRequired -> {
                            pendingVpnContinuation = PendingVpnContinuation(
                                submittedUrl = submittedUrl,
                                baselineResult = baselineExtraction,
                                strategy = strategy,
                                dirtyBaselinePriceCents = dirtyBaselinePriceCents,
                                attemptIndex = attempt,
                                waitingConfig = config,
                                attemptedConfigs = attemptedConfigs.toList(),
                                diagnostics = diagnostics.toList(),
                                attemptRows = attemptRows.toList(),
                            )
                            _uiState.update { current ->
                                current.copy(
                                    processState = HomeProcessState.Processing("Waiting for VPN permission..."),
                                )
                            }
                            _vpnPermissionRequests.tryEmit(execution.intent)
                            awaitingVpnPermission = true
                            return@launch
                        }

                        is SpoofAttemptExecution.Failure -> {
                            vpnConnectedThisRun = vpnConnectedThisRun || execution.connected
                            attemptRows += buildAttemptRow(
                                phase = "spoof",
                                attemptIndex = attemptNumber,
                                vpnConfig = config,
                                success = false,
                                throwable = execution.throwable,
                                extracted = null,
                                latencyMs = execution.latencyMs,
                            )
                            diagnostics += execution.userMessage
                            if (execution.throwable != null) {
                                Log.e(TAG, "Spoof attempt failed (attempt=$attemptNumber, config=$config)", execution.throwable)
                            }
                        }

                        is SpoofAttemptExecution.Success -> {
                            vpnConnectedThisRun = true
                            attemptRows += buildAttemptRow(
                                phase = "spoof",
                                attemptIndex = attemptNumber,
                                vpnConfig = config,
                                success = true,
                                throwable = null,
                                extracted = execution.result,
                                latencyMs = execution.latencyMs,
                            )
                            spoofedResult = execution.result
                            finalConfig = config
                            activeVpnConfig = config
                            break
                        }
                    }
                }

                if (spoofedResult == null) {
                    terminalError = terminalError ?: "Spoofed extraction failed after bounded retry."
                    val failedPriceCheck = buildPriceCheck(
                        url = submittedUrl,
                        strategyId = strategy.strategyId,
                        strategyName = DEFAULT_STRATEGY_NAME,
                        baselinePriceCents = baselineExtraction.priceCents,
                        foundPriceCents = baselineExtraction.priceCents,
                        extractionSuccessful = false,
                        tactics = baselineExtraction.tactics,
                        attemptedConfigs = attemptedConfigs,
                        finalConfig = finalConfig,
                        retryCount = retryCountFromAttempts(attemptRows),
                        outcome = "spoof_failed",
                        degraded = true,
                        baselineSuccess = true,
                        spoofSuccess = false,
                        dirtyBaselinePriceCents = dirtyBaselinePriceCents,
                        diagnostics = diagnostics,
                    )
                    _uiState.update { current ->
                        current.copy(processState = HomeProcessState.Processing("Logging run result..."))
                    }
                    repository.logPriceCheckRun(failedPriceCheck, attemptRows)
                    return@launch
                }

                val priceCheck = buildPriceCheck(
                    url = submittedUrl,
                    strategyId = strategy.strategyId,
                    strategyName = DEFAULT_STRATEGY_NAME,
                    baselinePriceCents = baselineExtraction.priceCents,
                    foundPriceCents = spoofedResult.priceCents,
                    extractionSuccessful = true,
                    tactics = baselineExtraction.tactics,
                    attemptedConfigs = attemptedConfigs,
                    finalConfig = finalConfig,
                    retryCount = retryCountFromAttempts(attemptRows),
                    outcome = "success",
                    degraded = false,
                    baselineSuccess = true,
                    spoofSuccess = true,
                    dirtyBaselinePriceCents = dirtyBaselinePriceCents,
                    diagnostics = diagnostics,
                )

                _uiState.update { current ->
                    current.copy(processState = HomeProcessState.Processing("Logging to database..."))
                }
                val logResult = repository.logPriceCheckRun(priceCheck, attemptRows)
                if (logResult.isFailure) {
                    val throwable = logResult.exceptionOrNull()
                    terminalError = "Supabase log failed: ${throwable.toUserMessage()}"
                    Log.e("HomeViewModel", "price_checks insert failed", throwable)
                    return@launch
                }

                Log.i("HomeViewModel", "price_checks insert succeeded")
                successSummary = buildSuccessSummary(
                    baselineResult = baselineExtraction,
                    spoofedResult = spoofedResult,
                    dirtyBaselinePriceCents = dirtyBaselinePriceCents,
                    strategy = strategy,
                    attemptedConfigs = attemptedConfigs,
                    finalConfig = finalConfig ?: strategy.wireguardConfig,
                    retryCount = retryCountFromAttempts(attemptRows),
                    outcome = "success",
                    diagnostics = diagnostics,
                )
                keepVpnForShopping = true
                shoppingVpnActive = true
                finalShowBrowser = false
            } finally {
                if (vpnConnectedThisRun && !keepVpnForShopping) {
                    val disconnectResult = vpnEngine.disconnect()
                    if (disconnectResult.isFailure) {
                        val throwable = disconnectResult.exceptionOrNull()
                        val disconnectMessage = "VPN disconnect failed: ${throwable.toUserMessage()}"
                        Log.e("HomeViewModel", "VPN disconnect failed", throwable)
                        terminalError = terminalError?.let { "$it | $disconnectMessage" } ?: disconnectMessage
                    }
                    shoppingVpnActive = false
                }

                if (!awaitingVpnPermission) {
                    _uiState.update { current ->
                        current.copy(
                            showBrowser = finalShowBrowser,
                            processState = terminalError?.let { HomeProcessState.Error(it) }
                                ?: successSummary?.let { HomeProcessState.Success(it) }
                                ?: HomeProcessState.Idle,
                        )
                    }
                }
            }
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        val pending = pendingVpnContinuation ?: return
        pendingVpnContinuation = null

        if (!granted) {
            val diagnostics = pending.diagnostics.toMutableList().apply {
                add("VPN permission denied. Continuing without VPN optimization.")
            }
            val attemptRows = pending.attemptRows.toMutableList().apply {
                add(
                    buildAttemptRow(
                        phase = "spoof",
                        attemptIndex = pending.attemptIndex + 1,
                        vpnConfig = pending.waitingConfig,
                        success = false,
                        throwable = IllegalStateException("vpn_permission_denied"),
                        extracted = null,
                        latencyMs = 0L,
                    ),
                )
            }
            viewModelScope.launch {
                val deniedPriceCheck = buildPriceCheck(
                    url = pending.submittedUrl,
                    strategyId = pending.strategy.strategyId,
                    strategyName = DEFAULT_STRATEGY_NAME,
                    baselinePriceCents = pending.baselineResult.priceCents,
                    foundPriceCents = pending.baselineResult.priceCents,
                    extractionSuccessful = false,
                    tactics = pending.baselineResult.tactics,
                    attemptedConfigs = pending.attemptedConfigs,
                    finalConfig = null,
                    retryCount = retryCountFromAttempts(attemptRows),
                    outcome = "vpn_permission_denied",
                    degraded = true,
                    baselineSuccess = true,
                    spoofSuccess = false,
                    dirtyBaselinePriceCents = pending.dirtyBaselinePriceCents,
                    diagnostics = diagnostics,
                )
                repository.logPriceCheckRun(deniedPriceCheck, attemptRows)
            }
            _uiState.update { current ->
                current.copy(
                    showBrowser = true,
                    processState = HomeProcessState.Error(
                        "VPN permission denied. Continuing without VPN optimization.",
                    ),
                )
            }
            return
        }

        viewModelScope.launch {
            var terminalError: String? = null
            var successSummary: SummaryData? = null
            var vpnConnectedThisRun = false
            var keepVpnForShopping = false
            val attemptRows = pending.attemptRows.toMutableList()
            val attemptedConfigs = pending.attemptedConfigs.toMutableList()
            val diagnostics = pending.diagnostics.toMutableList()

            try {
                var spoofedResult: ExtractionResult? = null
                var finalConfig: String? = null

                for (attempt in pending.attemptIndex until SPOOF_ATTEMPT_MAX) {
                    val attemptNumber = attempt + 1
                    val config = if (attempt == pending.attemptIndex) {
                        pending.waitingConfig
                    } else {
                        val next = vpnRotationEngine.nextConfig(attemptedConfigs.toSet())
                        val resolved = next ?: pending.strategy.wireguardConfig
                        if (resolved != null && resolved !in attemptedConfigs) {
                            attemptedConfigs += resolved
                        }
                        resolved
                    }

                    if (config == null) {
                        terminalError = "No healthy VPN configs available for spoof attempts."
                        diagnostics += terminalError.orEmpty()
                        break
                    }

                    val execution = runSpoofAttempt(
                        submittedUrl = pending.submittedUrl,
                        config = config,
                        attemptNumber = attemptNumber,
                    )
                    when (execution) {
                        is SpoofAttemptExecution.PermissionRequired -> {
                            pendingVpnContinuation = pending.copy(
                                attemptIndex = attempt,
                                waitingConfig = config,
                                attemptedConfigs = attemptedConfigs.toList(),
                                diagnostics = diagnostics.toList(),
                                attemptRows = attemptRows.toList(),
                            )
                            _uiState.update { current ->
                                current.copy(
                                    processState = HomeProcessState.Processing("Waiting for VPN permission..."),
                                )
                            }
                            _vpnPermissionRequests.tryEmit(execution.intent)
                            return@launch
                        }

                        is SpoofAttemptExecution.Failure -> {
                            vpnConnectedThisRun = vpnConnectedThisRun || execution.connected
                            attemptRows += buildAttemptRow(
                                phase = "spoof",
                                attemptIndex = attemptNumber,
                                vpnConfig = config,
                                success = false,
                                throwable = execution.throwable,
                                extracted = null,
                                latencyMs = execution.latencyMs,
                            )
                            diagnostics += execution.userMessage
                        }

                        is SpoofAttemptExecution.Success -> {
                            vpnConnectedThisRun = true
                            attemptRows += buildAttemptRow(
                                phase = "spoof",
                                attemptIndex = attemptNumber,
                                vpnConfig = config,
                                success = true,
                                throwable = null,
                                extracted = execution.result,
                                latencyMs = execution.latencyMs,
                            )
                            spoofedResult = execution.result
                            finalConfig = config
                            activeVpnConfig = config
                            break
                        }
                    }
                }

                if (spoofedResult == null) {
                    terminalError = terminalError ?: "Spoofed extraction failed after bounded retry."
                    val failedPriceCheck = buildPriceCheck(
                        url = pending.submittedUrl,
                        strategyId = pending.strategy.strategyId,
                        strategyName = DEFAULT_STRATEGY_NAME,
                        baselinePriceCents = pending.baselineResult.priceCents,
                        foundPriceCents = pending.baselineResult.priceCents,
                        extractionSuccessful = false,
                        tactics = pending.baselineResult.tactics,
                        attemptedConfigs = attemptedConfigs,
                        finalConfig = finalConfig,
                        retryCount = retryCountFromAttempts(attemptRows),
                        outcome = "spoof_failed",
                        degraded = true,
                        baselineSuccess = true,
                        spoofSuccess = false,
                        dirtyBaselinePriceCents = pending.dirtyBaselinePriceCents,
                        diagnostics = diagnostics,
                    )
                    repository.logPriceCheckRun(failedPriceCheck, attemptRows)
                    return@launch
                }

                val priceCheck = buildPriceCheck(
                    url = pending.submittedUrl,
                    strategyId = pending.strategy.strategyId,
                    strategyName = DEFAULT_STRATEGY_NAME,
                    baselinePriceCents = pending.baselineResult.priceCents,
                    foundPriceCents = spoofedResult.priceCents,
                    extractionSuccessful = true,
                    tactics = pending.baselineResult.tactics,
                    attemptedConfigs = attemptedConfigs,
                    finalConfig = finalConfig,
                    retryCount = retryCountFromAttempts(attemptRows),
                    outcome = "success",
                    degraded = false,
                    baselineSuccess = true,
                    spoofSuccess = true,
                    dirtyBaselinePriceCents = pending.dirtyBaselinePriceCents,
                    diagnostics = diagnostics,
                )

                _uiState.update { current ->
                    current.copy(processState = HomeProcessState.Processing("Logging to database..."))
                }
                val logResult = repository.logPriceCheckRun(priceCheck, attemptRows)
                if (logResult.isFailure) {
                    val throwable = logResult.exceptionOrNull()
                    terminalError = "Supabase log failed: ${throwable.toUserMessage()}"
                    Log.e("HomeViewModel", "price_checks insert failed after permission grant", throwable)
                    return@launch
                }

                successSummary = buildSuccessSummary(
                    baselineResult = pending.baselineResult,
                    spoofedResult = spoofedResult,
                    dirtyBaselinePriceCents = pending.dirtyBaselinePriceCents,
                    strategy = pending.strategy,
                    attemptedConfigs = attemptedConfigs,
                    finalConfig = finalConfig ?: pending.waitingConfig,
                    retryCount = retryCountFromAttempts(attemptRows),
                    outcome = "success",
                    diagnostics = diagnostics,
                )
                keepVpnForShopping = true
                shoppingVpnActive = true
            } finally {
                if (vpnConnectedThisRun && !keepVpnForShopping) {
                    val disconnectResult = vpnEngine.disconnect()
                    if (disconnectResult.isFailure) {
                        val throwable = disconnectResult.exceptionOrNull()
                        val disconnectMessage = "VPN disconnect failed: ${throwable.toUserMessage()}"
                        Log.e("HomeViewModel", "VPN disconnect failed after permission grant", throwable)
                        terminalError = terminalError?.let { "$it | $disconnectMessage" } ?: disconnectMessage
                    }
                    shoppingVpnActive = false
                }

                _uiState.update { current ->
                    current.copy(
                        showBrowser = false,
                        processState = terminalError?.let { HomeProcessState.Error(it) }
                            ?: successSummary?.let { HomeProcessState.Success(it) }
                            ?: HomeProcessState.Idle,
                    )
                }
            }
        }
    }

    fun onEnterShoppingMode() {
        _uiState.update { current ->
            current.copy(showBrowser = true)
        }
    }

    fun onBackToApp() {
        _uiState.update { current ->
            current.copy(showBrowser = false)
        }
    }

    fun onCloseShoppingSession() {
        viewModelScope.launch {
            var terminalError: String? = null
            val baselineError = ensureBaselineVpnActive()
            if (baselineError != null) {
                terminalError = baselineError
            } else {
                shoppingVpnActive = false
            }

            _uiState.update { current ->
                current.copy(
                    urlInput = "",
                    dirtyBaselineInputRaw = "",
                    lastSubmittedUrl = null,
                    showBrowser = false,
                    processState = terminalError?.let { HomeProcessState.Error(it) }
                        ?: HomeProcessState.Idle,
                )
            }
        }
    }

    fun onAppClosing() {
        viewModelScope.launch {
            val baselineError = ensureBaselineVpnActive()
            if (baselineError != null) {
                Log.e(TAG, baselineError)
            } else {
                shoppingVpnActive = false
            }
        }
    }

    private fun extractFirstUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val regex = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
        return regex.find(value)?.value
    }

    private suspend fun canonicalizeUrlIfNeeded(inputUrl: String): String {
        val host = runCatching { URI(inputUrl).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
        val isShortAmazonHost = host == "a.co" || host.endsWith(".a.co")
        if (!isShortAmazonHost) {
            return inputUrl
        }

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

    private suspend fun awaitSpoofStabilizationGate() {
        Log.i(TAG, "Starting VPN stabilization window for spoof extraction")
        _uiState.update { current ->
            current.copy(
                processState = HomeProcessState.Processing("Stabilizing secure tunnel..."),
            )
        }
        delay(VPN_STABILIZATION_DELAY_MS)
        Log.i(TAG, "VPN stabilization window complete")
    }

    private suspend fun runSpoofAttempt(
        submittedUrl: String,
        config: String,
        attemptNumber: Int,
    ): SpoofAttemptExecution {
        var connected = false
        var extractionResult: Result<ExtractionResult>? = null
        var permissionIntent: Intent? = null
        val latencyMs = measureTimeMillis {
            _uiState.update { current ->
                current.copy(
                    processState = HomeProcessState.Processing("Connecting VPN ($attemptNumber/$SPOOF_ATTEMPT_MAX)..."),
                )
            }
            val connectResult = vpnEngine.connect(config)
            if (connectResult.isFailure) {
                val throwable = connectResult.exceptionOrNull()
                if (throwable is VpnPermissionRequiredException) {
                    permissionIntent = throwable.intent
                    return@measureTimeMillis
                }
                vpnRotationEngine.reportAttemptResult(config, success = false)
                extractionResult = Result.failure(throwable ?: IllegalStateException("VPN connect failed"))
                return@measureTimeMillis
            }
            connected = true
            activeVpnConfig = config
            awaitSpoofStabilizationGate()
            _uiState.update { current ->
                current.copy(
                    processState = HomeProcessState.Processing("Extracting spoofed price ($attemptNumber/$SPOOF_ATTEMPT_MAX)..."),
                )
            }
            extractionResult = extractionEngine.loadAndExtract(submittedUrl)
        }

        val result = extractionResult
        if (result == null) {
            if (permissionIntent != null) {
                return SpoofAttemptExecution.PermissionRequired(permissionIntent!!, latencyMs)
            }
            return SpoofAttemptExecution.Failure(
                throwable = IllegalStateException("Spoof attempt interrupted."),
                connected = connected,
                userMessage = "Spoof attempt interrupted.",
                latencyMs = latencyMs,
            )
        }

        if (result.isSuccess) {
            vpnRotationEngine.reportAttemptResult(config, success = true)
            return SpoofAttemptExecution.Success(result.getOrThrow(), latencyMs)
        }

        val throwable = result.exceptionOrNull()
        vpnRotationEngine.reportAttemptResult(config, success = false)
        return SpoofAttemptExecution.Failure(
            throwable = throwable,
            connected = connected,
            userMessage = "Spoof attempt failed: ${throwable.toUserMessage()}",
            latencyMs = latencyMs,
        )
    }

    private fun buildPriceCheck(
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
            retryCount = retryCount,
            outcome = outcome,
            degraded = degraded,
            baselineSuccess = baselineSuccess,
            spoofSuccess = spoofSuccess,
            dirtyBaselinePriceCents = dirtyBaselinePriceCents,
            rawExtractionData = buildJsonObject {
                put(
                    "detected_tactics",
                    JsonArray(tactics.map { JsonPrimitive(it) }),
                )
                put(
                    "diagnostics",
                    JsonArray(diagnostics.map { JsonPrimitive(it) }),
                )
            },
        )
    }

    private fun buildAttemptRow(
        phase: String,
        attemptIndex: Int,
        vpnConfig: String?,
        success: Boolean,
        throwable: Throwable?,
        extracted: ExtractionResult?,
        latencyMs: Long,
    ): PriceCheckAttempt {
        return PriceCheckAttempt(
            phase = phase,
            attemptIndex = attemptIndex,
            vpnConfig = vpnConfig,
            success = success,
            errorType = throwable?.javaClass?.simpleName,
            errorMessage = throwable?.message,
            extractedPriceCents = extracted?.priceCents,
            detectedTactics = extracted?.tactics,
            debugExtractionPath = extracted?.debugExtractionPath,
            latencyMs = latencyMs,
        )
    }

    private fun retryCountFromAttempts(attemptRows: List<PriceCheckAttempt>): Int {
        val spoofAttempts = attemptRows.count { it.phase == "spoof" }
        return (spoofAttempts - 1).coerceAtLeast(0)
    }

    private fun Throwable?.toUserMessage(): String {
        val throwable = this ?: return "Unknown error"
        return throwable.message ?: throwable::class.java.simpleName
    }

    private suspend fun ensureBaselineVpnActive(): String? {
        val baselineId = resolveBaselineConfigId()
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

    private fun resolveBaselineConfigId(): String {
        val baseline = vpnConfigStore.getBaselineConfigId()
        if (!baseline.isNullOrBlank()) return baseline
        return BASELINE_VPN_CONFIG
    }

    private fun refreshVpnConfigs() {
        val configs = vpnConfigStore.listUserConfigs()
        val baseline = vpnConfigStore.getBaselineConfigId()
        _uiState.update { current ->
            current.copy(
                userVpnConfigs = configs,
                baselineConfigId = baseline,
            )
        }
    }

    private fun formatUsd(cents: Int): String {
        return String.format(Locale.US, "$%.2f", cents / 100.0)
    }

    private fun buildSuccessSummary(
        baselineResult: ExtractionResult,
        spoofedResult: ExtractionResult,
        dirtyBaselinePriceCents: Int?,
        strategy: StrategyResult,
        attemptedConfigs: List<String>,
        finalConfig: String,
        retryCount: Int,
        outcome: String,
        diagnostics: List<String>,
    ): SummaryData {
        val baseline = formatUsd(baselineResult.priceCents)
        val spoofed = formatUsd(spoofedResult.priceCents)
        val potentialSavingsCents = dirtyBaselinePriceCents?.minus(spoofedResult.priceCents)
        val isVictory = (potentialSavingsCents ?: 0) > 0
        return SummaryData(
            baselinePrice = baseline,
            spoofedPrice = spoofed,
            dirtyBaselinePrice = dirtyBaselinePriceCents?.let(::formatUsd),
            potentialSavings = potentialSavingsCents?.takeIf { it > 0 }?.let(::formatUsd),
            isVictory = isVictory,
            tactics = baselineResult.tactics,
            strategyName = DEFAULT_STRATEGY_NAME,
            vpnConfig = strategy.wireguardConfig,
            attemptedConfigs = attemptedConfigs,
            finalConfig = finalConfig,
            retryCount = retryCount,
            outcome = outcome,
            diagnostics = diagnostics,
        )
    }

    private sealed interface SpoofAttemptExecution {
        val latencyMs: Long

        data class Success(
            val result: ExtractionResult,
            override val latencyMs: Long,
        ) : SpoofAttemptExecution

        data class Failure(
            val throwable: Throwable?,
            val connected: Boolean,
            val userMessage: String,
            override val latencyMs: Long,
        ) : SpoofAttemptExecution

        data class PermissionRequired(
            val intent: Intent,
            override val latencyMs: Long,
        ) : SpoofAttemptExecution
    }

    private data class PendingVpnContinuation(
        val submittedUrl: String,
        val baselineResult: ExtractionResult,
        val strategy: StrategyResult,
        val dirtyBaselinePriceCents: Int?,
        val attemptIndex: Int,
        val waitingConfig: String,
        val attemptedConfigs: List<String>,
        val diagnostics: List<String>,
        val attemptRows: List<PriceCheckAttempt>,
    )

    private fun sanitizeDigitsOnly(value: String): String = value.filter(Char::isDigit)

    private fun parseDirtyBaselineCents(raw: String): Int? {
        val normalized = sanitizeDigitsOnly(raw)
        if (normalized.isBlank()) return null
        return normalized.toIntOrNull()
    }
}
