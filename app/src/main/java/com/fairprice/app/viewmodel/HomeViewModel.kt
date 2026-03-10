package com.fairprice.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairprice.app.coordinator.DefaultPriceCheckCoordinator
import com.fairprice.app.coordinator.PreSpoofStageRunner
import com.fairprice.app.coordinator.PriceCheckCoordinator
import com.fairprice.app.coordinator.SpoofAttemptRunner
import com.fairprice.app.coordinator.TelemetryAssembler
import com.fairprice.app.coordinator.model.CoordinatorProcessState
import com.fairprice.app.coordinator.model.StartPriceCheckParams
import com.fairprice.app.data.FairPriceRepository
import com.fairprice.app.engine.ExtractionEngine
import com.fairprice.app.engine.StrategyResolver
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession

sealed interface HomeProcessState {
    data object Idle : HomeProcessState
    data class Processing(val message: String) : HomeProcessState
    data class Success(val summary: SummaryData) : HomeProcessState
    data class Error(val message: String) : HomeProcessState
}

enum class EngineOverride {
    AUTO,
    FORCE_CLEAN_BASELINE,
    FORCE_SHIELD_BASIC,
    FORCE_AMNESIA_STANDARD,
    FORCE_STEALTH_MAX,
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
    val isAdmin: Boolean = false,
    val adminEngineOverride: EngineOverride = EngineOverride.AUTO,
)

class HomeViewModel(
    private val repository: FairPriceRepository,
    private val extractionEngine: ExtractionEngine,
    private val strategyResolver: StrategyResolver,
    private val isAdminUser: Boolean = false,
    private val shortUrlResolver: suspend (String) -> String? = { inputUrl ->
        resolveAmazonShortUrlBestEffort(inputUrl)
    },
    private val shadowCleanControlSampler: (String) -> Boolean = { false },
) : ViewModel() {
    companion object {
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

    private val telemetryAssembler = TelemetryAssembler()
    private val coordinator: PriceCheckCoordinator =
        DefaultPriceCheckCoordinator(
            scope = viewModelScope,
            repository = repository,
            strategyResolver = strategyResolver,
            telemetryAssembler = telemetryAssembler,
            preSpoofStageRunner = PreSpoofStageRunner(
                extractionEngine = extractionEngine,
                telemetryAssembler = telemetryAssembler,
                shadowCleanControlSampler = shadowCleanControlSampler,
            ),
            spoofAttemptRunner = SpoofAttemptRunner(
                extractionEngine = extractionEngine,
                telemetryAssembler = telemetryAssembler,
            ),
            shortUrlResolver = shortUrlResolver,
        )

    init {
        _uiState.update { it.copy(isAdmin = isAdminUser) }
        viewModelScope.launch {
            extractionEngine.currentSession.collect { session ->
                _uiState.update { current -> current.copy(activeSession = session) }
            }
        }
        viewModelScope.launch {
            coordinator.state.collect { state ->
                val mapped = when (val process = state.processState) {
                    is CoordinatorProcessState.Idle -> HomeProcessState.Idle
                    is CoordinatorProcessState.Processing -> HomeProcessState.Processing(process.message)
                    is CoordinatorProcessState.Success -> HomeProcessState.Success(process.summary)
                    is CoordinatorProcessState.Error -> HomeProcessState.Error(process.message)
                }
                _uiState.update { current ->
                    current.copy(
                        showBrowser = state.showBrowser,
                        processState = mapped,
                    )
                }
            }
        }
    }

    fun onUrlInputChanged(value: String) {
        _uiState.update { current -> current.copy(urlInput = value) }
    }

    fun onDirtyBaselineInputChanged(value: String) {
        val sanitized = sanitizeDigitsOnly(value)
        _uiState.update { current -> current.copy(dirtyBaselineInputRaw = sanitized) }
    }

    fun onSharedTextReceived(sharedText: String?) {
        val extractedUrl = extractFirstUrl(sharedText).orEmpty()
        if (extractedUrl.isNotBlank()) {
            _uiState.update { current -> current.copy(urlInput = extractedUrl) }
        }
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
        coordinator.startPriceCheck(
            StartPriceCheckParams(
                rawSubmittedUrl = rawSubmittedUrl,
                dirtyBaselinePriceCents = dirtyBaselinePriceCents,
                adminEngineOverride = _uiState.value.adminEngineOverride,
                isAdmin = _uiState.value.isAdmin,
            ),
        )
    }

    fun onEnterShoppingMode() {
        coordinator.onEnterShoppingMode()
    }

    fun onBackToApp() {
        coordinator.onBackToApp()
    }

    fun onCloseShoppingSession() {
        coordinator.onCloseShoppingSession()
        _uiState.update { current ->
            current.copy(
                urlInput = "",
                dirtyBaselineInputRaw = "",
                lastSubmittedUrl = null,
                showBrowser = false,
            )
        }
    }

    fun onAppClosing() {
        coordinator.onAppClosing()
    }

    private fun extractFirstUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val regex = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
        return regex.find(value)?.value
    }

    private fun sanitizeDigitsOnly(value: String): String = value.filter(Char::isDigit)

    private fun parseDirtyBaselineCents(raw: String): Int? {
        val normalized = sanitizeDigitsOnly(raw)
        if (normalized.isBlank()) return null
        return normalized.toIntOrNull()
    }
}
