package com.fairprice.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairprice.app.data.FairPriceRepository
import com.fairprice.app.data.models.PriceCheck
import com.fairprice.app.engine.ExtractionEngine
import com.fairprice.app.engine.ExtractionResult
import com.fairprice.app.engine.PricingStrategyEngine
import com.fairprice.app.engine.VpnEngine
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mozilla.geckoview.GeckoSession

sealed interface HomeProcessState {
    data object Idle : HomeProcessState
    data class Processing(val message: String) : HomeProcessState
    data class Success(val summary: SummaryData) : HomeProcessState
    data class Error(val message: String) : HomeProcessState
}

data class SummaryData(
    val baselinePrice: String,
    val spoofedPrice: String,
    val tactics: List<String>,
    val strategyName: String,
)

data class HomeUiState(
    val urlInput: String = "",
    val lastSubmittedUrl: String? = null,
    val processState: HomeProcessState = HomeProcessState.Idle,
    val activeSession: GeckoSession? = null,
    val showBrowser: Boolean = false,
)

class HomeViewModel(
    private val repository: FairPriceRepository,
    private val vpnEngine: VpnEngine,
    private val extractionEngine: ExtractionEngine,
    private val strategyEngine: PricingStrategyEngine,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var shoppingVpnActive: Boolean = false

    init {
        viewModelScope.launch {
            extractionEngine.currentSession.collect { session ->
                _uiState.update { current ->
                    current.copy(activeSession = session)
                }
            }
        }
    }

    fun onUrlInputChanged(value: String) {
        _uiState.update { current ->
            current.copy(urlInput = value)
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

    fun onCheckPriceClicked() {
        val submittedUrl = _uiState.value.urlInput.trim()
        _uiState.update { current ->
            current.copy(
                lastSubmittedUrl = submittedUrl,
                processState = HomeProcessState.Idle,
                showBrowser = false,
            )
        }

        if (submittedUrl.isBlank()) return

        viewModelScope.launch {
            var terminalError: String? = null
            var successSummary: SummaryData? = null
            var vpnConnectedThisRun = false
            var keepVpnForShopping = false
            var finalShowBrowser = false

            try {
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
                val baselineResult = extractionEngine.loadAndExtract(submittedUrl).getOrElse { throwable ->
                    terminalError =
                        "Baseline extraction failed: ${throwable.toUserMessage()}. You can continue shopping normally."
                    Log.e("HomeViewModel", "Baseline extraction failed", throwable)
                    _uiState.update { current ->
                        current.copy(processState = HomeProcessState.Processing("Logging fallback result..."))
                    }
                    val fallbackPriceCheck = buildPriceCheck(
                        url = submittedUrl,
                        strategyId = null,
                        baselinePriceCents = 0,
                        foundPriceCents = 0,
                        extractionSuccessful = false,
                        tactics = emptyList(),
                    )
                    val fallbackLogResult = repository.logPriceCheck(fallbackPriceCheck)
                    if (fallbackLogResult.isFailure) {
                        val logThrowable = fallbackLogResult.exceptionOrNull()
                        val logMessage =
                            "Supabase fallback log failed: ${logThrowable.toUserMessage()}"
                        Log.e("HomeViewModel", "price_checks fallback insert failed", logThrowable)
                        terminalError = "$terminalError | $logMessage"
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
                        baselineTactics = baselineResult.tactics,
                    ).getOrElse { throwable ->
                        terminalError = "Strategy resolution failed: ${throwable.toUserMessage()}"
                        Log.e("HomeViewModel", "Strategy resolution failed", throwable)
                        return@launch
                    }

                _uiState.update { current ->
                    current.copy(processState = HomeProcessState.Processing("Connecting VPN..."))
                }
                val connectResult = vpnEngine.connect(strategy.wireguardConfig)
                if (connectResult.isFailure) {
                    val throwable = connectResult.exceptionOrNull()
                    terminalError = "VPN connect failed: ${throwable.toUserMessage()}"
                    Log.e("HomeViewModel", "VPN connect failed", throwable)
                    return@launch
                }
                vpnConnectedThisRun = true

                _uiState.update { current ->
                    current.copy(
                        processState = HomeProcessState.Processing(
                            "Extracting spoofed price...",
                        ),
                    )
                }
                val spoofedResult = extractionEngine.loadAndExtract(submittedUrl).getOrElse { throwable ->
                    terminalError = "Spoofed extraction failed: ${throwable.toUserMessage()}"
                    Log.e("HomeViewModel", "Spoofed extraction failed", throwable)
                    return@launch
                }

                val priceCheck = buildPriceCheck(
                    url = submittedUrl,
                    strategyId = strategy.strategyId,
                    baselinePriceCents = baselineResult.priceCents,
                    foundPriceCents = spoofedResult.priceCents,
                    extractionSuccessful = true,
                    tactics = baselineResult.tactics,
                )

                _uiState.update { current ->
                    current.copy(processState = HomeProcessState.Processing("Logging to database..."))
                }
                val logResult = repository.logPriceCheck(priceCheck)
                if (logResult.isFailure) {
                    val throwable = logResult.exceptionOrNull()
                    terminalError = "Supabase log failed: ${throwable.toUserMessage()}"
                    Log.e("HomeViewModel", "price_checks insert failed", throwable)
                    return@launch
                }

                Log.i("HomeViewModel", "price_checks insert succeeded")
                successSummary = buildSuccessSummary(
                    baselineResult = baselineResult,
                    spoofedResult = spoofedResult,
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

    fun onEnterShoppingMode() {
        _uiState.update { current ->
            current.copy(showBrowser = true)
        }
    }

    fun onCloseShoppingSession() {
        viewModelScope.launch {
            var terminalError: String? = null
            if (shoppingVpnActive) {
                val disconnectResult = vpnEngine.disconnect()
                if (disconnectResult.isFailure) {
                    val throwable = disconnectResult.exceptionOrNull()
                    terminalError = "VPN disconnect failed: ${throwable.toUserMessage()}"
                    Log.e("HomeViewModel", "VPN disconnect failed on close", throwable)
                } else {
                    shoppingVpnActive = false
                }
            }

            _uiState.update { current ->
                current.copy(
                    showBrowser = false,
                    processState = terminalError?.let { HomeProcessState.Error(it) }
                        ?: HomeProcessState.Idle,
                )
            }
        }
    }

    private fun extractFirstUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val regex = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
        return regex.find(value)?.value
    }

    private fun buildPriceCheck(
        url: String,
        strategyId: String?,
        baselinePriceCents: Int,
        foundPriceCents: Int,
        extractionSuccessful: Boolean,
        tactics: List<String>,
    ): PriceCheck {
        val domain = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        return PriceCheck(
            productUrl = url,
            domain = domain,
            baselinePriceCents = baselinePriceCents,
            foundPriceCents = foundPriceCents,
            strategyId = strategyId,
            extractionSuccessful = extractionSuccessful,
            rawExtractionData = buildJsonObject {
                put(
                    "detected_tactics",
                    JsonArray(tactics.map { JsonPrimitive(it) }),
                )
            },
        )
    }

    private fun Throwable?.toUserMessage(): String {
        val throwable = this ?: return "Unknown error"
        return throwable.message ?: throwable::class.java.simpleName
    }

    private fun formatUsd(cents: Int): String {
        return String.format(Locale.US, "$%.2f", cents / 100.0)
    }

    private fun buildSuccessSummary(
        baselineResult: ExtractionResult,
        spoofedResult: ExtractionResult,
    ): SummaryData {
        val baseline = formatUsd(baselineResult.priceCents)
        val spoofed = formatUsd(spoofedResult.priceCents)
        return SummaryData(
            baselinePrice = baseline,
            spoofedPrice = spoofed,
            tactics = baselineResult.tactics,
            strategyName = "Default Strategy (stub)",
        )
    }
}
