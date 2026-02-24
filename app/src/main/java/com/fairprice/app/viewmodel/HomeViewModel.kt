package com.fairprice.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairprice.app.data.FairPriceRepository
import com.fairprice.app.data.models.PriceCheck
import java.net.URI
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject

data class HomeUiState(
    val urlInput: String = "",
    val lastSubmittedUrl: String? = null,
    val lastLogStatusMessage: String? = null,
)

class HomeViewModel(
    private val repository: FairPriceRepository,
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
                lastLogStatusMessage = null,
            )
        }

        if (submittedUrl.isBlank()) return

        viewModelScope.launch {
            val result = repository.logPriceCheck(buildPriceCheck(submittedUrl))
            result
                .onSuccess {
                    _uiState.update { current ->
                        current.copy(lastLogStatusMessage = "Price check log sent to Supabase.")
                    }
                    Log.i("HomeViewModel", "price_checks insert succeeded")
                }
                .onFailure { throwable ->
                    val message = throwable.message ?: throwable::class.java.simpleName
                    _uiState.update { current ->
                        current.copy(lastLogStatusMessage = "Supabase log failed: $message")
                    }
                    Log.e("HomeViewModel", "price_checks insert failed", throwable)
                }
        }
    }

    private fun extractFirstUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val regex = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
        return regex.find(value)?.value
    }

    private fun buildPriceCheck(url: String): PriceCheck {
        val domain = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        return PriceCheck(
            productUrl = url,
            domain = domain,
            baselinePriceCents = 0,
            foundPriceCents = 0,
            extractionSuccessful = false,
            rawExtractionData = buildJsonObject { },
        )
    }
}
