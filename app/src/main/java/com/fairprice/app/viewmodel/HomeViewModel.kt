package com.fairprice.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairprice.app.data.FairPriceRepository
import com.fairprice.app.data.models.PriceCheck
import com.fairprice.app.engine.ExtractionEngine
import com.fairprice.app.engine.VpnEngine
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject

sealed interface HomeProcessState {
    data object Idle : HomeProcessState
    data class Processing(val message: String) : HomeProcessState
    data class Success(val message: String) : HomeProcessState
    data class Error(val message: String) : HomeProcessState
}

data class HomeUiState(
    val urlInput: String = "",
    val lastSubmittedUrl: String? = null,
    val processState: HomeProcessState = HomeProcessState.Idle,
)

class HomeViewModel(
    private val repository: FairPriceRepository,
    private val vpnEngine: VpnEngine,
    private val extractionEngine: ExtractionEngine,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

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
            )
        }

        if (submittedUrl.isBlank()) return

        viewModelScope.launch {
            var terminalError: String? = null
            var successMessage: String? = null

            try {
                _uiState.update { current ->
                    current.copy(processState = HomeProcessState.Processing("Connecting to VPN..."))
                }
                val connectResult = vpnEngine.connect("phase3-placeholder-config")
                if (connectResult.isFailure) {
                    val throwable = connectResult.exceptionOrNull()
                    terminalError = "VPN connect failed: ${throwable.toUserMessage()}"
                    Log.e("HomeViewModel", "VPN connect failed", throwable)
                    return@launch
                }

                _uiState.update { current ->
                    current.copy(
                        processState = HomeProcessState.Processing(
                            "Loading page & extracting price...",
                        ),
                    )
                }
                val extractedPriceCents = extractionEngine.loadAndExtract(submittedUrl).getOrElse { throwable ->
                    terminalError = "Price extraction failed: ${throwable.toUserMessage()}"
                    Log.e("HomeViewModel", "Price extraction failed", throwable)
                    return@launch
                }

                val priceCheck = buildPriceCheck(
                    url = submittedUrl,
                    foundPriceCents = extractedPriceCents,
                    extractionSuccessful = true,
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
                successMessage = "Price check logged. Extracted price: ${formatUsd(extractedPriceCents)}."
            } finally {
                val disconnectResult = vpnEngine.disconnect()
                if (disconnectResult.isFailure) {
                    val throwable = disconnectResult.exceptionOrNull()
                    val disconnectMessage = "VPN disconnect failed: ${throwable.toUserMessage()}"
                    Log.e("HomeViewModel", "VPN disconnect failed", throwable)
                    terminalError = terminalError?.let { "$it | $disconnectMessage" } ?: disconnectMessage
                }

                _uiState.update { current ->
                    current.copy(
                        processState = terminalError?.let { HomeProcessState.Error(it) }
                            ?: HomeProcessState.Success(successMessage ?: "Price check completed."),
                    )
                }
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
        foundPriceCents: Int,
        extractionSuccessful: Boolean,
    ): PriceCheck {
        val domain = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        return PriceCheck(
            productUrl = url,
            domain = domain,
            baselinePriceCents = 0,
            foundPriceCents = foundPriceCents,
            extractionSuccessful = extractionSuccessful,
            rawExtractionData = buildJsonObject { },
        )
    }

    private fun Throwable?.toUserMessage(): String {
        val throwable = this ?: return "Unknown error"
        return throwable.message ?: throwable::class.java.simpleName
    }

    private fun formatUsd(cents: Int): String {
        return String.format(Locale.US, "$%.2f", cents / 100.0)
    }
}
