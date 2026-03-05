package com.fairprice.app.coordinator

import com.fairprice.app.coordinator.model.CoordinatorState
import com.fairprice.app.coordinator.model.StartPriceCheckParams
import kotlinx.coroutines.flow.StateFlow

interface PriceCheckCoordinator {
    val state: StateFlow<CoordinatorState>

    fun startPriceCheck(params: StartPriceCheckParams)
    fun onEnterShoppingMode()
    fun onBackToApp()
    fun onCloseShoppingSession()
    fun onAppClosing()
}
