package com.fairprice.app.coordinator.model

import android.content.Intent

sealed interface CoordinatorCommand {
    data class RequestVpnPermission(val intent: Intent) : CoordinatorCommand
}
