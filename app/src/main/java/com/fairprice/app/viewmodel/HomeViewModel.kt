package com.fairprice.app.viewmodel

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairprice.app.data.FairPriceRepository
import com.fairprice.app.data.RunLogResult
import com.fairprice.app.data.models.PriceCheck
import com.fairprice.app.data.models.PriceCheckAttempt
import com.fairprice.app.engine.ExtractionEngine
import com.fairprice.app.engine.ExtractionRequest
import com.fairprice.app.engine.ExtractionResult
import com.fairprice.app.engine.CleanSessionPreparationException
import com.fairprice.app.engine.EngineProfile
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
import java.net.URLDecoder
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
import kotlinx.serialization.json.JsonObject
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

enum class EngineOverride {
    AUTO,
    FORCE_LEGACY,
    FORCE_YALE_SMART,
}

data class SummaryData(
    val lifetimePotentialSavings: String,
    val baselineConfig: String,
    val outcome: String,
    val snifferPrice: String,
    val cleanControlPrice: String?,
    val spoofedPrice: String,
    val dirtyBaselinePrice: String?,
    val potentialSavings: String?,
    val isVictory: Boolean,
    val tactics: List<String>,
    val tacticSourcePass: String,
    val cleanControlExecutionMode: String,
    val shadowSampled: Boolean,
    val strategyName: String,
    val vpnConfig: String,
    val attemptedConfigs: List<String>,
    val finalConfig: String,
    val retryCount: Int,
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
    val isAdmin: Boolean = false,
    val adminEngineOverride: EngineOverride = EngineOverride.AUTO,
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
    private val isAdminUser: Boolean = false,
    private val shortUrlResolver: suspend (String) -> String? = { inputUrl ->
        resolveAmazonShortUrlBestEffort(inputUrl)
    },
    private val shadowCleanControlSampler: (String) -> Boolean = { false },
) : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
        private const val VPN_STABILIZATION_DELAY_MS = 2_000L
        private const val SPOOF_ATTEMPT_MAX = 2
        private const val USER_CONFIG_PREFIX = "user:"
        private const val URL_RESOLVE_CONNECT_TIMEOUT_MS = 5_000
        private const val URL_RESOLVE_READ_TIMEOUT_MS = 5_000
        private val TRACKING_QUERY_PARAM_KEYS = setOf("gclid", "fbclid", "ref")
        private const val TRACKING_PROTECTION_STRICT = "strict"
        private const val TRACKING_PROTECTION_OFF = "off"
        private const val ENGINE_VERSION = "11.5a"
        private const val ENGINE_BUILD_ID = "local-dev"
        private const val ENGINE_HASH_KEY = "fp_engine"
        private const val SHADOW_CLEAN_CONTROL_SAMPLE_PERCENT = 20
        private const val CONTROL_PROFILE_TOKEN = "clean_control_v1"
        private const val YALE_SMART_PROFILE_TOKEN = "yale_smart"
        private const val SNIFFER_INTEL_TOKEN = "sniffer_intel"
        private const val CLEAN_CONTROL_INTEL_TOKEN = "clean_control_intel"
        private const val PHASE_SNIFFER = "sniffer"
        private const val PHASE_CLEAN_CONTROL = "clean_control"
        private const val PHASE_SPOOF = "spoof"
        private const val CLEAN_CONTROL_MODE_NONE = "none"
        private const val CLEAN_CONTROL_MODE_FALLBACK = "fallback"
        private const val CLEAN_CONTROL_MODE_SHADOW = "shadow"

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

        private fun sanitizeUrlForSpoof(inputUrl: String): SanitizedUrl {
            val fragmentIndex = inputUrl.indexOf('#')
            val baseWithQuery =
                if (fragmentIndex >= 0) inputUrl.substring(0, fragmentIndex) else inputUrl
            val fragmentSuffix =
                if (fragmentIndex >= 0) inputUrl.substring(fragmentIndex) else ""

            val queryIndex = baseWithQuery.indexOf('?')
            if (queryIndex < 0) return SanitizedUrl(url = inputUrl, wasSanitized = false)

            val base = baseWithQuery.substring(0, queryIndex)
            val rawQuery = baseWithQuery.substring(queryIndex + 1)
            if (rawQuery.isBlank()) return SanitizedUrl(url = inputUrl, wasSanitized = false)

            val keptParts = rawQuery
                .split("&")
                .filter { it.isNotBlank() }
                .filterNot(::isTrackingQueryPart)

            val sanitizedBase = if (keptParts.isEmpty()) {
                base
            } else {
                "$base?${keptParts.joinToString("&")}"
            }
            val sanitizedUrl = "$sanitizedBase$fragmentSuffix"
            return SanitizedUrl(
                url = sanitizedUrl,
                wasSanitized = sanitizedUrl != inputUrl,
            )
        }

        private fun isTrackingQueryPart(rawPart: String): Boolean {
            val rawKey = rawPart.substringBefore('=', rawPart).trim()
            if (rawKey.isBlank()) return false
            val decodedKey = runCatching {
                URLDecoder.decode(rawKey, Charsets.UTF_8.name())
            }.getOrDefault(rawKey)
            val normalizedKey = decodedKey.lowercase(Locale.US)
            return normalizedKey.startsWith("utm_") || normalizedKey in TRACKING_QUERY_PARAM_KEYS
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
        _uiState.update { current ->
            current.copy(isAdmin = isAdminUser)
        }
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

    fun onEngineOverrideChanged(override: EngineOverride) {
        _uiState.update { current ->
            if (!current.isAdmin) return@update current
            current.copy(adminEngineOverride = override)
        }
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
            var snifferExtraction: ExtractionResult? = null
            var cleanControlExtraction: ExtractionResult? = null
            var shadowSampled = false
            var cleanControlExecutionMode = CLEAN_CONTROL_MODE_NONE
            var tacticSourcePass = PHASE_SNIFFER

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
                        processState = HomeProcessState.Processing("Running Sniffer Pass..."),
                    )
                }
                var snifferResultValue: Result<ExtractionResult> =
                    Result.failure(IllegalStateException("Sniffer pass did not run."))
                val snifferNavigationUrl = buildSnifferNavigationUrl(submittedUrl)
                val snifferLatencyMs = measureTimeMillis {
                    snifferResultValue = extractionEngine.loadAndExtract(
                        snifferNavigationUrl,
                        request = ExtractionRequest(
                            cleanSessionRequired = true,
                            phase = PHASE_SNIFFER,
                            strictTrackingProtection = false,
                        ),
                    )
                }
                val snifferResult = snifferResultValue
                if (snifferResult.isFailure) {
                    val throwable = snifferResult.exceptionOrNull()
                    attemptRows += buildAttemptRow(
                        phase = PHASE_SNIFFER,
                        attemptIndex = 0,
                        vpnConfig = null,
                        success = false,
                        throwable = throwable,
                        extracted = null,
                        latencyMs = snifferLatencyMs,
                        executionUrl = submittedUrl,
                        appliedLevers = buildAppliedLevers(
                            urlSanitized = false,
                            amnesiaProtocol = false,
                            trackingProtection = TRACKING_PROTECTION_OFF,
                        ),
                    )
                    diagnostics += "Sniffer Pass failed: ${throwable.toUserMessage()}"
                    Log.e(TAG, "Sniffer pass failed", throwable)
                    _uiState.update { current ->
                        current.copy(processState = HomeProcessState.Processing("Running Clean Control fallback..."))
                    }
                    cleanControlExecutionMode = CLEAN_CONTROL_MODE_FALLBACK
                    var cleanControlResultValue: Result<ExtractionResult> =
                        Result.failure(IllegalStateException("Clean Control fallback did not run."))
                    val cleanControlNavigationUrl = buildCleanControlNavigationUrl(submittedUrl)
                    val cleanControlLatencyMs = measureTimeMillis {
                        cleanControlResultValue = extractionEngine.loadAndExtract(
                            cleanControlNavigationUrl,
                            request = ExtractionRequest(
                                cleanSessionRequired = true,
                                phase = PHASE_CLEAN_CONTROL,
                                strictTrackingProtection = false,
                            ),
                        )
                    }
                    val cleanControlResult = cleanControlResultValue
                    if (cleanControlResult.isFailure) {
                        val cleanControlThrowable = cleanControlResult.exceptionOrNull()
                        attemptRows += buildAttemptRow(
                            phase = PHASE_CLEAN_CONTROL,
                            attemptIndex = 0,
                            vpnConfig = null,
                            success = false,
                            throwable = cleanControlThrowable,
                            extracted = null,
                            latencyMs = cleanControlLatencyMs,
                            executionUrl = submittedUrl,
                            appliedLevers = buildAppliedLevers(
                                urlSanitized = false,
                                amnesiaProtocol = false,
                                trackingProtection = TRACKING_PROTECTION_OFF,
                            ),
                        )
                        terminalError = "Sniffer Pass failed and Clean Control fallback failed: ${cleanControlThrowable.toUserMessage()}. You can continue shopping manually."
                        diagnostics += terminalError.orEmpty()
                        val failedPreSpoofCheck = buildPriceCheck(
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
                            outcome = "degraded_pre_spoof_failed",
                            degraded = true,
                            baselineSuccess = false,
                            spoofSuccess = false,
                            dirtyBaselinePriceCents = dirtyBaselinePriceCents,
                            diagnostics = diagnostics,
                            snifferPriceCents = null,
                            cleanControlPriceCents = null,
                            tacticSourcePass = null,
                            cleanControlExecutionMode = cleanControlExecutionMode,
                            shadowSampled = false,
                        )
                        _uiState.update { current ->
                            current.copy(processState = HomeProcessState.Processing("Logging fallback result..."))
                        }
                        val fallbackLogResult = repository.logPriceCheckRun(failedPreSpoofCheck, attemptRows)
                        if (fallbackLogResult.isFailure) {
                            val logThrowable = fallbackLogResult.exceptionOrNull()
                            val logMessage = "Supabase fallback log failed: ${logThrowable.toUserMessage()}"
                            Log.e(TAG, "price_checks fallback insert failed", logThrowable)
                            terminalError = "$terminalError | $logMessage"
                        }
                        finalShowBrowser = true
                        return@launch
                    }
                    cleanControlExtraction = cleanControlResult.getOrThrow()
                    attemptRows += buildAttemptRow(
                        phase = PHASE_CLEAN_CONTROL,
                        attemptIndex = 0,
                        vpnConfig = null,
                        success = true,
                        throwable = null,
                        extracted = cleanControlExtraction,
                        latencyMs = cleanControlLatencyMs,
                        executionUrl = submittedUrl,
                        appliedLevers = buildAppliedLevers(
                            urlSanitized = false,
                            amnesiaProtocol = true,
                            trackingProtection = TRACKING_PROTECTION_OFF,
                        ),
                    )
                    tacticSourcePass = PHASE_CLEAN_CONTROL
                    diagnostics += "Sniffer Pass failed. Clean Control fallback succeeded."
                } else {
                    snifferExtraction = snifferResult.getOrThrow()
                    attemptRows += buildAttemptRow(
                        phase = PHASE_SNIFFER,
                        attemptIndex = 0,
                        vpnConfig = null,
                        success = true,
                        throwable = null,
                        extracted = snifferExtraction,
                        latencyMs = snifferLatencyMs,
                        executionUrl = submittedUrl,
                        appliedLevers = buildAppliedLevers(
                            urlSanitized = false,
                            amnesiaProtocol = true,
                            trackingProtection = TRACKING_PROTECTION_OFF,
                        ),
                    )
                    shadowSampled = shadowCleanControlSampler(submittedUrl)
                    if (shadowSampled) {
                        cleanControlExecutionMode = CLEAN_CONTROL_MODE_SHADOW
                        _uiState.update { current ->
                            current.copy(processState = HomeProcessState.Processing("Running Clean Control shadow pass..."))
                        }
                        var cleanControlShadowValue: Result<ExtractionResult> =
                            Result.failure(IllegalStateException("Clean Control shadow pass did not run."))
                        val cleanControlNavigationUrl = buildCleanControlNavigationUrl(submittedUrl)
                        val cleanControlShadowLatencyMs = measureTimeMillis {
                            cleanControlShadowValue = extractionEngine.loadAndExtract(
                                cleanControlNavigationUrl,
                                request = ExtractionRequest(
                                    cleanSessionRequired = true,
                                    phase = PHASE_CLEAN_CONTROL,
                                    strictTrackingProtection = false,
                                ),
                            )
                        }
                        val cleanControlShadowResult = cleanControlShadowValue
                        if (cleanControlShadowResult.isSuccess) {
                            cleanControlExtraction = cleanControlShadowResult.getOrThrow()
                            attemptRows += buildAttemptRow(
                                phase = PHASE_CLEAN_CONTROL,
                                attemptIndex = 0,
                                vpnConfig = null,
                                success = true,
                                throwable = null,
                                extracted = cleanControlExtraction,
                                latencyMs = cleanControlShadowLatencyMs,
                                executionUrl = submittedUrl,
                                appliedLevers = buildAppliedLevers(
                                    urlSanitized = false,
                                    amnesiaProtocol = true,
                                    trackingProtection = TRACKING_PROTECTION_OFF,
                                ),
                            )
                            diagnostics += "Clean Control Pass shadow telemetry captured."
                        } else {
                            val shadowThrowable = cleanControlShadowResult.exceptionOrNull()
                            attemptRows += buildAttemptRow(
                                phase = PHASE_CLEAN_CONTROL,
                                attemptIndex = 0,
                                vpnConfig = null,
                                success = false,
                                throwable = shadowThrowable,
                                extracted = null,
                                latencyMs = cleanControlShadowLatencyMs,
                                executionUrl = submittedUrl,
                                appliedLevers = buildAppliedLevers(
                                    urlSanitized = false,
                                    amnesiaProtocol = false,
                                    trackingProtection = TRACKING_PROTECTION_OFF,
                                ),
                            )
                            diagnostics += "Clean Control Pass shadow failed (non-terminal): ${shadowThrowable.toUserMessage()}"
                        }
                    }
                }

                val tacticSourceExtraction = if (tacticSourcePass == PHASE_CLEAN_CONTROL) {
                    cleanControlExtraction
                } else {
                    snifferExtraction
                }
                if (tacticSourceExtraction == null) {
                    terminalError = "No successful pre-spoof pass available. You can continue shopping manually."
                    diagnostics += terminalError.orEmpty()
                    val failedPreSpoofCheck = buildPriceCheck(
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
                        outcome = "degraded_pre_spoof_failed",
                        degraded = true,
                        baselineSuccess = false,
                        spoofSuccess = false,
                        dirtyBaselinePriceCents = dirtyBaselinePriceCents,
                        diagnostics = diagnostics,
                        snifferPriceCents = snifferExtraction?.priceCents,
                        cleanControlPriceCents = cleanControlExtraction?.priceCents,
                        tacticSourcePass = null,
                        cleanControlExecutionMode = cleanControlExecutionMode,
                        shadowSampled = shadowSampled,
                    )
                    val failedPreLog = repository.logPriceCheckRun(failedPreSpoofCheck, attemptRows)
                    if (failedPreLog.isFailure) {
                        diagnostics += "Supabase log failed: ${failedPreLog.exceptionOrNull().toUserMessage()}"
                    }
                    finalShowBrowser = true
                    return@launch
                }

                _uiState.update { current ->
                    current.copy(processState = HomeProcessState.Processing("Determining strategy..."))
                }
                val strategy =
                    strategyEngine.determineStrategy(
                        url = submittedUrl,
                        baselineTactics = tacticSourceExtraction.tactics,
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
                            baselinePriceCents = tacticSourceExtraction.priceCents,
                            foundPriceCents = tacticSourceExtraction.priceCents,
                            extractionSuccessful = false,
                            tactics = tacticSourceExtraction.tactics,
                            attemptedConfigs = emptyList(),
                            finalConfig = null,
                            retryCount = 0,
                            outcome = "strategy_failed",
                            degraded = tacticSourcePass == PHASE_CLEAN_CONTROL,
                            baselineSuccess = true,
                            spoofSuccess = false,
                            dirtyBaselinePriceCents = dirtyBaselinePriceCents,
                            diagnostics = diagnostics,
                            snifferPriceCents = snifferExtraction?.priceCents,
                            cleanControlPriceCents = cleanControlExtraction?.priceCents,
                            tacticSourcePass = tacticSourcePass,
                            cleanControlExecutionMode = cleanControlExecutionMode,
                            shadowSampled = shadowSampled,
                        )
                        val failedLogResult = repository.logPriceCheckRun(failedPriceCheck, attemptRows)
                        if (failedLogResult.isSuccess) {
                            diagnostics += logResultDiagnostics(failedLogResult.getOrThrow())
                        }
                        return@launch
                    }
                val profileResolution = resolveActiveEngineProfile(
                    strategyProfile = strategy.engineProfile,
                    override = _uiState.value.adminEngineOverride,
                    isAdmin = _uiState.value.isAdmin,
                )
                val spoofUrlPlan = when (profileResolution.profile) {
                    EngineProfile.LEGACY -> SanitizedUrl(url = submittedUrl, wasSanitized = false)
                    EngineProfile.YALE_SMART -> sanitizeUrlForSpoof(submittedUrl)
                }
                var spoofedResult: ExtractionResult? = null
                var finalConfig: String? = null
                for (attempt in 0 until SPOOF_ATTEMPT_MAX) {
                    val config = resolveSpoofConfig(
                        excludedConfigs = attemptedConfigs.toSet(),
                        strategyConfig = strategy.wireguardConfig,
                    )
                    if (config == null) {
                        terminalError = "No enabled VPN configs available for spoof attempts."
                        diagnostics += terminalError.orEmpty()
                        break
                    }
                    if (config !in attemptedConfigs) {
                        attemptedConfigs += config
                    }
                    val attemptNumber = attempt + 1
                    val execution = runSpoofAttempt(
                        executionUrl = spoofUrlPlan.url,
                        urlSanitized = spoofUrlPlan.wasSanitized,
                        strategy = strategy,
                        engineProfile = profileResolution.profile,
                        engineSelectionSource = profileResolution.selectionSource,
                        config = config,
                        attemptNumber = attemptNumber,
                    )
                    when (execution) {
                        is SpoofAttemptExecution.PermissionRequired -> {
                            pendingVpnContinuation = PendingVpnContinuation(
                                submittedUrl = submittedUrl,
                                preSpoofResult = tacticSourceExtraction,
                                preSpoofTacticSourcePass = tacticSourcePass,
                                snifferResult = snifferExtraction,
                                cleanControlResult = cleanControlExtraction,
                                cleanControlExecutionMode = cleanControlExecutionMode,
                                shadowSampled = shadowSampled,
                                strategy = strategy,
                                dirtyBaselinePriceCents = dirtyBaselinePriceCents,
                                attemptIndex = attempt,
                                waitingConfig = config,
                                spoofExecutionUrl = spoofUrlPlan.url,
                                spoofUrlSanitized = spoofUrlPlan.wasSanitized,
                                spoofEngineProfile = profileResolution.profile,
                                spoofEngineSelectionSource = profileResolution.selectionSource,
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
                                phase = PHASE_SPOOF,
                                attemptIndex = attemptNumber,
                                vpnConfig = config,
                                success = false,
                                throwable = execution.throwable,
                                extracted = null,
                                latencyMs = execution.latencyMs,
                                executionUrl = spoofUrlPlan.url,
                                appliedLevers = execution.appliedLevers,
                            )
                            diagnostics += execution.userMessage
                            if (execution.throwable != null) {
                                Log.e(TAG, "Spoof attempt failed (attempt=$attemptNumber, config=$config)", execution.throwable)
                            }
                        }

                        is SpoofAttemptExecution.Success -> {
                            vpnConnectedThisRun = true
                            attemptRows += buildAttemptRow(
                                phase = PHASE_SPOOF,
                                attemptIndex = attemptNumber,
                                vpnConfig = config,
                                success = true,
                                throwable = null,
                                extracted = execution.result,
                                latencyMs = execution.latencyMs,
                                executionUrl = spoofUrlPlan.url,
                                appliedLevers = execution.appliedLevers,
                            )
                            spoofedResult = execution.result
                            finalConfig = config
                            activeVpnConfig = config
                            break
                        }
                    }
                }

                if (spoofedResult == null) {
                    terminalError = terminalError ?: when {
                        diagnostics.any { it.contains("Secure tunnel unavailable", ignoreCase = true) } ->
                            "Spoofed extraction failed after bounded retry. Secure tunnel unavailable; reconnect VPN and retry."
                        else -> "Spoofed extraction failed after bounded retry."
                    }
                    val failedPriceCheck = buildPriceCheck(
                        url = submittedUrl,
                        strategyId = strategy.strategyId,
                        strategyName = strategy.strategyName,
                        baselinePriceCents = tacticSourceExtraction.priceCents,
                        foundPriceCents = tacticSourceExtraction.priceCents,
                        extractionSuccessful = false,
                        tactics = tacticSourceExtraction.tactics,
                        attemptedConfigs = attemptedConfigs,
                        finalConfig = finalConfig,
                        retryCount = retryCountFromAttempts(attemptRows),
                        outcome = "spoof_failed",
                        degraded = true,
                        baselineSuccess = true,
                        spoofSuccess = false,
                        dirtyBaselinePriceCents = dirtyBaselinePriceCents,
                        diagnostics = diagnostics,
                        snifferPriceCents = snifferExtraction?.priceCents,
                        cleanControlPriceCents = cleanControlExtraction?.priceCents,
                        tacticSourcePass = tacticSourcePass,
                        cleanControlExecutionMode = cleanControlExecutionMode,
                        shadowSampled = shadowSampled,
                    )
                    _uiState.update { current ->
                        current.copy(processState = HomeProcessState.Processing("Logging run result..."))
                    }
                    val failedLogResult = repository.logPriceCheckRun(failedPriceCheck, attemptRows)
                    if (failedLogResult.isFailure) {
                        diagnostics += "Supabase log failed: ${failedLogResult.exceptionOrNull().toUserMessage()}"
                    } else {
                        diagnostics += logResultDiagnostics(failedLogResult.getOrThrow())
                    }
                    return@launch
                }

                val priceCheck = buildPriceCheck(
                    url = submittedUrl,
                    strategyId = strategy.strategyId,
                    strategyName = strategy.strategyName,
                    baselinePriceCents = tacticSourceExtraction.priceCents,
                    foundPriceCents = spoofedResult.priceCents,
                    extractionSuccessful = true,
                    tactics = tacticSourceExtraction.tactics,
                    attemptedConfigs = attemptedConfigs,
                    finalConfig = finalConfig,
                    retryCount = retryCountFromAttempts(attemptRows),
                    outcome = if (tacticSourcePass == PHASE_CLEAN_CONTROL) {
                        "degraded_sniffer_fallback_success"
                    } else {
                        "success"
                    },
                    degraded = tacticSourcePass == PHASE_CLEAN_CONTROL,
                    baselineSuccess = true,
                    spoofSuccess = true,
                    dirtyBaselinePriceCents = dirtyBaselinePriceCents,
                    diagnostics = diagnostics,
                    snifferPriceCents = snifferExtraction?.priceCents,
                    cleanControlPriceCents = cleanControlExtraction?.priceCents,
                    tacticSourcePass = tacticSourcePass,
                    cleanControlExecutionMode = cleanControlExecutionMode,
                    shadowSampled = shadowSampled,
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
                val runLog = logResult.getOrThrow()
                diagnostics += logResultDiagnostics(runLog)

                Log.i("HomeViewModel", "price_checks insert succeeded")
                val lifetimeSavingsCents = repository.fetchLifetimePotentialSavingsCents()
                    .getOrDefault(0)
                successSummary = buildSuccessSummary(
                    tacticSourceResult = tacticSourceExtraction,
                    snifferResult = snifferExtraction,
                    cleanControlResult = cleanControlExtraction,
                    tacticSourcePass = tacticSourcePass,
                    cleanControlExecutionMode = cleanControlExecutionMode,
                    shadowSampled = shadowSampled,
                    spoofedResult = spoofedResult,
                    dirtyBaselinePriceCents = dirtyBaselinePriceCents,
                    attemptedConfigs = attemptedConfigs,
                    finalConfig = requireNotNull(finalConfig) {
                        "Spoof success missing final config."
                    },
                    retryCount = retryCountFromAttempts(attemptRows),
                    outcome = if (runLog.attemptsInserted) {
                        priceCheck.outcome ?: "success"
                    } else {
                        "partial_log_failure"
                    },
                    diagnostics = diagnostics,
                    lifetimePotentialSavingsCents = lifetimeSavingsCents,
                    strategyName = strategy.strategyName,
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
                        phase = PHASE_SPOOF,
                        attemptIndex = pending.attemptIndex + 1,
                        vpnConfig = pending.waitingConfig,
                        success = false,
                        throwable = IllegalStateException("vpn_permission_denied"),
                        extracted = null,
                        latencyMs = 0L,
                        executionUrl = pending.spoofExecutionUrl,
                        appliedLevers = buildAppliedLevers(
                            urlSanitized = pending.spoofUrlSanitized,
                            amnesiaProtocol = false,
                            trackingProtection = trackingProtectionForProfile(pending.spoofEngineProfile),
                            strategy = pending.strategy,
                            engineProfile = pending.spoofEngineProfile,
                            engineSelectionSource = pending.spoofEngineSelectionSource,
                        ),
                    ),
                )
            }
            viewModelScope.launch {
                val deniedPriceCheck = buildPriceCheck(
                    url = pending.submittedUrl,
                    strategyId = pending.strategy.strategyId,
                    strategyName = pending.strategy.strategyName,
                    baselinePriceCents = pending.preSpoofResult.priceCents,
                    foundPriceCents = pending.preSpoofResult.priceCents,
                    extractionSuccessful = false,
                    tactics = pending.preSpoofResult.tactics,
                    attemptedConfigs = pending.attemptedConfigs,
                    finalConfig = null,
                    retryCount = retryCountFromAttempts(attemptRows),
                    outcome = "vpn_permission_denied",
                    degraded = true,
                    baselineSuccess = true,
                    spoofSuccess = false,
                    dirtyBaselinePriceCents = pending.dirtyBaselinePriceCents,
                    diagnostics = diagnostics,
                    snifferPriceCents = pending.snifferResult?.priceCents,
                    cleanControlPriceCents = pending.cleanControlResult?.priceCents,
                    tacticSourcePass = pending.preSpoofTacticSourcePass,
                    cleanControlExecutionMode = pending.cleanControlExecutionMode,
                    shadowSampled = pending.shadowSampled,
                )
                val deniedLogResult = repository.logPriceCheckRun(deniedPriceCheck, attemptRows)
                if (deniedLogResult.isSuccess) {
                    diagnostics += logResultDiagnostics(deniedLogResult.getOrThrow())
                }
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
                        val resolved = resolveSpoofConfig(
                            excludedConfigs = attemptedConfigs.toSet(),
                            strategyConfig = pending.strategy.wireguardConfig,
                        )
                        if (resolved == null) {
                            terminalError = "No enabled VPN configs available for spoof attempts."
                            diagnostics += terminalError.orEmpty()
                            break
                        }
                        if (resolved !in attemptedConfigs) {
                            attemptedConfigs += resolved
                        }
                        resolved
                    }

                    val execution = runSpoofAttempt(
                        executionUrl = pending.spoofExecutionUrl,
                        urlSanitized = pending.spoofUrlSanitized,
                        strategy = pending.strategy,
                        engineProfile = pending.spoofEngineProfile,
                        engineSelectionSource = pending.spoofEngineSelectionSource,
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
                                phase = PHASE_SPOOF,
                                attemptIndex = attemptNumber,
                                vpnConfig = config,
                                success = false,
                                throwable = execution.throwable,
                                extracted = null,
                                latencyMs = execution.latencyMs,
                                executionUrl = pending.spoofExecutionUrl,
                                appliedLevers = execution.appliedLevers,
                            )
                            diagnostics += execution.userMessage
                        }

                        is SpoofAttemptExecution.Success -> {
                            vpnConnectedThisRun = true
                            attemptRows += buildAttemptRow(
                                phase = PHASE_SPOOF,
                                attemptIndex = attemptNumber,
                                vpnConfig = config,
                                success = true,
                                throwable = null,
                                extracted = execution.result,
                                latencyMs = execution.latencyMs,
                                executionUrl = pending.spoofExecutionUrl,
                                appliedLevers = execution.appliedLevers,
                            )
                            spoofedResult = execution.result
                            finalConfig = config
                            activeVpnConfig = config
                            break
                        }
                    }
                }

                if (spoofedResult == null) {
                    terminalError = terminalError ?: when {
                        diagnostics.any { it.contains("Secure tunnel unavailable", ignoreCase = true) } ->
                            "Spoofed extraction failed after bounded retry. Secure tunnel unavailable; reconnect VPN and retry."
                        else -> "Spoofed extraction failed after bounded retry."
                    }
                    val failedPriceCheck = buildPriceCheck(
                        url = pending.submittedUrl,
                        strategyId = pending.strategy.strategyId,
                        strategyName = pending.strategy.strategyName,
                        baselinePriceCents = pending.preSpoofResult.priceCents,
                        foundPriceCents = pending.preSpoofResult.priceCents,
                        extractionSuccessful = false,
                        tactics = pending.preSpoofResult.tactics,
                        attemptedConfigs = attemptedConfigs,
                        finalConfig = finalConfig,
                        retryCount = retryCountFromAttempts(attemptRows),
                        outcome = "spoof_failed",
                        degraded = true,
                        baselineSuccess = true,
                        spoofSuccess = false,
                        dirtyBaselinePriceCents = pending.dirtyBaselinePriceCents,
                        diagnostics = diagnostics,
                        snifferPriceCents = pending.snifferResult?.priceCents,
                        cleanControlPriceCents = pending.cleanControlResult?.priceCents,
                        tacticSourcePass = pending.preSpoofTacticSourcePass,
                        cleanControlExecutionMode = pending.cleanControlExecutionMode,
                        shadowSampled = pending.shadowSampled,
                    )
                    val failedLogResult = repository.logPriceCheckRun(failedPriceCheck, attemptRows)
                    if (failedLogResult.isSuccess) {
                        diagnostics += logResultDiagnostics(failedLogResult.getOrThrow())
                    }
                    return@launch
                }

                val priceCheck = buildPriceCheck(
                    url = pending.submittedUrl,
                    strategyId = pending.strategy.strategyId,
                    strategyName = pending.strategy.strategyName,
                    baselinePriceCents = pending.preSpoofResult.priceCents,
                    foundPriceCents = spoofedResult.priceCents,
                    extractionSuccessful = true,
                    tactics = pending.preSpoofResult.tactics,
                    attemptedConfigs = attemptedConfigs,
                    finalConfig = finalConfig,
                    retryCount = retryCountFromAttempts(attemptRows),
                    outcome = if (pending.preSpoofTacticSourcePass == PHASE_CLEAN_CONTROL) {
                        "degraded_sniffer_fallback_success"
                    } else {
                        "success"
                    },
                    degraded = pending.preSpoofTacticSourcePass == PHASE_CLEAN_CONTROL,
                    baselineSuccess = true,
                    spoofSuccess = true,
                    dirtyBaselinePriceCents = pending.dirtyBaselinePriceCents,
                    diagnostics = diagnostics,
                    snifferPriceCents = pending.snifferResult?.priceCents,
                    cleanControlPriceCents = pending.cleanControlResult?.priceCents,
                    tacticSourcePass = pending.preSpoofTacticSourcePass,
                    cleanControlExecutionMode = pending.cleanControlExecutionMode,
                    shadowSampled = pending.shadowSampled,
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
                val runLog = logResult.getOrThrow()
                diagnostics += logResultDiagnostics(runLog)

                val lifetimeSavingsCents = repository.fetchLifetimePotentialSavingsCents()
                    .getOrDefault(0)
                successSummary = buildSuccessSummary(
                    tacticSourceResult = pending.preSpoofResult,
                    snifferResult = pending.snifferResult,
                    cleanControlResult = pending.cleanControlResult,
                    tacticSourcePass = pending.preSpoofTacticSourcePass,
                    cleanControlExecutionMode = pending.cleanControlExecutionMode,
                    shadowSampled = pending.shadowSampled,
                    spoofedResult = spoofedResult,
                    dirtyBaselinePriceCents = pending.dirtyBaselinePriceCents,
                    attemptedConfigs = attemptedConfigs,
                    finalConfig = finalConfig ?: pending.waitingConfig,
                    retryCount = retryCountFromAttempts(attemptRows),
                    outcome = if (runLog.attemptsInserted) {
                        priceCheck.outcome ?: "success"
                    } else {
                        "partial_log_failure"
                    },
                    diagnostics = diagnostics,
                    lifetimePotentialSavingsCents = lifetimeSavingsCents,
                    strategyName = pending.strategy.strategyName,
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
        executionUrl: String,
        urlSanitized: Boolean,
        strategy: StrategyResult,
        engineProfile: EngineProfile,
        engineSelectionSource: String,
        config: String,
        attemptNumber: Int,
    ): SpoofAttemptExecution {
        var connected = false
        var extractionResult: Result<ExtractionResult>? = null
        var permissionIntent: Intent? = null
        var failureUserMessage: String? = null
        val strictTrackingProtection = engineProfile == EngineProfile.YALE_SMART
        val trackingProtection = trackingProtectionForProfile(engineProfile)
        val navigationUrl = buildEngineBootstrapNavigationUrl(executionUrl, engineProfile)
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
                failureUserMessage = if (isLikelyVpnConnectivityIssue(throwable)) {
                    "Secure tunnel unavailable. Verify device VPN is connected, then retry."
                } else {
                    "Spoof attempt failed: ${throwable.toUserMessage()}"
                }
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
            Log.i(
                TAG,
                "Spoof execution URL prepared (sanitized=$urlSanitized): $executionUrl",
            )
            extractionResult = extractionEngine.loadAndExtract(
                navigationUrl,
                request = ExtractionRequest(
                    cleanSessionRequired = true,
                    phase = PHASE_SPOOF,
                    strictTrackingProtection = strictTrackingProtection,
                ),
            )
        }

        val result = extractionResult
        if (result == null) {
            if (permissionIntent != null) {
                return SpoofAttemptExecution.PermissionRequired(
                    intent = permissionIntent!!,
                    appliedLevers = buildAppliedLevers(
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
                appliedLevers = buildAppliedLevers(
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
            vpnRotationEngine.reportAttemptResult(config, success = true)
            return SpoofAttemptExecution.Success(
                result = result.getOrThrow(),
                appliedLevers = buildAppliedLevers(
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
            else -> "Spoof attempt failed: ${throwable.toUserMessage()}"
        }
        return SpoofAttemptExecution.Failure(
            throwable = throwable,
            connected = connected,
            userMessage = resolvedUserMessage,
            appliedLevers = buildAppliedLevers(
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
        snifferPriceCents: Int?,
        cleanControlPriceCents: Int?,
        tacticSourcePass: String?,
        cleanControlExecutionMode: String,
        shadowSampled: Boolean,
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
            rawExtractionData = buildJsonObject {
                put(
                    "detected_tactics",
                    JsonArray(tactics.map { JsonPrimitive(it) }),
                )
                put(
                    "diagnostics",
                    JsonArray(diagnostics.map { JsonPrimitive(it) }),
                )
                snifferPriceCents?.let { put("sniffer_price_cents", JsonPrimitive(it)) }
                cleanControlPriceCents?.let { put("clean_control_price_cents", JsonPrimitive(it)) }
                tacticSourcePass?.let { put("tactic_source_pass", JsonPrimitive(it)) }
                put("clean_control_execution_mode", JsonPrimitive(cleanControlExecutionMode))
                put("shadow_sampled", JsonPrimitive(shadowSampled))
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

    private fun buildAppliedLevers(
        urlSanitized: Boolean,
        amnesiaProtocol: Boolean? = null,
        trackingProtection: String? = null,
        strategy: StrategyResult? = null,
        engineProfile: EngineProfile? = null,
        engineSelectionSource: String? = null,
    ): JsonObject {
        return buildJsonObject {
            put("url_sanitized", JsonPrimitive(urlSanitized))
            if (amnesiaProtocol != null) {
                put("amnesia_protocol", JsonPrimitive(amnesiaProtocol))
            }
            if (trackingProtection != null) {
                put("tracking_protection", JsonPrimitive(trackingProtection))
            }
            if (strategy != null) {
                put("strategy_name", JsonPrimitive(strategy.strategyName))
                put("strategy_engine", JsonPrimitive(strategy.strategyEngineName))
                put("strategy_version", JsonPrimitive(strategy.strategyVersion))
            }
            if (engineProfile != null) {
                put("engine_profile", JsonPrimitive(engineProfile.toTelemetryValue()))
                put("engine_version", JsonPrimitive(ENGINE_VERSION))
                put("engine_build_id", JsonPrimitive(ENGINE_BUILD_ID))
            }
            if (engineSelectionSource != null) {
                put("engine_selection_source", JsonPrimitive(engineSelectionSource))
            }
        }
    }

    private fun retryCountFromAttempts(attemptRows: List<PriceCheckAttempt>): Int {
        val spoofAttempts = attemptRows.count { it.phase == PHASE_SPOOF }
        return (spoofAttempts - 1).coerceAtLeast(0)
    }

    private fun logResultDiagnostics(result: RunLogResult): List<String> {
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

    private fun Throwable?.toUserMessage(): String {
        val throwable = this ?: return "Unknown error"
        return throwable.message ?: throwable::class.java.simpleName
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
        return throwable is CleanSessionPreparationException ||
            throwable?.cause is CleanSessionPreparationException
    }

    private fun resolveActiveEngineProfile(
        strategyProfile: EngineProfile,
        override: EngineOverride,
        isAdmin: Boolean,
    ): ActiveEngineProfile {
        if (!isAdmin || override == EngineOverride.AUTO) {
            return ActiveEngineProfile(
                profile = strategyProfile,
                selectionSource = "strategy",
            )
        }
        return when (override) {
            EngineOverride.AUTO -> ActiveEngineProfile(strategyProfile, "strategy")
            EngineOverride.FORCE_LEGACY ->
                ActiveEngineProfile(EngineProfile.LEGACY, "admin_override")
            EngineOverride.FORCE_YALE_SMART ->
                ActiveEngineProfile(EngineProfile.YALE_SMART, "admin_override")
        }
    }

    private fun trackingProtectionForProfile(profile: EngineProfile): String {
        return if (profile == EngineProfile.YALE_SMART) TRACKING_PROTECTION_STRICT
        else TRACKING_PROTECTION_OFF
    }

    private fun EngineProfile.toTelemetryValue(): String {
        return when (this) {
            EngineProfile.LEGACY -> CONTROL_PROFILE_TOKEN
            EngineProfile.YALE_SMART -> YALE_SMART_PROFILE_TOKEN
        }
    }

    private fun buildEngineBootstrapNavigationUrl(
        executionUrl: String,
        profile: EngineProfile,
    ): String {
        return appendEngineBootstrapToken(executionUrl, profile.toTelemetryValue())
    }

    private fun buildSnifferNavigationUrl(executionUrl: String): String {
        return appendEngineBootstrapToken(executionUrl, SNIFFER_INTEL_TOKEN)
    }

    private fun buildCleanControlNavigationUrl(executionUrl: String): String {
        return appendEngineBootstrapToken(executionUrl, CLEAN_CONTROL_INTEL_TOKEN)
    }

    private fun appendEngineBootstrapToken(
        executionUrl: String,
        tokenValue: String,
    ): String {
        // Contract:
        // - No fragment => append "#fp_engine=<token>".
        // - Existing fragment => append/replace using "&" within hash params.
        // - Existing fp_engine key is replaced to avoid duplicate token keys.
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
                part.substringBefore('=')
                    .trim()
                    .equals(ENGINE_HASH_KEY, ignoreCase = true)
            }
            .toMutableList()
        hashParts += "$ENGINE_HASH_KEY=$tokenValue"
        return "$base#${hashParts.joinToString("&")}"
    }

    private fun shouldRunShadowCleanControl(url: String): Boolean {
        val normalized = url.trim().lowercase(Locale.US)
        val bucket = (normalized.hashCode() and Int.MAX_VALUE) % 100
        return bucket < SHADOW_CLEAN_CONTROL_SAMPLE_PERCENT
    }

    private suspend fun ensureBaselineVpnActive(): String? {
        val baselineId = resolveBaselineConfigId()
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

    private fun resolveBaselineConfigId(): String? {
        val baseline = vpnConfigStore.getBaselineConfigId()
        return baseline?.takeIf { it.isNotBlank() }
    }

    private fun resolveSpoofConfig(
        excludedConfigs: Set<String>,
        strategyConfig: String?,
    ): String? {
        val fromRotation = vpnRotationEngine.nextConfig(excludedConfigs)
        if (fromRotation != null) return fromRotation

        val fallback = strategyConfig?.takeIf { it.isNotBlank() } ?: return null
        return fallback
    }

    private fun resolveConfigSource(configId: String?): String? {
        if (configId.isNullOrBlank()) return null
        return if (configId.startsWith(USER_CONFIG_PREFIX)) "user" else "asset"
    }

    private fun resolveConfigProvider(configId: String?): String? {
        if (configId.isNullOrBlank()) return null
        if (configId.startsWith(USER_CONFIG_PREFIX)) {
            return vpnConfigStore.listUserConfigs()
                .firstOrNull { it.id == configId }
                ?.providerHint
                ?: "unknown"
        }
        return inferProviderFromAssetConfigName(configId)
    }

    private fun inferProviderFromAssetConfigName(configName: String): String {
        val normalized = configName.lowercase(Locale.US)
        return when {
            "proton" in normalized -> "proton"
            "surfshark" in normalized -> "surfshark"
            "mullvad" in normalized -> "mullvad"
            "nord" in normalized -> "nordvpn"
            else -> "asset"
        }
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
        val baselineDisplay = resolveBaselineConfigId()?.let(::displayConfigLabel) ?: "Not set"
        return SummaryData(
            lifetimePotentialSavings = formatUsd(lifetimePotentialSavingsCents),
            baselineConfig = baselineDisplay,
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
            val appliedLevers: JsonObject,
            override val latencyMs: Long,
        ) : SpoofAttemptExecution

        data class PermissionRequired(
            val intent: Intent,
            val appliedLevers: JsonObject,
            override val latencyMs: Long,
        ) : SpoofAttemptExecution
    }

    private data class PendingVpnContinuation(
        val submittedUrl: String,
        val preSpoofResult: ExtractionResult,
        val preSpoofTacticSourcePass: String,
        val snifferResult: ExtractionResult?,
        val cleanControlResult: ExtractionResult?,
        val cleanControlExecutionMode: String,
        val shadowSampled: Boolean,
        val strategy: StrategyResult,
        val dirtyBaselinePriceCents: Int?,
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

    private data class ActiveEngineProfile(
        val profile: EngineProfile,
        val selectionSource: String,
    )

    private data class SanitizedUrl(
        val url: String,
        val wasSanitized: Boolean,
    )

    private fun sanitizeDigitsOnly(value: String): String = value.filter(Char::isDigit)

    private fun parseDirtyBaselineCents(raw: String): Int? {
        val normalized = sanitizeDigitsOnly(raw)
        if (normalized.isBlank()) return null
        return normalized.toIntOrNull()
    }

    private fun displayConfigLabel(configId: String): String {
        if (configId.startsWith(USER_CONFIG_PREFIX)) {
            val importedDisplayName = vpnConfigStore.listUserConfigs()
                .firstOrNull { it.id == configId }
                ?.displayName
                ?: configId
            return trimConfSuffix(importedDisplayName)
        }
        return trimConfSuffix(configId)
    }

    private fun trimConfSuffix(value: String): String {
        return value.removeSuffix(".conf").trim()
    }
}
