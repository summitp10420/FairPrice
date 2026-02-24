package com.fairprice.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairprice.app.data.FairPriceRepository
import com.fairprice.app.data.models.PriceCheck
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject

data class HomeUiState(
    val urlInput: String = "",
    val lastSubmittedUrl: String? = null,
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
        _uiState.update { current -> current.copy(lastSubmittedUrl = submittedUrl) }

        if (submittedUrl.isBlank()) return

        viewModelScope.launch {
            repository.logPriceCheck(buildPriceCheck(submittedUrl))
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
            id = UUID.randomUUID().toString(),
            userId = "00000000-0000-0000-0000-000000000000",
            productUrl = url,
            domain = domain,
            baselinePriceCents = 0,
            foundPriceCents = 0,
            strategyId = "",
            extractionSuccessful = false,
            rawExtractionData = buildJsonObject { },
            createdAt = Instant.now().toString(),
        )
    }
}
