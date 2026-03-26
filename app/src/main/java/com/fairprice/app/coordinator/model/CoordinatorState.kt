package com.fairprice.app.coordinator.model

import com.fairprice.app.viewmodel.EngineOverride
import com.fairprice.app.viewmodel.SummaryData

sealed interface CoordinatorProcessState {
    data object Idle : CoordinatorProcessState
    data class Processing(val message: String) : CoordinatorProcessState
    data class Success(val summary: SummaryData) : CoordinatorProcessState
    data class Error(val message: String) : CoordinatorProcessState
}

data class CoordinatorState(
    val processState: CoordinatorProcessState = CoordinatorProcessState.Idle,
    val showBrowser: Boolean = false,
)

data class StartPriceCheckParams(
    val rawSubmittedUrl: String,
    val dirtyBaselinePriceCents: Int?,
    val adminEngineOverride: EngineOverride,
    val isAdmin: Boolean,
    val shoppingSessionId: String,
)
