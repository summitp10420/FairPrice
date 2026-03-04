package com.fairprice.app.coordinator

import com.fairprice.app.coordinator.model.CoordinatorCommand
import com.fairprice.app.coordinator.model.CoordinatorState
import com.fairprice.app.coordinator.model.StartPriceCheckParams
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface PriceCheckCoordinator {
    val state: StateFlow<CoordinatorState>
    val commands: SharedFlow<CoordinatorCommand>

    fun startPriceCheck(params: StartPriceCheckParams)
    fun onVpnPermissionResult(granted: Boolean)
    fun onEnterShoppingMode()
    fun onBackToApp()
    fun onCloseShoppingSession()
    fun onAppClosing()
}
